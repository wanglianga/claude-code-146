package com.studyroom.it

import com.studyroom.domain.*
import com.studyroom.repo.TimelineEventRepository
import com.studyroom.service.AccessService
import com.studyroom.service.ComplaintService
import com.studyroom.support.SeatLockManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import java.time.Instant

/**
 * 无人值守门店最常见连续事件：
 * 用户 A 晚到 → 申请顺延 → 顺延占用下一位用户 B 的座位 → B 进场自动换座 → B 触发投诉。
 * 验证预约、门禁、座位状态、投诉工单在同一时间轴上一致。
 */
class CollisionChainIT : BaseIT() {

    @Autowired lateinit var fx: Fixtures
    @Autowired lateinit var access: AccessService
    @Autowired lateinit var complaints: ComplaintService
    @Autowired lateinit var timelineRepo: TimelineEventRepository
    @Autowired lateinit var redis: StringRedisTemplate

    @Test
    fun `late postpone collides next booking - next user reassigned and complaint opens ticket`() {
        val store = fx.store1()
        val storeId = store.id!!
        val seatA01 = fx.seat(storeId, "A01")
        val seatAlt = fx.seat(storeId, "A02")
        val alice = fx.verifiedUser("晚到用户A", "13710000001")
        val bob = fx.verifiedUser("下一位B", "13710000002")

        val t = java.time.Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        val (winA, winB) = fx.windows(t)

        val bookingA = fx.paidTimedBooking(alice, store, seatA01, winA.first, winA.second)
        val bookingB = fx.paidTimedBooking(bob, store, seatA01, winB.first, winB.second)

        // 1) A 迟到 10 分钟（宽限 15 内），尚未进场，申请顺延 40 分钟 → 结束 t+100
        clock.setOverride(t.plus(Duration.ofMinutes(10)))
        val postponed = fx.bookingService.postpone(bookingA.bookingNo, 40)
        assertThat(postponed.extendedEndTime).isEqualTo(winA.second.plus(Duration.ofMinutes(40)))
        assertThat(postponed.extraChargedAmount).isGreaterThan(java.math.BigDecimal.ZERO)
        // 碰撞预警必须写入时间轴（挂在 B 单上）
        val warning = timelineRepo.findByBookingNoOrderByOccurredAtAsc(bookingB.bookingNo)
            .firstOrNull { it.eventType == "COLLISION_PREDICTED" }
        assertThat(warning).isNotNull

        // 2) A 在顺延窗口内进场
        val enterA = access.enter(alice.id!!, storeId)
        assertThat(enterA.granted).isTrue()
        assertThat(enterA.result).isEqualTo(AccessResult.GRANTED)
        val aAfter = fx.bookings.findByBookingNo(bookingA.bookingNo)!!
        assertThat(aAfter.status).isEqualTo(BookingStatus.IN_PROGRESS)
        assertThat(aAfter.late).isTrue()
        assertThat(aAfter.lateMinutes).isEqualTo(10)

        // 3) B 在自己的开始时间进场：A01 被顺延的 A 占着 → 自动换到 A03
        clock.setOverride(winB.first)
        val enterB = access.enter(bob.id!!, storeId)
        assertThat(enterB.granted).isTrue()
        assertThat(enterB.result).isEqualTo(AccessResult.GRANTED_REASSIGNED)
        assertThat(enterB.seatCode).isEqualTo("A02")

        val bAfter = fx.bookings.findByBookingNo(bookingB.bookingNo)!!
        assertThat(bAfter.status).isEqualTo(BookingStatus.IN_PROGRESS)
        assertThat(bAfter.seat!!.id!!).isEqualTo(seatAlt.id!!)
        assertThat(bAfter.originalSeatId).isEqualTo(seatA01.id!!)
        // 两张单同时在场，各占各座
        assertThat(redis.opsForValue().get(SeatLockManager.stateKey(seatA01.id!!))).isEqualTo(bookingA.bookingNo)
        assertThat(redis.opsForValue().get(SeatLockManager.stateKey(seatAlt.id!!))).isEqualTo(bookingB.bookingNo)

        // 4) B 投诉“座位被占”，指明 A 的订单 → 自动归因为 USER_LATE 并开工单
        val complaint = complaints.file(
            bookingNo = bookingB.bookingNo,
            category = ComplaintCategory.SEAT_OCCUPIED,
            description = "按预约到店发现 A01 有人，被换到 A03",
            againstBookingNo = bookingA.bookingNo
        )
        assertThat(complaint.status).isEqualTo(ComplaintStatus.OPEN)
        assertThat(complaint.relatedTicketNo).isNotBlank

        // 5) 两边正常离场：退费完成后两个座位都必须释放
        access.leave(alice.id!!, storeId)
        access.leave(bob.id!!, storeId)
        assertThat(redis.hasKey(SeatLockManager.stateKey(seatA01.id!!))).isFalse
        assertThat(redis.hasKey(SeatLockManager.stateKey(seatAlt.id!!))).isFalse
    }

    @Test
    fun `enter beyond late grace is denied and no-show scan refunds deposit`() {
        val store = fx.store1()
        val seat = fx.seat(store.id!!, "A01")
        val user = fx.verifiedUser("严重迟到", "13710000003")
        val t = java.time.Instant.now().plusSeconds(7200).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)

        val b = fx.paidTimedBooking(user, store, seat, t, t.plus(Duration.ofMinutes(60)))
        clock.setOverride(t.plus(Duration.ofMinutes(16))) // 宽限 15 之外
        val denied = access.enter(user.id!!, store.id!!)
        assertThat(denied.granted).isFalse()
        assertThat(denied.result).isEqualTo(AccessResult.DENIED_LATE_EXPIRED)
        // 被拒绝时订单绝不能变成 IN_PROGRESS
        assertThat(fx.bookings.findByBookingNo(b.bookingNo)!!.status).isEqualTo(BookingStatus.CONFIRMED)

        // 爽约扫描：订单置 NO_SHOW，押金退回，座位不被锁
        val noShows = fx.bookingService.markNoShows()
        assertThat(noShows).contains(b.bookingNo)
        val after = fx.bookings.findByBookingNo(b.bookingNo)!!
        assertThat(after.status).isEqualTo(BookingStatus.NO_SHOW)
        assertThat(after.refundedAmount).isGreaterThanOrEqualTo(after.deposit)
        assertThat(redis.hasKey(SeatLockManager.stateKey(seat.id!!))).isFalse
    }
}
