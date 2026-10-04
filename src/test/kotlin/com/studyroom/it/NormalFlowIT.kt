package com.studyroom.it

import com.studyroom.domain.AccessResult
import com.studyroom.domain.BookingStatus
import com.studyroom.domain.BookingType
import com.studyroom.domain.IncidentType
import com.studyroom.service.AccessService
import com.studyroom.service.CheckoutMode
import com.studyroom.support.SeatLockManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import java.time.Instant

/** 正常链路：支付 → 放行与订单状态一致 → 正常离场退押金并释放座位 */
class NormalFlowIT : BaseIT() {

    @Autowired lateinit var fx: Fixtures
    @Autowired lateinit var access: AccessService
    @Autowired lateinit var redis: StringRedisTemplate

    @Test
    fun `pay enter leave keeps booking seat and refund consistent`() {
        val store = fx.store1()
        val seat = fx.seat(store.id!!, "A01")
        val user = fx.verifiedUser("正常流", "13700000001")
        val t = Instant.now()

        val b = fx.paidTimedBooking(user, store, seat, t.minus(Duration.ofMinutes(5)), t.plus(Duration.ofMinutes(55)))
        val decision = access.enter(user.id!!, store.id!!)

        assertThat(decision.granted).isTrue()
        assertThat(decision.result).isEqualTo(AccessResult.GRANTED)

        val afterEnter = fx.bookings.findByBookingNo(b.bookingNo)!!
        // 不变量 1：门禁放行时订单必须已是 IN_PROGRESS，不能“已放行但未开始”
        assertThat(afterEnter.status).isEqualTo(BookingStatus.IN_PROGRESS)
        assertThat(afterEnter.actualCheckIn).isNotNull
        assertThat(redis.hasKey(SeatLockManager.stateKey(seat.id!!))).isTrue

        val exit = access.leave(user.id!!, store.id!!, mode = CheckoutMode.NORMAL)
        val afterLeave = fx.bookings.findByBookingNo(b.bookingNo)!!

        assertThat(afterLeave.status).isEqualTo(BookingStatus.COMPLETED)
        assertThat(afterLeave.actualCheckOut).isNotNull
        // 押金（单座 20）全额原路退回
        assertThat(afterLeave.depositDeducted.compareTo(java.math.BigDecimal.ZERO)).isEqualTo(0)
        assertThat(exit.settlement.depositReturned.compareTo(afterLeave.deposit)).isEqualTo(0)
        // 不变量 2：离场（退费）完成后座位不得仍锁定
        assertThat(redis.hasKey(SeatLockManager.stateKey(seat.id!!))).isFalse
    }

    @Test
    fun `unreturned locker causes deposit deduction`() {
        val store = fx.store1()
        val seat = fx.seat(store.id!!, "A02") // A02 有储物柜
        val user = fx.verifiedUser("柜子用户", "13700000002")
        val t = Instant.now()

        val b = fx.paidTimedBooking(
            user, store, seat,
            t.minus(Duration.ofMinutes(5)), t.plus(Duration.ofMinutes(55)),
            useLocker = true
        )
        access.enter(user.id!!, store.id!!)
        val exit = access.leave(user.id!!, store.id!!, lockerReturned = false, mode = CheckoutMode.NORMAL)

        val after = fx.bookings.findByBookingNo(b.bookingNo)!!
        assertThat(after.depositDeducted.compareTo(java.math.BigDecimal("30.00"))).isEqualTo(0)
        assertThat(exit.settlement.depositDeduction.compareTo(java.math.BigDecimal("30.00"))).isEqualTo(0)
        assertThat(redis.hasKey(SeatLockManager.stateKey(seat.id!!))).isFalse
    }
}
