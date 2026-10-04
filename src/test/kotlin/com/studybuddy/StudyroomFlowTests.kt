package com.studybuddy

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.studybuddy.api.CreateReservationRequest
import com.studybuddy.domain.*
import com.studybuddy.infra.TimeService
import com.studybuddy.service.ReservationService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 无人值守连续事件链与跨模块一致性集成测试。
 *
 * 覆盖：
 * - 晚到 → 顺延 → 侵占下一位 → 投诉/换座 的完整链路
 * - 门禁放行与订单 IN_PROGRESS / 座位 OCCUPIED 同事务
 * - 离场/退费完成与座位释放同事务
 * - 停电批量处置、门锁/空调故障、包月固定座冲突
 * - 调度器迟到/爽约、对账修复
 *
 * 使用固定时钟驱动“迟到/顺延/超时”等时间相关逻辑。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class StudyroomFlowTests {

    @Autowired lateinit var time: TimeService
    @Autowired lateinit var reservations: ReservationService
    @Autowired lateinit var gate: com.studybuddy.service.GateService
    @Autowired lateinit var devices: com.studybuddy.service.DeviceService
    @Autowired lateinit var support: com.studybuddy.service.SupportService
    @Autowired lateinit var ops: com.studybuddy.service.OpsService
    @Autowired lateinit var housekeeping: com.studybuddy.service.HousekeepingService
    @Autowired lateinit var storeRepo: StoreRepository
    @Autowired lateinit var seatRepo: SeatRepository
    @Autowired lateinit var userRepo: AppUserRepository
    @Autowired lateinit var reservationRepo: ReservationRepository
    @Autowired lateinit var gateLogRepo: GateLogRepository
    @Autowired lateinit var passRepo: MonthlyPassRepository

    private val base: Instant = Instant.parse("2026-10-03T10:00:00Z")
    private val MIN = 60_000L

    private lateinit var store: Store
    private lateinit var seatA: Seat
    private lateinit var seatB: Seat
    private lateinit var u1: AppUser
    private lateinit var u2: AppUser

    @BeforeEach
    fun setup() {
        time.setFixed(base)
        store = storeRepo.save(Store(name = "无人值守测试店", unattended = true))
        seatA = seatRepo.save(Seat(store, "A-1", SeatType.SINGLE_SEAT, "静音区",
            hourlyPrice = BigDecimal("12.00"), overtimePerMinute = BigDecimal("0.50")))
        seatB = seatRepo.save(Seat(store, "A-2", SeatType.SINGLE_SEAT, "静音区",
            hourlyPrice = BigDecimal("12.00"), overtimePerMinute = BigDecimal("0.50"), hasLocker = true))
        u1 = userRepo.save(AppUser(name = "u1", phone = nextPhone(), idVerified = true))
        u2 = userRepo.save(AppUser(name = "u2", phone = nextPhone(), idVerified = true))
    }

    private fun t(min: Long) = base.plus(Duration.ofMinutes(min)).toEpochMilli()

    companion object {
        private var phoneCounter = 100
        @JvmStatic
        fun nextPhone() = "139%08d".format(phoneCounter++)
    }

    private fun book(user: AppUser, seat: Seat, fromMin: Long, toMin: Long,
                     type: ReservationType = ReservationType.SINGLE_SEAT,
                     locker: Boolean = false): Reservation {
        var r = reservations.create(
            CreateReservationRequest(
                userId = user.id!!, storeId = store.id!!, seatId = seat.id,
                reservationType = type, startTime = t(fromMin), endTime = t(toMin),
                needLocker = locker, silentRuleAccepted = true
            )
        )
        r = reservations.pay(r.id!!, null)
        return r
    }

    // ---------------- 核心连续事件 ----------------

    @Test
    fun `晚到顺延侵占下一位后投诉换座全链路一致`() {
        // u1 的 15 分钟短单：-10 开始（迟到 10 分钟）
        val r1 = book(u1, seatA, -10, 5)
        // u2 下一位 +10 开始
        val r2 = book(u2, seatA, 10, 70)

        // u1 迟到 10 分钟仍可进场（15 分钟宽限）
        val in1 = gate.attempt(com.studybuddy.api.GateAttemptRequest(
            reservationId = r1.id, direction = GateDirection.IN, deviceSn = "G1"))
        assertEquals(GateResult.ALLOW, in1.result)
        assertEquals(ReservationStatus.IN_PROGRESS, reservations.getOrThrow(r1.id!!).status)
        assertEquals(r1.id, seatRepo.findById(seatA.id!!).get().occupiedByReservationId)

        // u2 提前 10 分钟到场：座位有人，拒绝（物理占用）
        val early2 = gate.attempt(com.studybuddy.api.GateAttemptRequest(
            reservationId = r2.id, direction = GateDirection.IN))
        assertEquals(GateResult.DENY, early2.result)
        assertEquals(GateReason.SEAT_OCCUPIED, early2.reason)

        // 换座到 seatB 后放行
        reservations.reassign(r2.id!!, seatB.id!!, "上一位迟到顺延，安排换座")
        val in2 = gate.attempt(com.studybuddy.api.GateAttemptRequest(
            reservationId = r2.id, direction = GateDirection.IN))
        assertEquals(GateResult.ALLOW, in2.result)
        assertEquals(seatB.id, reservations.getOrThrow(r2.id!!).seat!!.id)
        assertEquals(r2.id, seatRepo.findById(seatB.id!!).get().occupiedByReservationId)

        // u2 投诉 + 工单（根因用户迟到）
        val complaint = support.fileComplaint(store.id!!,
            com.studybuddy.api.ComplaintRequest(
                reservationId = r2.id, userId = u2.id, reason = ComplaintReason.SEAT_OCCUPIED,
                cctvEventId = early2.cctvEventId))
        val ticket = support.openTicket(com.studybuddy.api.CreateTicketRequest(
            storeId = store.id!!, reservationId = r2.id, userId = u2.id,
            category = TicketCategory.SEAT_CONFLICT, rootCause = RootCause.USER_LATE,
            complaintId = complaint.id))
        val evidence = support.evidence(ticket.id!!)
        assertTrue(evidence.gateLogs.any { it.result == GateResult.DENY && it.reason == GateReason.SEAT_OCCUPIED })
        assertTrue(evidence.complaints.any { it.cctvEventId == early2.cctvEventId })
        assertTrue(evidence.timeline.any { it.type == TimelineType.REASSIGNED })
    }

    @Test
    fun `未进场用户顺延自动给下一位打侵占标记并阻止其进场`() {
        // u1 迟到但还没进场；申请顺延 20 分钟
        val r1 = book(u1, seatA, -10, 5)
        val r2 = book(u2, seatA, 10, 70)

        time.setFixed(base.plus(Duration.ofMinutes(10)))
        val p = reservations.postpone(r1.id!!, 20)
        assertEquals(1, p.postponeCount)
        assertEquals(r1.id, reservationRepo.findById(r2.id!!).get().encroachedByReservationId)

        // 顺延结束时间 25 > 下一位开始 10：下一位即便座位物理空闲也被侵占标记拦截
        val g = gate.attempt(com.studybuddy.api.GateAttemptRequest(reservationId = r2.id, direction = GateDirection.IN))
        assertEquals(GateResult.DENY, g.result)
        assertEquals(GateReason.SEAT_OCCUPIED, g.reason)

        // u1 顺延单自身仍可进场
        val g1 = gate.attempt(com.studybuddy.api.GateAttemptRequest(reservationId = r1.id, direction = GateDirection.IN))
        assertEquals(GateResult.ALLOW, g1.result)
    }

    // ---------------- 门禁前置条件 ----------------

    @Test
    fun `未支付和未实名门禁拒绝`() {
        val unpaid = reservations.create(
            CreateReservationRequest(userId = u1.id!!, storeId = store.id!!, seatId = seatA.id,
                reservationType = ReservationType.SINGLE_SEAT, startTime = t(0), endTime = t(60)))
        val g = gate.attempt(com.studybuddy.api.GateAttemptRequest(reservationId = unpaid.id, direction = GateDirection.IN))
        assertEquals(GateReason.NOT_PAID, g.reason)

        val unverified = userRepo.save(AppUser(name = "ux", phone = nextPhone(), idVerified = false))
        val r = reservations.create(
            CreateReservationRequest(userId = unverified.id!!, storeId = store.id!!, seatId = seatB.id,
                reservationType = ReservationType.SINGLE_SEAT, startTime = t(0), endTime = t(60)))
        reservations.pay(r.id!!, null)
        val g2 = gate.attempt(com.studybuddy.api.GateAttemptRequest(reservationId = r.id, direction = GateDirection.IN))
        assertEquals(GateReason.REALNAME_MISSING, g2.reason)
    }

    @Test
    fun `不在时间窗内拒绝进场`() {
        val r = book(u1, seatA, 60, 120) // 1 小时后才开始
        val early = gate.attempt(com.studybuddy.api.GateAttemptRequest(reservationId = r.id, direction = GateDirection.IN))
        assertEquals(GateReason.OUTSIDE_WINDOW, early.reason)

        time.setFixed(base.plus(Duration.ofMinutes(80))) // 超过开始后 15 分钟宽限
        val late = gate.attempt(com.studybuddy.api.GateAttemptRequest(reservationId = r.id, direction = GateDirection.IN))
        assertEquals(GateReason.OUTSIDE_WINDOW, late.reason)
    }

    // ---------------- 延时与退费 ----------------

    @Test
    fun `延时不得侵占下一位并在离场按分钟计费`() {
        val r1 = book(u1, seatA, 0, 60)
        val r2 = book(u2, seatA, 75, 135) // 下一位 75 分开始
        gate.attempt(com.studybuddy.api.GateAttemptRequest(reservationId = r1.id, direction = GateDirection.IN))

        // 延时 20 分钟 -> 结束 80 > 75，拒绝
        val blocked = runCatching { reservations.extend(r1.id!!, 20) }.exceptionOrNull()
        assertNotNull(blocked)
        assertTrue(blocked is com.studybuddy.service.RuleRejectException)

        // 延时 10 分钟 -> 结束 70 < 75，允许
        reservations.extend(r1.id!!, 10)
        assertEquals(t(70), reservations.getOrThrow(r1.id!!).scheduledEndTime)

        // 第 72 分钟离场：超时 2 分钟，按 0.5/分钟收费
        time.setFixed(base.plus(Duration.ofMinutes(72)))
        val settle = reservations.checkout(r1.id!!)
        assertEquals(2, settle.reservation.overtimeMinutes)
        assertEquals(0, BigDecimal("1.00").compareTo(settle.reservation.overtimeFee))
        assertNull(seatRepo.findById(seatA.id!!).get().occupiedByReservationId)
    }

    @Test
    fun `提前离场退费完成后座位必须释放`() {
        val r = book(u1, seatA, 0, 120) // 2 小时
        gate.attempt(com.studybuddy.api.GateAttemptRequest(reservationId = r.id, direction = GateDirection.IN))
        time.setFixed(base.plus(Duration.ofMinutes(30))) // 用了 30 分钟（<=50%）
        val settle = reservations.earlyLeaveRefund(r.id!!, "有事提前走")
        assertTrue(settle.seatReleased)
        assertTrue(settle.reservation.refundedAmount > BigDecimal.ZERO)
        assertEquals(SeatStatus.FREE, seatRepo.findById(seatA.id!!).get().status)
    }

    @Test
    fun `押金在设备损坏时扣除其余返还`() {
        val r = book(u1, seatB, 0, 120, type = ReservationType.PRIVATE_ROOM, locker = true)
        assertEquals(BigDecimal("50.00"), r.deposit)
        gate.attempt(com.studybuddy.api.GateAttemptRequest(reservationId = r.id, direction = GateDirection.IN))
        reservations.submitDepartureCheck(r.id!!, com.studybuddy.api.DepartureCheckRequest(
            equipmentCheck = EquipmentCheck.DAMAGED,
            lockerCheck = LockerCheck.CLEAN,
            damageCharge = BigDecimal("30.00")))
        val settle = reservations.checkout(r.id!!)
        assertEquals(0, BigDecimal("30.00").compareTo(settle.depositDeducted))
        assertEquals(0, BigDecimal("20.00").compareTo(settle.depositReturned))
        assertEquals(DepositStatus.DEDUCTED, settle.reservation.depositStatus)
    }

    // ---------------- 停电与设备 ----------------

    @Test
    fun `停电期间拒绝进场允许离场恢复后开放`() {
        val r1 = book(u1, seatA, 0, 120)
        gate.attempt(com.studybuddy.api.GateAttemptRequest(reservationId = r1.id, direction = GateDirection.IN))

        val ev = devices.report(store.id!!, com.studybuddy.api.DeviceEventRequest(
            deviceType = DeviceType.POWER, responsible = ResponsibleParty.STORE, note = "停电"))
        assertTrue(ev.affectedReservationIds.contains(r1.id))

        val r2 = book(u2, seatB, 0, 120)
        val deny = gate.attempt(com.studybuddy.api.GateAttemptRequest(reservationId = r2.id, direction = GateDirection.IN))
        assertEquals(GateReason.STORE_OUTAGE, deny.reason)

        // 在场用户可离场
        val out = gate.attempt(com.studybuddy.api.GateAttemptRequest(reservationId = r1.id, direction = GateDirection.OUT))
        assertEquals(GateResult.ALLOW, out.result)

        devices.resolve(store.id!!, ev.id!!, "恢复")
        val in2 = gate.attempt(com.studybuddy.api.GateAttemptRequest(reservationId = r2.id, direction = GateDirection.IN))
        assertEquals(GateResult.ALLOW, in2.result)
    }

    @Test
    fun `门锁故障门禁拒绝并留下监控事件编号`() {
        val r = book(u1, seatA, 0, 60)
        devices.report(store.id!!, com.studybuddy.api.DeviceEventRequest(
            deviceType = DeviceType.DOOR_LOCK, responsible = ResponsibleParty.VENDOR))
        val g = gate.attempt(com.studybuddy.api.GateAttemptRequest(reservationId = r.id, direction = GateDirection.IN))
        assertEquals(GateReason.GATE_DEVICE_FAULT, g.reason)
        assertNotNull(gateLogRepo.findById(g.gateLogId).get().cctvEventId)
    }

    // ---------------- 包月 ----------------

    @Test
    fun `固定座包月与普通预约互相冲突`() {
        val passUser = userRepo.save(AppUser(name = "pass", phone = nextPhone(), idVerified = true))
        val other = userRepo.save(AppUser(name = "other", phone = nextPhone(), idVerified = true))
        val pass = MonthlyPass(
            passNo = "MP-T-1", user = passUser, storeId = store.id!!, fixedSeat = seatA,
            mode = PassMode.FIXED_SEAT,
            validFrom = java.time.LocalDate.ofInstant(base, java.time.ZoneOffset.UTC),
            validTo = java.time.LocalDate.ofInstant(base, java.time.ZoneOffset.UTC).plusMonths(1),
            status = PassStatus.ACTIVE, price = BigDecimal("599"))
        // 直接通过仓储保存（此处不测开通接口）
        passRepo.save(pass)

        // 他人预约 seatA 被拒
        val ex = runCatching {
            reservations.create(CreateReservationRequest(
                userId = other.id!!, storeId = store.id!!, seatId = seatA.id,
                reservationType = ReservationType.SINGLE_SEAT, startTime = t(0), endTime = t(60)))
        }.exceptionOrNull()
        assertTrue(ex is com.studybuddy.service.RuleRejectException)

        // 持有人包月单 0 元直接 BOOKED
        val own = reservations.create(CreateReservationRequest(
            userId = passUser.id!!, storeId = store.id!!, seatId = seatA.id,
            reservationType = ReservationType.MONTHLY, startTime = t(0), endTime = t(60), passId = pass.id))
        assertEquals(ReservationStatus.BOOKED, own.status)
        assertEquals(0, BigDecimal.ZERO.compareTo(own.price))
    }

    // ---------------- 调度器：迟到/爽约 ----------------

    @Test
    fun `调度器标记迟到并在超时未处理时判爽约释放`() {
        // 注意：该用例直接 new 调度器不便；用 Spring 注入的 scheduler（@Conditional 已禁用自动调度，但 Bean 仍在）
        val r = book(u1, seatA, 0, 60)

        time.setFixed(base.plus(Duration.ofMinutes(16)))
        housekeeping.scanLateAndNoShow()
        assertNotNull(reservationRepo.findById(r.id!!).get().lateMarkedAt)

        time.setFixed(base.plus(Duration.ofMinutes(31)))
        housekeeping.scanLateAndNoShow()
        assertEquals(ReservationStatus.NO_SHOW, reservationRepo.findById(r.id!!).get().status)
    }

    // ---------------- 对账修复 ----------------

    @Test
    fun `对账修复已放行但订单未开始的漂移`() {
        val r = book(u1, seatA, 0, 60)
        // 模拟异常漂移：有放行 IN 日志，但订单仍是 BOOKED 且座位 FREE
        gateLogRepo.save(GateLog(
            reservation = r, storeId = store.id!!, seatId = seatA.id, userId = u1.id,
            direction = GateDirection.IN, result = GateResult.ALLOW, reason = GateReason.OK,
            occurredAt = base, deviceSn = "G1", cctvEventId = "CCTV-FIX-1"))
        assertEquals(ReservationStatus.BOOKED, reservationRepo.findById(r.id!!).get().status)

        val report = ops.reconcile()
        assertTrue(report.inconsistent)
        assertEquals(ReservationStatus.IN_PROGRESS, reservationRepo.findById(r.id!!).get().status)
        assertEquals(SeatStatus.OCCUPIED, seatRepo.findById(seatA.id!!).get().status)
    }

    @Test
    fun `对账修复已完结订单但座位仍锁定`() {
        val r = book(u1, seatA, 0, 60)
        gate.attempt(com.studybuddy.api.GateAttemptRequest(reservationId = r.id, direction = GateDirection.IN))
        reservations.checkout(r.id!!)
        // 人为制造漂移
        val s = seatRepo.findById(seatA.id!!).get()
        s.status = SeatStatus.OCCUPIED
        s.occupiedByReservationId = r.id
        seatRepo.save(s)

        ops.reconcile()
        val fixed = seatRepo.findById(seatA.id!!).get()
        assertEquals(SeatStatus.FREE, fixed.status)
        assertNull(fixed.occupiedByReservationId)
    }
}
