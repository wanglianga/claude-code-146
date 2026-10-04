package com.studybuddy.service

import com.studybuddy.api.GateAttemptRequest
import com.studybuddy.api.GateAttemptResponse
import com.studybuddy.domain.*
import com.studybuddy.infra.GatePassService
import com.studybuddy.infra.Locks
import com.studybuddy.infra.Tx
import com.studybuddy.infra.TimeService
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicLong

/**
 * 物理门禁判定服务。
 * 不变量：
 * - 每次刷卡（放行/拒绝）都落 gate_logs + 时间轴，附监控事件编号；
 * - 放行 IN 的同一事务内：预约 BOOKED→IN_PROGRESS、记录进场时间、座位 OCCUPIED + Redis 镜像，
 *   杜绝“门已开但订单仍未开始”；
 * - 放行 OUT 的同一事务内结算并释放座位，杜绝“已退费/已离场但座位仍锁”。
 * 座位级操作一律在“数据库事务 + Redis 座位锁”内完成，且每笔操作只获取一次锁。
 */
@Service
class GateService(
    private val reservationRepo: ReservationRepository,
    private val storeRepo: StoreRepository,
    private val gateRepo: GateLogRepository,
    private val deviceRepo: DeviceEventRepository,
    private val sensorRepo: SensorReadingRepository,
    private val reservations: ReservationService,
    private val timeline: TimelineService,
    private val gatePass: GatePassService,
    private val locks: Locks,
    private val tx: Tx,
    private val time: TimeService,
    private val props: StudyroomProps
) {
    private val cctvSeq = AtomicLong(0)

    fun attempt(req: GateAttemptRequest): GateAttemptResponse {
        val r0 = resolve(req)
        return when (req.direction) {
            GateDirection.IN -> checkIn(r0, req.deviceSn)
            GateDirection.OUT -> checkOut(r0, req.deviceSn)
        }
    }

    private fun resolve(req: GateAttemptRequest): Reservation? {
        val r = when {
            req.reservationId != null -> reservationRepo.findById(req.reservationId).orElse(null)
            !req.orderNo.isNullOrBlank() -> reservationRepo.findByOrderNo(req.orderNo)
            req.userId != null -> findActiveForUser(req.userId)
            else -> null
        }
        if (r != null && req.userId != null && r.user.id != req.userId) {
            throw RuleRejectException("预约单与刷卡用户不匹配", GateReason.RESERVATION_INVALID)
        }
        return r
    }

    /** 用户刷脸/刷卡不带单号：找其当前应进场或在场的订单 */
    private fun findActiveForUser(userId: Long): Reservation? =
        reservationRepo.findByUserId(userId)
            .filter { it.status in listOf(ReservationStatus.BOOKED, ReservationStatus.IN_PROGRESS, ReservationStatus.DISPUTED) }
            .maxByOrNull { it.startTime }

    // ---------------- 进场 ----------------

    private fun checkIn(r0: Reservation?, deviceSn: String?): GateAttemptResponse {
        if (r0 == null) {
            return saveAnonymousDeny(GateDirection.IN, GateReason.RESERVATION_INVALID, deviceSn)
        }
        val seatId = r0.seat?.id
            ?: return tx.run { saveDeny(r0, GateDirection.IN, GateReason.RESERVATION_INVALID, deviceSn) }

        return tx.run {
            locks.withLock(Locks.seatKey(seatId)) {
                evaluateIn(reservations.getOrThrow(r0.id!!), deviceSn)
            }
        }
    }

    private fun evaluateIn(r: Reservation, deviceSn: String?): GateAttemptResponse {
        val now = time.now()
        val nowMs = now.toEpochMilli()

        if (r.user.blacklisted) return saveDeny(r, GateDirection.IN, GateReason.USER_BLACKLISTED, deviceSn)

        val store = r.store
        if (store.status != StoreStatus.OPEN) return saveDeny(r, GateDirection.IN, GateReason.STORE_OUTAGE, deviceSn)

        // 门锁设备故障（最近一条未恢复）
        val lockFault = deviceRepo.findTopByStoreIdAndDeviceTypeOrderByCreatedAtDesc(store.id!!, DeviceType.DOOR_LOCK)
        if (lockFault != null && lockFault.eventType == DeviceEventType.REPORTED) {
            return saveDeny(r, GateDirection.IN, GateReason.GATE_DEVICE_FAULT, deviceSn)
        }

        // 支付优先判定（待支付单给闸机明确的“未支付”提示）
        if (!r.paid) return saveDeny(r, GateDirection.IN, GateReason.NOT_PAID, deviceSn)

        if (r.status == ReservationStatus.IN_PROGRESS) {
            return saveDeny(r, GateDirection.IN, GateReason.ALREADY_ENTERED, deviceSn)
        }
        // 争议中但尚未进场（如换座后的下一位用户）：仍按待进场判定
        if (r.status == ReservationStatus.DISPUTED && r.checkInAt != null) {
            return saveDeny(r, GateDirection.IN, GateReason.ALREADY_ENTERED, deviceSn)
        }
        if (r.status != ReservationStatus.BOOKED &&
            !(r.status == ReservationStatus.DISPUTED && r.checkInAt == null)) {
            return saveDeny(r, GateDirection.IN, GateReason.RESERVATION_INVALID, deviceSn)
        }
        if (!r.user.idVerified) return saveDeny(r, GateDirection.IN, GateReason.REALNAME_MISSING, deviceSn)

        val seat = r.seat
        if (seat != null && seat.status == SeatStatus.MAINTENANCE) {
            return saveDeny(r, GateDirection.IN, GateReason.SEAT_MAINTENANCE, deviceSn)
        }

        // 时间窗：开始前 earlyEntryMinutes ~ 开始后 lateGraceMinutes
        val earlyMs = props.gate.earlyEntryMinutes * 60_000L
        val graceMs = props.gate.lateGraceMinutes * 60_000L
        if (nowMs < r.startTime - earlyMs) {
            return saveDeny(r, GateDirection.IN, GateReason.OUTSIDE_WINDOW, deviceSn)
        }
        if (nowMs > r.startTime + graceMs) {
            return saveDeny(r, GateDirection.IN, GateReason.OUTSIDE_WINDOW, deviceSn,
                "已超过迟到宽限 ${props.gate.lateGraceMinutes} 分钟，请先申请顺延或重新预约")
        }

        if (seat != null) {
            if (seat.occupiedByReservationId != null && seat.occupiedByReservationId != r.id) {
                return saveDeny(r, GateDirection.IN, GateReason.SEAT_OCCUPIED, deviceSn,
                    "座位被订单 ${seat.occupiedByReservationId} 占用")
            }
            val occ = sensorRepo.findFirstBySeatIdAndSensorTypeOrderByReportedAtDesc(seat.id!!, SensorType.OCCUPANCY)
            if (occ != null && occ.value >= 1.0 && seat.occupiedByReservationId == null) {
                return saveDeny(r, GateDirection.IN, GateReason.SEAT_OCCUPIED, deviceSn,
                    "占用传感器检测到座位有人（读数@${occ.reportedAt}）")
            }
        }
        if (r.encroachedByReservationId != null) {
            return saveDeny(r, GateDirection.IN, GateReason.SEAT_OCCUPIED, deviceSn,
                "上一位用户顺延仍占用本座位，请申请换座")
        }

        // 放行：状态迁移 + 座位占用 + 凭证刷新同事务
        r.status = ReservationStatus.IN_PROGRESS
        r.checkInAt = now
        seat?.let {
            it.status = SeatStatus.OCCUPIED
            it.occupiedByReservationId = r.id
            gatePass.mirrorSeatOccupied(it.id!!, r.id!!)
        }
        reservations.issuePass(r)
        val log = saveGateLog(r, GateDirection.IN, GateResult.ALLOW, GateReason.OK, deviceSn)
        timeline.add(TimelineType.CHECK_IN, r, actorType = ActorType.GATE,
            detail = "放行进场 闸机=${deviceSn ?: "-"} 监控事件=${log.cctvEventId}")
        return toResponse(log, r.id)
    }

    // ---------------- 离场 ----------------

    private fun checkOut(r0: Reservation?, deviceSn: String?): GateAttemptResponse {
        if (r0 == null) {
            return saveAnonymousDeny(GateDirection.OUT, GateReason.RESERVATION_INVALID, deviceSn)
        }
        val seatId = r0.seat?.id
            ?: return tx.run { saveDeny(r0, GateDirection.OUT, GateReason.RESERVATION_INVALID, deviceSn) }

        return tx.run {
            locks.withLock(Locks.seatKey(seatId)) {
                val r = reservations.getOrThrow(r0.id!!)
                if (r.status !in listOf(ReservationStatus.IN_PROGRESS, ReservationStatus.DISPUTED)) {
                    saveDeny(r, GateDirection.OUT, GateReason.NOT_INSIDE, deviceSn)
                } else {
                    // 锁内、同事务结算（延时费/押金/释放座位），随后写放行日志
                    val data = reservations.checkoutLocked(
                        r, time.now(), ActorType.GATE, deviceSn
                    )
                    val log = saveGateLog(data.reservation, GateDirection.OUT, GateResult.ALLOW, GateReason.OK, deviceSn)
                    toResponse(log, r.id)
                }
            }
        }
    }

    // ---------------- 日志 ----------------

    private fun saveDeny(
        r: Reservation,
        direction: GateDirection,
        reason: GateReason,
        deviceSn: String?,
        extraDetail: String? = null
    ): GateAttemptResponse {
        val log = saveGateLog(r, direction, GateResult.DENY, reason, deviceSn)
        timeline.add(
            TimelineType.GATE_DENIED, r,
            actorType = ActorType.GATE,
            detail = "拒绝${if (direction == GateDirection.IN) "进场" else "离场"}：${reason.note}" +
                    (extraDetail?.let { "；$it" } ?: "") + " 监控事件=${log.cctvEventId}"
        )
        return toResponse(log, r.id)
    }

    private fun saveAnonymousDeny(
        direction: GateDirection,
        reason: GateReason,
        deviceSn: String?
    ): GateAttemptResponse {
        val log = gateRepo.save(
            GateLog(
                reservation = null, storeId = -1L, seatId = null, userId = null,
                direction = direction, result = GateResult.DENY, reason = reason,
                occurredAt = time.now(), deviceSn = deviceSn,
                cctvEventId = "CCTV-X-${time.nowMillis()}-${cctvSeq.incrementAndGet()}"
            )
        )
        return toResponse(log, null)
    }

    private fun saveGateLog(
        r: Reservation?,
        direction: GateDirection,
        result: GateResult,
        reason: GateReason,
        deviceSn: String?
    ): GateLog {
        val now = time.now()
        val cctv = "CCTV-${r?.store?.id ?: "X"}-${now.toEpochMilli()}-${cctvSeq.incrementAndGet()}"
        return gateRepo.save(
            GateLog(
                reservation = r,
                storeId = r?.store?.id ?: -1L,
                seatId = r?.seat?.id,
                userId = r?.user?.id,
                direction = direction,
                result = result,
                reason = reason,
                occurredAt = now,
                deviceSn = deviceSn,
                cctvEventId = cctv
            )
        )
    }

    private fun toResponse(log: GateLog, reservationId: Long?): GateAttemptResponse =
        GateAttemptResponse(
            result = log.result,
            reason = log.reason,
            gateLogId = log.id!!,
            cctvEventId = log.cctvEventId!!,
            reservationId = reservationId,
            at = log.occurredAt
        )
}
