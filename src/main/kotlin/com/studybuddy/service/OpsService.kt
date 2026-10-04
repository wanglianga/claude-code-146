package com.studybuddy.service

import com.studybuddy.api.ReconcileReport
import com.studybuddy.api.StoreOperationReport
import com.studybuddy.domain.*
import com.studybuddy.infra.GatePassService
import com.studybuddy.infra.TimeService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import kotlin.math.max
import kotlin.math.min

/**
 * 运营与对账：
 * - 门店报表：上座率、延时率、投诉率、设备故障数、争议根因分布（设备故障 vs 用户迟到/权益滥用）、收支
 * - 对账修复：扫描“门禁/订单/座位/计费”间的漂移并修复，保证时间轴一致
 */
@Service
class OpsService(
    private val storeRepo: StoreRepository,
    private val seatRepo: SeatRepository,
    private val reservationRepo: ReservationRepository,
    private val complaintRepo: ComplaintRepository,
    private val ticketRepo: SupportTicketRepository,
    private val deviceRepo: DeviceEventRepository,
    private val billingRepo: BillingEntryRepository,
    private val gateLogRepo: GateLogRepository,
    private val timeline: TimelineService,
    private val gatePass: GatePassService,
    private val time: TimeService
) {
    @Transactional(readOnly = true)
    fun report(storeId: Long, from: Instant, to: Instant): StoreOperationReport {
        val store = storeRepo.findById(storeId).orElseThrow { NotFoundException("门店", storeId) }
        val seats = seatRepo.findByStoreId(storeId)
        val reservations = reservationRepo.findByStoreId(storeId)
            .filter { it.startTime in from.toEpochMilli()..to.toEpochMilli() ||
                it.endTime in from.toEpochMilli()..to.toEpochMilli() ||
                (it.startTime < from.toEpochMilli() && it.endTime > to.toEpochMilli()) }

        val counted = reservations.filter {
            it.status !in listOf(ReservationStatus.PENDING_PAYMENT, ReservationStatus.CANCELLED, ReservationStatus.NO_SHOW)
        }

        // 上座率：占用座位分钟数 / (座位数 × 窗口分钟数)
        val windowMin = max(1.0, (to.toEpochMilli() - from.toEpochMilli()) / 60_000.0)
        val occupiedMin = counted.sumOf { r ->
            val s = max(r.startTime, from.toEpochMilli())
            val e = min(r.scheduledEndTime.takeIf { it > 0 } ?: r.endTime, to.toEpochMilli())
            max(0L, e - s) / 60_000.0
        }
        val occupancy = if (seats.isEmpty()) 0.0 else occupiedMin / (seats.size * windowMin)

        val checkedOut = counted.filter { it.checkOutAt != null }
        val overtimeCount = checkedOut.count { it.overtimeMinutes > 0 }
        val overtimeRate = if (checkedOut.isEmpty()) 0.0 else overtimeCount.toDouble() / checkedOut.size

        val complaintCount = complaintRepo.countByStoreIdAndCreatedAtBetween(storeId, from, to)
        val complaintRate = if (counted.isEmpty()) 0.0 else complaintCount.toDouble() / counted.size

        val faults = deviceRepo.findByStoreIdOrderByCreatedAtDesc(storeId)
            .filter { it.eventType == DeviceEventType.REPORTED && !it.createdAt.isBefore(from) && !it.createdAt.isAfter(to) }

        val rootCounts = RootCause.entries.associateWith { rc ->
            ticketRepo.countByStoreIdAndRootCauseAndCreatedAtBetween(storeId, rc, from, to).toInt()
        }.filterValues { it > 0 }

        val deviceDisputes = (rootCounts[RootCause.DEVICE_FAULT] ?: 0)
        val lateAbuseDisputes = (rootCounts[RootCause.USER_LATE] ?: 0) + (rootCounts[RootCause.BENEFIT_ABUSE] ?: 0)

        var revenue = BigDecimal.ZERO
        var refunded = BigDecimal.ZERO
        var compensated = BigDecimal.ZERO
        reservations.forEach { r ->
            billingRepo.findByReservationIdOrderByAtAsc(r.id!!)
                .filter { !it.at.isBefore(from) && !it.at.isAfter(to) }
                .forEach { b ->
                    when (b.type) {
                        BillingType.BOOKING_FEE, BillingType.OVERTIME_FEE -> revenue = revenue.add(b.amount)
                        BillingType.REFUND -> refunded = refunded.add(b.amount.abs())
                        BillingType.COMPENSATION -> compensated = compensated.add(b.amount.abs())
                        else -> {}
                    }
                }
        }

        return StoreOperationReport(
            storeId = storeId, storeName = store.name,
            windowFrom = from, windowTo = to,
            totalSeats = seats.size,
            reservationCount = counted.size,
            occupancyRate = round3(occupancy),
            overtimeRate = round3(overtimeRate),
            complaintRate = round3(complaintRate),
            deviceFaultCount = faults.size,
            disputesByRootCause = rootCounts,
            disputesFromDeviceFault = deviceDisputes,
            disputesFromUserLateOrAbuse = lateAbuseDisputes,
            revenue = revenue, refunded = refunded, compensated = compensated
        )
    }

    /**
     * 对账修复（幂等）：
     * A. 座位被标记占用，但占用订单已完结/退费/取消 → 释放座位与镜像（退费后座位仍锁定）
     * B. 存在放行 IN 日志且无 OUT，但订单仍是 BOOKED（门禁已放行订单却未开始）→ 置 IN_PROGRESS 并占用座位
     * C. Redis 占用镜像与 DB 不一致 → 以 DB 为准重放镜像
     */
    @Transactional
    fun reconcile(): ReconcileReport {
        val repairs = mutableListOf<String>()
        var checked = 0

        seatRepo.findAll().forEach { seat ->
            val occupantId = seat.occupiedByReservationId
            if (occupantId != null) {
                checked++
                val r = reservationRepo.findById(occupantId).orElse(null)
                if (r == null || r.status in listOf(
                        ReservationStatus.COMPLETED, ReservationStatus.REFUNDED,
                        ReservationStatus.CANCELLED, ReservationStatus.NO_SHOW) || r.checkOutAt != null
                ) {
                    seat.status = SeatStatus.FREE
                    seat.occupiedByReservationId = null
                    gatePass.mirrorSeatFree(seat.id!!)
                    repairs += "座位 ${seat.seatCode}(#${seat.id}) 的占用订单 #$occupantId 已结束但座位仍锁定，已释放"
                }
            }
            // C. 镜像对账
            val mirror = gatePass.mirrorOccupant(seat.id!!)
            val dbOcc = seat.occupiedByReservationId
            if (mirror != dbOcc) {
                if (dbOcc != null) gatePass.mirrorSeatOccupied(seat.id!!, dbOcc)
                else gatePass.mirrorSeatFree(seat.id!!)
                repairs += "座位 ${seat.seatCode}(#${seat.id}) Redis 占用镜像($mirror) 与 DB($dbOcc) 不一致，已以 DB 为准修复"
            }
        }

        // B. 已放行进场但订单未推进
        reservationRepo.findAll().forEach { r ->
            checked++
            val allowedIn = gateLogRepo.findByReservationIdOrderByOccurredAtAsc(r.id!!)
                .any { it.direction == GateDirection.IN && it.result == GateResult.ALLOW }
            if (allowedIn && r.status == ReservationStatus.BOOKED && r.checkInAt == null) {
                r.status = ReservationStatus.IN_PROGRESS
                r.checkInAt = gateLogRepo.findByReservationIdOrderByOccurredAtAsc(r.id!!)
                    .first { it.direction == GateDirection.IN && it.result == GateResult.ALLOW }.occurredAt
                r.seat?.let { s ->
                    s.status = SeatStatus.OCCUPIED
                    s.occupiedByReservationId = r.id
                    gatePass.mirrorSeatOccupied(s.id!!, r.id!!)
                }
                repairs += "订单 ${r.orderNo} 已有放行进场记录但状态仍为 BOOKED，已修正为 IN_PROGRESS 并占用座位"
            }
        }

        repairs.forEach { msg -> timeline.add(TimelineType.RECONCILED, detail = "[对账] $msg") }
        return ReconcileReport(checkedReservations = checked, repairs = repairs, inconsistent = repairs.isNotEmpty())
    }

    private fun round3(d: Double) = Math.round(d * 1000.0) / 1000.0
}
