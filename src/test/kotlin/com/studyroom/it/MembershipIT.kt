package com.studyroom.it

import com.studyroom.domain.AccessResult
import com.studyroom.domain.BookingStatus
import com.studyroom.domain.BookingType
import com.studyroom.domain.SeatType
import com.studyroom.service.AccessService
import com.studyroom.service.CheckoutMode
import com.studyroom.support.ConflictException
import com.studyroom.support.SeatLockManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Instant

/** 包月权益：固定座进出后权益保留但座位释放；重叠权益拒绝；浮动权益现场分座 */
class MembershipIT : BaseIT() {

    @Autowired lateinit var fx: Fixtures
    @Autowired lateinit var access: AccessService
    @Autowired lateinit var redis: StringRedisTemplate

    @Test
    fun `fixed monthly member can enter and leave while membership stays confirmed and seat releases`() {
        val store = fx.store1()
        val seat = fx.seat(store.id!!, "A03")
        val plan = fx.plans.findByStoreIdAndActiveTrue(store.id!!).first { it.fixedSeat }
        val user = fx.verifiedUser("包月用户", "13730000001")

        val membership = fx.paidMonthly(user, store, plan, seat, Instant.now().minusSeconds(3600))
        val enter = access.enter(user.id!!, store.id!!)
        assertThat(enter.granted).isTrue()

        val exit = access.leave(user.id!!, store.id!!, mode = CheckoutMode.NORMAL)
        assertThat(exit.status).isEqualTo(BookingStatus.CONFIRMED) // 权益仍在有效期
        assertThat(redis.hasKey(SeatLockManager.stateKey(seat.id!!))).isFalse
        // 日常离场押金保留，不产生退款
        assertThat(exit.settlement.depositReturned.compareTo(java.math.BigDecimal.ZERO)).isEqualTo(0)
        assertThat(fx.bookings.findByBookingNo(membership.bookingNo)!!.refundedAmount.compareTo(java.math.BigDecimal.ZERO)).isEqualTo(0)

        // 当天可再次进入
        val second = access.enter(user.id!!, store.id!!)
        assertThat(second.granted).isTrue()
        access.leave(user.id!!, store.id!!)
        assertThat(fx.bookings.findByBookingNo(membership.bookingNo)!!.status).isEqualTo(BookingStatus.CONFIRMED)
    }

    @Test
    fun `sweep closes expired membership and refunds deposit without locking seat`() {
        val store = fx.store1()
        val seat = fx.seat(store.id!!, "A03")
        val plan = fx.plans.findByStoreIdAndActiveTrue(store.id!!).first { it.fixedSeat }
        val user = fx.verifiedUser("过期包月", "13730000004")

        // 权益在 31 天前开始，当前早已到期（套餐 30 天）
        val membership = fx.paidMonthly(user, store, plan, seat, Instant.now().minusSeconds(86400L * 31))
        val result = fx.bookingService.runSweep()
        assertThat(result.closedMemberships).contains(membership.bookingNo)

        val after = fx.bookings.findByBookingNo(membership.bookingNo)!!
        assertThat(after.status).isEqualTo(BookingStatus.COMPLETED)
        assertThat(after.refundedAmount.compareTo(after.deposit)).isEqualTo(0)
        assertThat(redis.hasKey(SeatLockManager.stateKey(seat.id!!))).isFalse
    }

    @Test
    fun `overlapping monthly membership is rejected`() {
        val store = fx.store1()
        val seat1 = fx.seat(store.id!!, "A01")
        val seat2 = fx.seat(store.id!!, "A03")
        val plan = fx.plans.findByStoreIdAndActiveTrue(store.id!!).first { it.fixedSeat }
        val user = fx.verifiedUser("重复包月", "13730000002")

        fx.paidMonthly(user, store, plan, seat1)
        val ex = assertThrows<ConflictException> {
            fx.paidMonthly(user, store, plan, seat2, Instant.now().plusSeconds(86400 * 10))
        }
        assertThat(ex.code).isEqualTo("MEMBERSHIP_CONFLICT")
    }

    @Test
    fun `floating monthly membership assigns a free seat at entry`() {
        val store = fx.store1()
        val plan = fx.plans.findByStoreIdAndActiveTrue(store.id!!)
            .first { !it.fixedSeat && it.seatType == SeatType.SINGLE }
        val user = fx.verifiedUser("浮动包月", "13730000003")

        fx.paidMonthly(user, store, plan)
        val enter = access.enter(user.id!!, store.id!!)
        assertThat(enter.granted).isTrue()
        assertThat(enter.seatCode).isNotBlank()
        access.leave(user.id!!, store.id!!)
    }
}
