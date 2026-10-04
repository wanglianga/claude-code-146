package com.studyroom.it

import com.studyroom.domain.*
import com.studyroom.service.ComplaintService
import com.studyroom.service.IncidentService
import com.studyroom.service.OperationsService
import com.studyroom.service.SupportService
import com.studyroom.service.TicketDecision
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/** 客服争议证据、责任判定退款与运营指标/争议来源分类 */
class SupportAndOpsIT : BaseIT() {

    @Autowired lateinit var fx: Fixtures
    @Autowired lateinit var complaints: ComplaintService
    @Autowired lateinit var support: SupportService
    @Autowired lateinit var ops: OperationsService
    @Autowired lateinit var incidentService: IncidentService

    @Test
    fun `ac failure complaint classifies as device fault and store-responsibility refund completes`() {
        val store = fx.store1()
        val seat = fx.seat(store.id!!, "A01")
        val user = fx.verifiedUser("空调投诉人", "13740000001")
        val t = Instant.now()
        val booking = fx.paidTimedBooking(user, store, seat, t, t.plus(Duration.ofMinutes(60)))

        // 门店上报空调故障，用户凭事件编号投诉
        val incident = incidentService.open(store.id!!, IncidentType.AC_FAILURE, "空调不制冷")
        val complaint = complaints.file(
            bookingNo = booking.bookingNo,
            category = ComplaintCategory.EQUIPMENT_ENV,
            description = "空调不制冷，房间闷热无法自习",
            incidentNo = incident.incidentNo
        )
        val ticketNo = complaint.relatedTicketNo!!

        // 证据包：工单、预约、时间轴、设备事件齐全，自动归因为设备故障
        val evidence = support.evidence(ticketNo)
        assertThat(evidence.incident?.incidentNo).isEqualTo(incident.incidentNo)
        assertThat(evidence.ticket.cause).isEqualTo(DisputeCause.DEVICE_FAULT)
        assertThat(evidence.timeline.map { it.eventType }).contains("COMPLAINT_FILED")

        // 用户申诉 → 客服判门店责任退款并结案
        support.appeal(ticketNo, "有监控时间点，请求退还部分费用")
        support.decide(
            ticketNo,
            TicketDecision(Responsibility.STORE, BigDecimal("5.00"), "空调故障属实，门店责任退 5 元")
        )

        val after = fx.bookings.findByBookingNo(booking.bookingNo)!!
        assertThat(after.refundedAmount.compareTo(BigDecimal("5.00"))).isGreaterThanOrEqualTo(0)

        val evidenceAfter = support.evidence(ticketNo)
        assertThat(evidenceAfter.complaint?.status).isEqualTo(ComplaintStatus.RESOLVED)
        assertThat(evidenceAfter.ticket.status).isEqualTo(TicketStatus.RESOLVED)
        assertThat(evidenceAfter.refunds).isNotEmpty

        // 运营端：争议来源可按门店筛出“设备故障”
        val deviceTickets = support.listByStore(store.id!!, DisputeCause.DEVICE_FAULT)
        assertThat(deviceTickets.map { it.ticketNo }).contains(ticketNo)

        val metrics = ops.metrics(store.id!!, t.minus(Duration.ofMinutes(30)), t.plus(Duration.ofMinutes(90)))
        assertThat(metrics.disputesByCause["DEVICE_FAULT"]).isEqualTo(1L)
        assertThat(metrics.deviceIncidents).isGreaterThanOrEqualTo(1L)
    }
}
