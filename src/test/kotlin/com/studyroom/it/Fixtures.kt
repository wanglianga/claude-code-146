package com.studyroom.it

import com.studyroom.domain.*
import com.studyroom.repo.*
import com.studyroom.service.CreateBookingCommand
import com.studyroom.service.BookingService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/** 测试数据装配助手 */
@Component
class Fixtures @Autowired constructor(
    val users: AppUserRepository,
    val stores: StoreRepository,
    val seats: SeatRepository,
    val plans: MembershipPlanRepository,
    val bookings: BookingRepository,
    val bookingService: BookingService
) {
    fun store1(): Store = stores.findAll().first { it.name.contains("无人值守") }
    fun store2(): Store = stores.findAll().first { it.name.contains("有人") }

    fun seat(storeId: Long, code: String): Seat =
        seats.findByStoreIdAndActiveTrue(storeId).first { it.code == code }

    fun verifiedUser(name: String, phone: String): AppUser =
        users.save(AppUser(phone = phone, name = name, realNameVerified = true, idCardMasked = "ID****$phone"))

    fun unverifiedUser(name: String, phone: String): AppUser =
        users.save(AppUser(phone = phone, name = name, realNameVerified = false))

    /** 创建并支付一个计时类预约（时间截到秒，规避 H2 时间戳四舍五入导致的相邻窗口误重叠） */
    fun paidTimedBooking(
        user: AppUser, store: Store, seat: Seat,
        start: Instant, end: Instant,
        type: BookingType = BookingType.SINGLE,
        useLocker: Boolean = false
    ): Booking {
        val b = bookingService.create(
            CreateBookingCommand(
                userId = user.id!!, storeId = store.id!!, seatId = seat.id!!,
                type = type,
                startTime = start.truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
                endTime = end.truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
                useLocker = useLocker, silenceRuleAcknowledged = true
            )
        )
        return bookingService.pay(b.bookingNo)
    }

    fun paidMonthly(user: AppUser, store: Store, plan: MembershipPlan, seat: Seat? = null, start: Instant = Instant.now()): Booking {
        val b = bookingService.create(
            CreateBookingCommand(
                userId = user.id!!, storeId = store.id!!, seatId = seat?.id, planId = plan.id!!,
                type = BookingType.MONTHLY,
                startTime = start.truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
                useLocker = false, silenceRuleAcknowledged = true
            )
        )
        return bookingService.pay(b.bookingNo)
    }

    /** 两个连续整点时段，便于构造碰撞 */
    fun windows(base: Instant): Pair<Pair<Instant, Instant>, Pair<Instant, Instant>> {
        val aStart = base
        val aEnd = base.plus(Duration.ofMinutes(60))
        val bStart = aEnd
        val bEnd = bStart.plus(Duration.ofMinutes(60))
        return (aStart to aEnd) to (bStart to bEnd)
    }
}
