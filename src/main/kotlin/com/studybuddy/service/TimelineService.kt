package com.studybuddy.service

import com.studybuddy.domain.*
import com.studybuddy.infra.TimeService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 统一时间轴写入：预约、门禁、座位、投诉、设备、计费、退款、门店责任
 * 的每一次状态变化都追加一条不可变事件，客服可按单/门店/工单回放完整经过。
 */
@Service
class TimelineService(
    private val repo: TimelineEventRepository,
    private val time: TimeService
) {
    @Transactional
    fun add(
        type: TimelineType,
        reservation: Reservation? = null,
        storeId: Long? = reservation?.store?.id,
        seatId: Long? = reservation?.seat?.id,
        ticketId: Long? = null,
        actorType: ActorType? = ActorType.SYSTEM,
        actorId: String? = null,
        detail: String? = null,
        at: java.time.Instant? = null
    ): TimelineEvent {
        val ev = TimelineEvent(
            type = type,
            at = at ?: time.now(),
            reservation = reservation,
            storeId = storeId,
            seatId = seatId,
            ticketId = ticketId,
            actorType = actorType,
            actorId = actorId,
            detail = detail
        )
        return repo.save(ev)
    }

    fun findRecentOvertime(reservationId: Long): TimelineEvent? =
        repo.findFirstByReservationIdAndTypeAndActorTypeOrderByAtDesc(
            reservationId, TimelineType.OVERTIME_DETECTED, ActorType.SCHEDULER
        )
}
