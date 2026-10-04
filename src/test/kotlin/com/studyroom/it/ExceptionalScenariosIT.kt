package com.studyroom.it

import com.studyroom.domain.*
import com.studyroom.service.AccessService
import com.studyroom.service.CheckoutMode
import com.studyroom.service.IncidentService
import com.studyroom.support.SeatLockManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import java.time.Instant

/** 门禁故障、门店停电清场、提前离场退费三类异常场景 */
class ExceptionalScenariosIT : BaseIT() {

    @Autowired lateinit var fx: Fixtures
    @Autowired lateinit var access: AccessService
    @Autowired lateinit var incidents: IncidentService
    @Autowired lateinit var redis: StringRedisTemplate

    @Test
    fun `open door failure denies entry with incident reference`() {
        val store = fx.store1()
        val user = fx.verifiedUser("门禁故障用户", "13720000001")
        val inc = incidents.open(store.id!!, IncidentType.DOOR_FAILURE, "正门闸机无法开启")

        val decision = access.enter(user.id!!, store.id!!)
        assertThat(decision.granted).isFalse()
        assertThat(decision.result).isEqualTo(AccessResult.DENIED_DOOR_FAULT)
        assertThat(decision.detail).contains(inc.incidentNo)

        incidents.resolve(inc.incidentNo, "更换闸机主板")
        val after = access.enter(user.id!!, store.id!!)
        assertThat(after.result).isEqualTo(AccessResult.DENIED_NO_BOOKING) // 故障恢复，但该用户无预约
    }

    @Test
    fun `store blackout force-checkout refunds remaining fee and deposit and releases seat`() {
        val store = fx.store1()
        val seat = fx.seat(store.id!!, "A01")
        val user = fx.verifiedUser("停电用户", "13720000002")
        val t = Instant.now()
        val b = fx.paidTimedBooking(user, store, seat, t.minus(Duration.ofMinutes(10)), t.plus(Duration.ofMinutes(50)))

        access.enter(user.id!!, store.id!!)
        val affected = incidents.setBlackout(store.id!!, true, "片区检修停电")

        assertThat(affected).contains(b.bookingNo)
        val after = fx.bookings.findByBookingNo(b.bookingNo)!!
        assertThat(after.status).isEqualTo(BookingStatus.COMPLETED)
        assertThat(after.actualCheckOut).isNotNull
        assertThat(after.relatedIncidentNo).isNotBlank
        // 门店责任：剩余使用费全退 + 押金全退
        val refunds = fx.bookings.findByBookingNo(b.bookingNo)!!.refundedAmount
        assertThat(refunds.compareTo(b.price.add(b.deposit))).isEqualTo(0)
        assertThat(redis.hasKey(SeatLockManager.stateKey(seat.id!!))).isFalse

        // 停电期间新进入一律拒绝
        val other = fx.verifiedUser("停电想进", "13720000003")
        val denied = access.enter(other.id!!, store.id!!)
        assertThat(denied.result).isEqualTo(AccessResult.DENIED_STORE_BLACKOUT)

        incidents.setBlackout(store.id!!, false, "电力恢复")
    }

    @Test
    fun `early leave refunds part of fee and never leaves seat locked`() {
        val store = fx.store1()
        val seat = fx.seat(store.id!!, "A01")
        val user = fx.verifiedUser("提前走人", "13720000004")
        val t = Instant.now()
        val b = fx.paidTimedBooking(user, store, seat, t, t.plus(Duration.ofMinutes(60)))

        access.enter(user.id!!, store.id!!)
        val exit = access.leave(user.id!!, store.id!!, mode = CheckoutMode.EARLY_LEAVE)

        val after = fx.bookings.findByBookingNo(b.bookingNo)!!
        assertThat(after.status).isEqualTo(BookingStatus.COMPLETED)
        assertThat(exit.settlement.earlyRefund.compareTo(java.math.BigDecimal.ZERO) > 0).isTrue()
        assertThat(after.refundedAmount).isGreaterThanOrEqualTo(after.deposit) // 押金也已退回
        assertThat(redis.hasKey(SeatLockManager.stateKey(seat.id!!))).isFalse
    }
}
