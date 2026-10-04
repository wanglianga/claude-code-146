package com.studyroom.service

import com.studyroom.domain.*
import com.studyroom.repo.*
import com.studyroom.support.NotFoundException
import com.studyroom.support.TimeProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ComplaintService(
    private val complaintRepo: ComplaintRepository,
    private val ticketRepo: SupportTicketRepository,
    private val bookingRepo: BookingRepository,
    private val userRepo: AppUserRepository,
    private val storeRepo: StoreRepository,
    private val incidentRepo: IncidentRepository,
    private val timeline: TimelineService,
    private val clock: TimeProvider
) {
    /**
     * 提交投诉。系统根据投诉类型与上下文自动归因（设备故障 / 用户迟到 / 权益滥用 / 用户行为），
     * 并同步生成客服工单，保证“投诉—工单”一一对应。
     *
     * @param againstBookingNo 被投诉订单（邻座噪音等）
     * @param incidentNo 关联设备事件（空调故障等）
     */
    @Transactional
    fun file(
        bookingNo: String,
        category: ComplaintCategory,
        description: String,
        againstBookingNo: String? = null,
        incidentNo: String? = null
    ): Complaint {
        val booking = bookingRepo.findByBookingNo(bookingNo) ?: throw NotFoundException("预约不存在")
        val store = booking.store
        val user = booking.user

        val inc = incidentNo?.let { incidentRepo.findByIncidentNo(it) }
        if (inc != null && inc.store.id != store.id) throw com.studyroom.support.BizException("INCIDENT_STORE_MISMATCH", "事件不属于该门店")

        val cause = classify(category, booking, againstBookingNo, inc != null)

        val complaint = complaintRepo.save(
            Complaint(
                complaintNo = "TMP", store = store, complainant = user,
                bookingNo = bookingNo, againstBookingNo = againstBookingNo,
                category = category, description = description,
                relatedIncidentNo = inc?.incidentNo,
                monitorEventNo = inc?.monitorEventNo,
                createdAt = clock.now()
            )
        )
        complaint.complaintNo = BizNoGenerator.complaint(complaint.id!!)

        val ticket = ticketRepo.save(
            SupportTicket(
                ticketNo = "TMP", store = store, user = user, bookingNo = bookingNo,
                cause = cause, title = "[$category] ${description.take(30)}",
                description = description,
                incidentNo = inc?.incidentNo, complaintNo = complaint.complaintNo,
                monitorEventNos = inc?.monitorEventNo?.let { listOf(it).joinToString() },
                createdAt = clock.now(), updatedAt = clock.now()
            )
        )
        ticket.ticketNo = BizNoGenerator.ticket(ticket.id!!)
        complaint.relatedTicketNo = ticket.ticketNo

        timeline.record("COMPLAINT_FILED", store.id, bookingNo, "USER:${user.id}",
            "${complaint.complaintNo} $category，归因 $cause，工单 ${ticket.ticketNo}" +
                (againstBookingNo?.let { "，被诉订单 $it" } ?: ""))
        return complaint
    }

    /**
     * 归因规则：
     * - 设备/环境投诉且关联设备事件 → DEVICE_FAULT；
     * - 座位被占 → 查占用方订单是否迟到/延时，是则 USER_LATE；
     * - 邻座噪音 → USER_BEHAVIOR；
     * - 包月权益冲突类 → PRIVILEGE_ABUSE；其他 → OTHER。
     */
    private fun classify(
        category: ComplaintCategory,
        booking: Booking,
        againstBookingNo: String?,
        linkedIncident: Boolean
    ): DisputeCause {
        if (category == ComplaintCategory.EQUIPMENT_ENV && linkedIncident) return DisputeCause.DEVICE_FAULT
        if (category == ComplaintCategory.SEAT_OCCUPIED) {
            val offender = againstBookingNo?.let { bookingRepo.findByBookingNo(it) }
            if (offender != null && (offender.late || offender.extended)) return DisputeCause.USER_LATE
            // 本单被自动换座也说明根因是上一单迟到顺延
            if (booking.originalSeatId != null) return DisputeCause.USER_LATE
            return DisputeCause.OTHER
        }
        if (category == ComplaintCategory.NOISE_NEIGHBOR) return DisputeCause.USER_BEHAVIOR
        return DisputeCause.OTHER
    }

    @Transactional
    fun resolve(complaintNo: String, resolution: String, rejected: Boolean = false): Complaint {
        val c = complaintRepo.findByComplaintNo(complaintNo) ?: throw NotFoundException("投诉不存在")
        c.status = if (rejected) ComplaintStatus.REJECTED else ComplaintStatus.RESOLVED
        c.resolvedAt = clock.now()
        c.resolution = resolution
        timeline.record("COMPLAINT_HANDLED", c.store.id, c.bookingNo, "STAFF",
            "$complaintNo：$resolution")
        return c
    }
}
