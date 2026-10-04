package com.studybuddy.service

import com.studybuddy.api.*
import com.studybuddy.domain.*
import com.studybuddy.infra.GatePass
import com.studybuddy.infra.GatePassService
import com.studybuddy.infra.Locks
import com.studybuddy.infra.TimeService
import com.studybuddy.infra.Tx
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong

/**
 * 预约/时段/结算核心服务。
 *
 * 并发模型（关键不变量）：
 * 1. 所有触碰“座位时间轴”的操作（下单、顺延、延时、换座、进场、离场）
 *    都在 Redis 座位锁内执行，且锁在数据库事务内部获取/释放，保证
 *    “重叠校验 + 状态迁移 + 座位镜像”原子可见。
 * 2. 多座位操作（换座）按 seatId 升序加锁，杜绝跨座位死锁。
 * 3. 结算/退费完成的同一事务内释放座位并删除 Redis 占用镜像，
 *    不会出现“费用已退但座位仍锁定”。
 */
@Service
class ReservationService(
    private val reservationRepo: ReservationRepository,
    private val seatRepo: SeatRepository,
    private val storeRepo: StoreRepository,
    private val userRepo: AppUserRepository,
    private val passRepo: MonthlyPassRepository,
    private val billing: BillingService,
    private val timeline: TimelineService,
    private val gatePass: GatePassService,
    private val locks: Locks,
    private val tx: Tx,
    private val time: TimeService,
    private val props: StudyroomProps
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val orderSeq = AtomicLong(0)

    val OCCUPYING_STATUSES = listOf(
        ReservationStatus.BOOKED, ReservationStatus.IN_PROGRESS, ReservationStatus.DISPUTED
    )

    fun getOrThrow(id: Long): Reservation =
        reservationRepo.findById(id).orElseThrow { NotFoundException("预约单", id) }

    fun getByOrderNoOrThrow(orderNo: String): Reservation =
        reservationRepo.findByOrderNo(orderNo) ?: throw NotFoundException("预约单", orderNo)

    /** 事务内、座位锁内执行（嵌套 tx.run 自动加入当前事务） */
    private fun <T> inSeatLock(seatId: Long, block: () -> T): T = tx.run {
        locks.withLock(Locks.seatKey(seatId)) { block() }
    }

    private fun <T> inSeatLocks(seatIds: Collection<Long>, block: () -> T): T = tx.run {
        val sorted = seatIds.filter { it > 0 }.distinct().sorted()
        fun next(i: Int): T =
            if (i >= sorted.size) block()
            else locks.withLock(Locks.seatKey(sorted[i])) { next(i + 1) }
        next(0)
    }

    // ---------------- 创建预约 ----------------

    fun create(req: CreateReservationRequest): Reservation {
        if (req.endTime <= req.startTime) throw RuleRejectException("结束时间必须晚于开始时间")

        // 幂等：同 key 直接返回
        req.idempotencyKey?.let { key ->
            reservationRepo.findByIdempotencyKey(key)?.let { return it }
        }

        // 先确定要锁的座位（固定座包月未显式传 seatId 时取权益绑定座）
        val lockSeatId = req.seatId ?: run {
            if (req.passId == null) throw RuleRejectException("非包月预约必须选择座位")
            passRepo.findFixedSeatId(req.passId) ?: throw RuleRejectException("包月浮动座也请选择具体座位")
        }

        return inSeatLock(lockSeatId) {
            doCreate(req)
        }
    }

    private fun doCreate(req: CreateReservationRequest): Reservation {
        val user = userRepo.findById(req.userId).orElseThrow { NotFoundException("用户", req.userId) }
        val store = storeRepo.findById(req.storeId).orElseThrow { NotFoundException("门店", req.storeId) }

        val pass = req.passId?.let { pid ->
            passRepo.findById(pid).orElseThrow { NotFoundException("包月权益", pid) }.also { p ->
                if (p.user.id != user.id) throw RuleRejectException("包月权益不属于该用户")
                if (p.storeId != store.id) throw RuleRejectException("包月权益不可跨门店使用")
                if (p.status != PassStatus.ACTIVE) throw RuleRejectException("包月权益不可用")
                val day = LocalDate.ofEpochDay(req.startTime / 86_400_000)
                if (day !in p.validFrom..p.validTo) throw RuleRejectException("预约日期不在包月有效期内")
            }
        }

        val seat = resolveSeat(req, pass, store)
        if (seat.status == SeatStatus.MAINTENANCE) throw RuleRejectException("座位维护中", GateReason.SEAT_MAINTENANCE)
        if (pass == null) checkFixedPassConflict(seat, req.startTime, req.endTime, user)

        val overlap = reservationRepo.findOverlapping(
            seat.id!!, req.startTime, req.endTime, OCCUPYING_STATUSES
        )
        if (overlap.isNotEmpty()) {
            throw ConflictException("座位该时段已被预约：${overlap.first().orderNo}")
        }

        val isPassBooking = pass != null
        val price = if (isPassBooking) BigDecimal.ZERO
        else billing.quote(req.startTime, req.endTime, seat.hourlyPrice)
        val deposit = if (isPassBooking) BigDecimal.ZERO else computeDeposit(req.reservationType, req.needLocker)

        val r = Reservation(
            orderNo = genOrderNo(),
            user = user,
            store = store,
            seat = seat,
            reservationType = req.reservationType,
            startTime = req.startTime,
            endTime = req.endTime,
            scheduledEndTime = req.endTime,
            status = ReservationStatus.PENDING_PAYMENT,
            price = price,
            deposit = deposit,
            needLocker = req.needLocker,
            silentRuleAccepted = req.silentRuleAccepted,
            paid = false,
            idempotencyKey = req.idempotencyKey,
            remark = pass?.let { "包月权益下单(${it.passNo})" }
        )
        val saved = reservationRepo.save(r)
        timeline.add(TimelineType.CREATED, saved, actorType = ActorType.USER, actorId = user.id.toString(),
            detail = "订单创建 ${seat.seatCode} ${req.reservationType}")

        if (isPassBooking) {
            saved.paid = true
            saved.paidAt = time.now()
            saved.status = ReservationStatus.BOOKED
            issuePass(saved)
            timeline.add(TimelineType.PAID, saved, detail = "包月权益核销，金额 0")
        }
        return saved
    }

    private fun resolveSeat(req: CreateReservationRequest, pass: MonthlyPass?, store: Store): Seat {
        val seatId = req.seatId ?: pass?.fixedSeat?.id
        ?: throw RuleRejectException("包月浮动座也请选择具体座位")
        val seat = seatRepo.findById(seatId).orElseThrow { NotFoundException("座位", seatId) }
        if (seat.store.id != store.id) throw RuleRejectException("座位不属于该门店")
        if (pass != null && pass.mode == PassMode.FIXED_SEAT && pass.fixedSeat?.id != seatId) {
            throw RuleRejectException("固定座包月只能预约绑定座位")
        }
        return seat
    }
    /** 普通预约不得占用他人有效固定座包月的日期 */
    fun checkFixedPassConflict(seat: Seat, startTime: Long, endTime: Long, user: AppUser) {
        val from = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(startTime), ZoneOffset.UTC)
        val to = LocalDate.ofInstant(java.time.Instant.ofEpochMilli((endTime - 1).coerceAtLeast(startTime)), ZoneOffset.UTC)
        var d = from
        while (!d.isAfter(to)) {
            val holders = passRepo.findActiveFixedOnSeat(seat.id!!, PassMode.FIXED_SEAT, PassStatus.ACTIVE, d)
            val other = holders.firstOrNull { it.user.id != user.id }
            if (other != null) {
                throw RuleRejectException(
                    "座位为包月固定座（${other.passNo} 持有），包月权益冲突",
                    GateReason.RESERVATION_INVALID
                )
            }
            d = d.plusDays(1)
        }
    }

    private fun computeDeposit(type: ReservationType, needLocker: Boolean): BigDecimal = when {
        type == ReservationType.PRIVATE_ROOM -> BigDecimal("50.00")
        type == ReservationType.NIGHT_SEAT -> BigDecimal("30.00")
        needLocker -> BigDecimal("20.00")
        else -> BigDecimal.ZERO
    }

    // ---------------- 支付 ----------------

    fun pay(id: Long, idempotencyKey: String?): Reservation = tx.run {
        locks.withLock(Locks.reservationKey(id)) {
            val r = getOrThrow(id)
            if (r.paid) return@withLock r
            if (idempotencyKey != null && !gatePass.firstTime("pay:$id:$idempotencyKey")) return@withLock r
            if (r.status != ReservationStatus.PENDING_PAYMENT) {
                throw RuleRejectException("订单当前状态不可支付: ${r.status}")
            }
            r.paid = true
            r.paidAt = time.now()
            r.status = ReservationStatus.BOOKED
            if (r.deposit > BigDecimal.ZERO) {
                r.depositStatus = DepositStatus.HELD
                billing.record(r, BillingType.DEPOSIT_HOLD, r.deposit, "押金暂扣")
            }
            billing.record(r, BillingType.BOOKING_FEE, r.price, "预约费用")
            issuePass(r)
            timeline.add(TimelineType.PAID, r, actorType = ActorType.USER, actorId = r.user.id.toString(),
                detail = "支付成功 费用=${r.price} 押金=${r.deposit}")
            r
        }
    }

    fun issuePass(r: Reservation) {
        val ttl = (r.scheduledEndTime - time.nowMillis()).coerceAtLeast(60_000L) + 12 * 3600_000L
        gatePass.issue(
            GatePass(
                reservationId = r.id!!,
                storeId = r.store.id!!,
                seatId = r.seat?.id,
                userId = r.user.id!!,
                status = r.status,
                startTime = r.startTime,
                scheduledEndTime = r.scheduledEndTime,
                paid = r.paid,
                idVerified = r.user.idVerified,
                version = (r.postponeCount + r.overtimeMinutes + 1)
            ),
            ttl
        )
    }

    // ---------------- 开始前取消（全额退） ----------------

    fun cancel(id: Long, reason: String?): Reservation = tx.run {
        locks.withLock(Locks.reservationKey(id)) {
            val r = getOrThrow(id)
            if (r.status !in listOf(ReservationStatus.PENDING_PAYMENT, ReservationStatus.BOOKED)) {
                throw RuleRejectException("仅待支付/已预约订单可取消，当前 ${r.status}")
            }
            val refunded = refundInternal(r, r.price + r.deposit, "取消订单全额退款：${reason ?: "用户取消"}")
            if (r.depositStatus == DepositStatus.HELD) r.depositStatus = DepositStatus.RETURNED
            r.status = ReservationStatus.CANCELLED
            gatePass.revoke(r.id!!)
            timeline.add(TimelineType.CANCELLED, r, actorType = ActorType.USER,
                actorId = r.user.id.toString(), detail = "取消并退款 $refunded")
            r
        }
    }

    // ---------------- 迟到顺延（无人值守连续事件起点） ----------------

    fun postpone(id: Long, delayMinutes: Int): Reservation = inSeatLock(seatIdOf(id)) {
        val r = getOrThrow(id)
        if (r.status != ReservationStatus.BOOKED) throw RuleRejectException("仅已预约未进场订单可顺延，当前 ${r.status}")
        if (r.checkInAt != null) throw RuleRejectException("已进场不可顺延")
        val now = time.nowMillis()
        if (now < r.startTime) throw RuleRejectException("尚未到预约开始时间，无需顺延")
        if (now > r.startTime + 60 * 60_000L) throw RuleRejectException("超过最长顺延时限（60 分钟），请重新预约")

        timeline.add(TimelineType.POSTPONE_REQUESTED, r, actorType = ActorType.USER,
            actorId = r.user.id.toString(), detail = "申请顺延 $delayMinutes 分钟")

        val delta = delayMinutes * 60_000L
        r.startTime += delta
        r.endTime += delta
        r.scheduledEndTime += delta
        r.postponeCount += 1
        r.lateMarkedAt = null

        // 标记被侵占的下一位（同座位时段重叠的其他有效订单）
        val victims = reservationRepo.findOverlapping(
            r.seat!!.id!!, r.startTime, r.scheduledEndTime,
            listOf(ReservationStatus.BOOKED, ReservationStatus.IN_PROGRESS)
        ).filter { it.id != r.id }
        victims.forEach { v ->
            v.encroachedByReservationId = r.id
            timeline.add(TimelineType.SEAT_ENCROACHED, v, actorType = ActorType.SYSTEM,
                detail = "上一位用户 ${r.orderNo} 顺延后侵占本订单座位，可换座或等候")
            timeline.add(TimelineType.ENCROACHMENT_MARKED, r, actorType = ActorType.SYSTEM,
                detail = "顺延侵占下一位订单 ${v.orderNo}")
        }

        issuePass(r)
        timeline.add(TimelineType.POSTPONED, r, actorType = ActorType.SYSTEM,
            detail = "已顺延 $delayMinutes 分钟，新结束 ${r.scheduledEndTime}" +
                    if (victims.isNotEmpty()) "；影响订单 ${victims.joinToString { it.orderNo }}" else "")
        r
    }

    // ---------------- 临时延时 ----------------

    fun extend(id: Long, minutes: Int): Reservation = inSeatLock(seatIdOf(id)) {
        val r = getOrThrow(id)
        if (r.status != ReservationStatus.IN_PROGRESS) throw RuleRejectException("仅在场订单可延时，当前 ${r.status}")
        timeline.add(TimelineType.EXTEND_REQUESTED, r, actorType = ActorType.USER,
            actorId = r.user.id.toString(), detail = "申请延时 $minutes 分钟")

        val newEnd = r.scheduledEndTime + minutes * 60_000L
        val blocked = reservationRepo.findOverlapping(
            r.seat!!.id!!, r.startTime, newEnd, listOf(ReservationStatus.BOOKED)
        ).filter { it.id != r.id }
        checkFixedPassConflict(r.seat!!, r.scheduledEndTime, newEnd, r.user)
        if (blocked.isNotEmpty()) {
            throw RuleRejectException(
                "延时将占用下一位用户座位（${blocked.first().orderNo}），请先联系客服换座",
                GateReason.SEAT_OCCUPIED
            )
        }
        r.scheduledEndTime = newEnd
        issuePass(r)
        timeline.add(TimelineType.EXTENDED, r, actorType = ActorType.SYSTEM,
            detail = "已延时至 $newEnd（离场时统一计费）")
        r
    }

    // ---------------- 换座（被顺延/设备故障影响的下一位用户） ----------------

    fun reassign(id: Long, targetSeatId: Long, reason: String?): Reservation {
        val currentSeatId = seatIdOf(id)
        return inSeatLocks(listOf(currentSeatId, targetSeatId)) {
            val r = getOrThrow(id)
            if (r.status !in listOf(ReservationStatus.BOOKED, ReservationStatus.IN_PROGRESS, ReservationStatus.DISPUTED)) {
                throw RuleRejectException("当前订单状态不可换座: ${r.status}")
            }
            val target = seatRepo.findById(targetSeatId).orElseThrow { NotFoundException("座位", targetSeatId) }
            if (target.store.id != r.store.id) throw RuleRejectException("不能跨门店换座")
            if (target.status == SeatStatus.MAINTENANCE) throw RuleRejectException("目标座位维护中")
            if (target.occupiedByReservationId != null && target.occupiedByReservationId != r.id) {
                throw ConflictException("目标座位当前有人使用")
            }
            val overlap = reservationRepo.findOverlapping(
                targetSeatId, r.startTime, r.scheduledEndTime, OCCUPYING_STATUSES
            ).filter { it.id != r.id }
            if (overlap.isNotEmpty()) throw ConflictException("目标座位时段冲突：${overlap.first().orderNo}")
            checkFixedPassConflict(target, r.startTime, r.scheduledEndTime, r.user)

            val oldSeat = r.seat
            r.movedFromSeatId = oldSeat?.id
            r.seat = target
            if (r.status == ReservationStatus.IN_PROGRESS) {
                oldSeat?.let {
                    if (it.occupiedByReservationId == r.id) {
                        it.status = SeatStatus.FREE
                        it.occupiedByReservationId = null
                        gatePass.mirrorSeatFree(it.id!!)
                    }
                }
                target.status = SeatStatus.OCCUPIED
                target.occupiedByReservationId = r.id
                gatePass.mirrorSeatOccupied(targetSeatId, r.id!!)
            }
            r.encroachedByReservationId = null
            issuePass(r)
            timeline.add(TimelineType.REASSIGNED, r, seatId = targetSeatId, actorType = ActorType.STAFF,
                detail = "由座位 ${oldSeat?.seatCode} 换至 ${target.seatCode}：${reason ?: "用户申请"}")
            r
        }
    }

    // ---------------- 离场检查 ----------------

    fun submitDepartureCheck(id: Long, req: DepartureCheckRequest): Reservation = tx.run {
        locks.withLock(Locks.reservationKey(id)) {
            val r = getOrThrow(id)
            r.equipmentCheck = req.equipmentCheck
            r.lockerCheck = req.lockerCheck
            r.damageCharge = req.damageCharge
            if (req.note != null) r.remark = (r.remark?.let { it + "；" } ?: "") + req.note
            timeline.add(TimelineType.TICKET_HANDLED, r, actorType = ActorType.STAFF,
                detail = "离场检查 设备=${req.equipmentCheck} 储物柜=${req.lockerCheck} 赔偿=${req.damageCharge}")
            r
        }
    }

    // ---------------- 离场结算 ----------------

    /**
     * 结算并释放座位（自行获取座位锁）。同一事务内完成：
     * 延时计费 → 押金结算 → 座位解锁/镜像释放 → 凭证作废。
     */
    fun checkout(
        reservationId: Long,
        at: java.time.Instant = time.now(),
        actorType: ActorType = ActorType.GATE,
        actorId: String? = null
    ): SettlementData = inSeatLock(seatIdOf(reservationId)) {
        checkoutLocked(getOrThrow(reservationId), at, actorType, actorId)
    }

    /** 调用方已持有座位锁时使用（如门禁服务）；嵌套事务自动加入外层事务 */
    fun checkoutLocked(
        r: Reservation,
        at: java.time.Instant = time.now(),
        actorType: ActorType = ActorType.GATE,
        actorId: String? = null
    ): SettlementData {
        if (r.status !in listOf(ReservationStatus.IN_PROGRESS, ReservationStatus.DISPUTED)) {
            throw RuleRejectException("仅在场订单可离场，当前 ${r.status}")
        }
        val wasDisputed = r.status == ReservationStatus.DISPUTED
        r.checkOutAt = at

        // 1) 延时计费
        val lateMs = at.toEpochMilli() - r.scheduledEndTime
        if (lateMs > 0) {
            val mins = Math.ceil(lateMs / 60_000.0).toInt()
            val perMin = r.seat?.overtimePerMinute ?: BigDecimal.valueOf(props.pricing.defaultOvertimePerMinute)
            val fee = billing.overtimeFee(mins, perMin)
            r.overtimeMinutes += mins
            r.overtimeFee = r.overtimeFee.add(fee)
            billing.record(r, BillingType.OVERTIME_FEE, fee, "延时 $mins 分钟")
            timeline.add(TimelineType.OVERTIME_DETECTED, r, actorType = actorType, actorId = actorId,
                detail = "延时离场 $mins 分钟，费用 $fee")
            timeline.add(TimelineType.BILLING_OVERTIME, r, detail = "延时费入账 $fee")
        }

        // 2) 押金结算
        var depositReturned = BigDecimal.ZERO
        var depositDeducted = BigDecimal.ZERO
        if (r.depositStatus == DepositStatus.HELD && r.deposit > BigDecimal.ZERO) {
            val deduct = minOf(r.deposit, r.damageCharge)
            depositDeducted = deduct
            depositReturned = r.deposit.subtract(deduct)
            if (deduct > BigDecimal.ZERO) {
                r.depositStatus = DepositStatus.DEDUCTED
                billing.record(r, BillingType.DEPOSIT_DEDUCT, deduct, "设备/储物柜损坏扣除")
                timeline.add(TimelineType.DEPOSIT_DEDUCTED, r, detail = "押金扣除 $deduct")
            } else {
                r.depositStatus = DepositStatus.RETURNED
            }
            if (depositReturned > BigDecimal.ZERO) {
                billing.record(r, BillingType.DEPOSIT_RETURN, depositReturned.negate(), "押金返还")
                timeline.add(TimelineType.DEPOSIT_RETURNED, r, detail = "押金返还 $depositReturned")
            }
        }

        // 3) 释放座位（DB 权威状态 + Redis 镜像）
        releaseSeat(r)

        r.status = if (wasDisputed) ReservationStatus.DISPUTED else ReservationStatus.COMPLETED
        gatePass.revoke(r.id!!)
        timeline.add(TimelineType.CHECK_OUT, r, actorType = actorType, actorId = actorId,
            detail = "离场 at=$at 设备=${r.equipmentCheck} 储物柜=${r.lockerCheck}" +
                    if (wasDisputed) "；争议处理中，保留 DISPUTED" else "")

        return SettlementData(r, depositReturned, depositDeducted, true)
    }

    /** 释放预约占用的座位（DB + Redis 镜像），结算/退费/强制完结共用 */
    fun releaseSeat(r: Reservation) {
        r.seat?.let { s ->
            if (s.occupiedByReservationId == r.id) {
                s.status = SeatStatus.FREE
                s.occupiedByReservationId = null
                gatePass.mirrorSeatFree(s.id!!)
            }
        }
    }

    // ---------------- 用户提前离场申请退费 ----------------

    fun earlyLeaveRefund(id: Long, reason: String?): SettlementData = inSeatLock(seatIdOf(id)) {
        val r = getOrThrow(id)
        if (r.status != ReservationStatus.IN_PROGRESS) {
            throw RuleRejectException("仅在场订单可申请提前离场退费，当前 ${r.status}")
        }
        timeline.add(TimelineType.REFUND_REQUESTED, r, actorType = ActorType.USER,
            actorId = r.user.id.toString(), detail = "提前离场退费申请：${reason ?: "未填写"}")

        // 当前已持有座位锁，直接走锁内结算
        val settle = checkoutLocked(r, actorType = ActorType.USER, actorId = r.user.id.toString())

        val amount = billing.earlyLeaveRefund(r, r.checkOutAt!!, props.pricing.refundUsedCapRatio)
        if (amount > BigDecimal.ZERO) {
            refundInternal(r, amount, "提前离场按未使用时长退费")
            timeline.add(TimelineType.REFUNDED, r, actorType = ActorType.SYSTEM,
                detail = "提前离场退费 $amount（押金已另行结算）")
            if (r.refundedAmount >= r.price) r.status = ReservationStatus.REFUNDED
        }
        settle
    }

    fun refundInternal(r: Reservation, amount: BigDecimal, note: String): BigDecimal {
        if (amount <= BigDecimal.ZERO) return BigDecimal.ZERO
        billing.record(r, BillingType.REFUND, amount.negate(), note)
        r.refundedAmount = r.refundedAmount.add(amount)
        return amount
    }

    fun compensateInternal(r: Reservation, amount: BigDecimal, note: String) {
        if (amount <= BigDecimal.ZERO) return
        billing.record(r, BillingType.COMPENSATION, amount.negate(), note)
        r.compensatedAmount = r.compensatedAmount.add(amount)
    }

    private fun seatIdOf(reservationId: Long): Long =
        reservationRepo.findSeatIdById(reservationId)
            ?: throw RuleRejectException("订单未绑定座位，无法进行座位级操作")

    private fun genOrderNo(): String {
        val ts = time.nowMillis()
        return "SR$ts${orderSeq.incrementAndGet().toString().padStart(4, '0')}"
    }

    data class SettlementData(
        val reservation: Reservation,
        val depositReturned: BigDecimal,
        val depositDeducted: BigDecimal,
        val seatReleased: Boolean
    )
}
