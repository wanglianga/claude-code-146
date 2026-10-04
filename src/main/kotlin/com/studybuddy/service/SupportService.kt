package com.studybuddy.service

import com.studybuddy.api.*
import com.studybuddy.domain.*
import com.studybuddy.infra.TimeService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.concurrent.atomic.AtomicLong

/**
 * 投诉与客服工单。
 *
 * 工单是争议主线，关联：
 * - 时间轴（timeline_events）
 * - 门禁日志（gate_logs，含监控事件编号）
 * - 座位传感器读数（占用/噪音/温度/供电）
 * - 用户申诉（appeals）
 * - 店员处理记录（complaint.handled*）
 * - 设备事件（空调/门锁/停电，决定责任方）
 *
 * 工单核定退款/补偿时，同一事务内更新订单金额；若 releaseSeat=true，
 * 完结订单并释放座位，保证“退款完成后座位不锁定”。
 */
@Service
class SupportService(
    private val ticketRepo: SupportTicketRepository,
    private val complaintRepo: ComplaintRepository,
    private val appealRepo: AppealRepository,
    private val gateLogRepo: GateLogRepository,
    private val sensorRepo: SensorReadingRepository,
    private val deviceRepo: DeviceEventRepository,
    private val timelineRepo: TimelineEventRepository,
    private val reservationRepo: ReservationRepository,
    private val userRepo: AppUserRepository,
    private val reservations: ReservationService,
    private val timeline: TimelineService,
    private val time: TimeService
) {
    private val seq = AtomicLong(0)

    // ---------------- 投诉 ----------------

    @Transactional
    fun fileComplaint(storeId: Long, req: ComplaintRequest): Complaint {
        val reservation = req.reservationId?.let { reservationRepo.findById(it).orElseThrow { NotFoundException("预约单", it) } }
        val deviceEventId = when (req.reason) {
            ComplaintReason.AC_FAULT -> findOpenDevice(storeId, req.seatId ?: reservation?.seat?.id, DeviceType.AC)?.id
            ComplaintReason.GATE_FAILED -> findOpenDevice(storeId, null, DeviceType.DOOR_LOCK)?.id
            ComplaintReason.POWER_OUTAGE -> findOpenDevice(storeId, null, DeviceType.POWER)?.id
            else -> null
        }
        val c = complaintRepo.save(
            Complaint(
                storeId = storeId,
                reservationId = req.reservationId,
                userId = req.userId ?: reservation?.user?.id,
                seatId = req.seatId ?: reservation?.seat?.id,
                reason = req.reason,
                detail = req.detail,
                againstUserId = req.againstUserId,
                deviceEventId = deviceEventId,
                cctvEventId = req.cctvEventId
            )
        )
        reservation?.let {
            timeline.add(TimelineType.COMPLAINT_FILED, it, ticketId = null,
                actorType = ActorType.USER, actorId = c.userId?.toString(),
                detail = "投诉：${req.reason}${req.detail?.let { d -> "；$d" } ?: ""} 监控=${req.cctvEventId ?: "-"}")
        }
        return c
    }

    private fun findOpenDevice(storeId: Long, seatId: Long?, type: DeviceType): DeviceEvent? {
        val ev = if (seatId != null)
            deviceRepo.findTopBySeatIdAndDeviceTypeOrderByCreatedAtDesc(seatId, type)
        else deviceRepo.findTopByStoreIdAndDeviceTypeOrderByCreatedAtDesc(storeId, type)
        return ev?.takeIf { it.eventType == DeviceEventType.REPORTED }
    }

    @Transactional
    fun handleComplaint(complaintId: Long, staffId: String, note: String?): Complaint {
        val c = complaintRepo.findById(complaintId).orElseThrow { NotFoundException("投诉", complaintId) }
        c.status = ComplaintStatus.ACKNOWLEDGED
        c.handledByStaffId = staffId
        c.handledAt = time.now()
        c.handleNote = note
        c.reservationId?.let { rid ->
            reservationRepo.findById(rid).ifPresent {
                timeline.add(TimelineType.TICKET_HANDLED, it, actorType = ActorType.STAFF, actorId = staffId,
                    detail = "店员受理投诉#$complaintId：${note ?: ""}")
            }
        }
        return c
    }

    // ---------------- 申诉 ----------------

    @Transactional
    fun fileAppeal(req: AppealRequest): Appeal {
        val a = appealRepo.save(
            Appeal(
                ticketId = req.ticketId,
                userId = req.userId,
                reservationId = req.reservationId,
                storeId = req.reservationId?.let { reservationRepo.findById(it).orElse(null)?.store?.id },
                content = req.content
            )
        )
        req.ticketId?.let { tid ->
            timeline.add(TimelineType.TICKET_OPENED, ticketId = tid,
                actorType = ActorType.USER, actorId = req.userId.toString(),
                detail = "用户申诉：${req.content}")
        }
        return a
    }

    // ---------------- 工单 ----------------

    @Transactional
    fun openTicket(req: CreateTicketRequest): SupportTicket {
        val reservation = req.reservationId?.let { reservationRepo.findById(it).orElseThrow { NotFoundException("预约单", it) } }
        val t = ticketRepo.save(
            SupportTicket(
                ticketNo = "TK${time.nowMillis()}${seq.incrementAndGet().toString().padStart(4, '0')}",
                storeId = req.storeId,
                reservationId = req.reservationId,
                userId = req.userId ?: reservation?.user?.id,
                category = req.category,
                rootCause = req.rootCause,
                summary = req.summary
            )
        )
        reservation?.let {
            it.ticketId = t.id
            if (it.status == ReservationStatus.BOOKED || it.status == ReservationStatus.IN_PROGRESS) {
                it.status = ReservationStatus.DISPUTED
            }
        }
        req.complaintId?.let { cid ->
            complaintRepo.findById(cid).ifPresent { it.ticketId = t.id }
        }
        timeline.add(TimelineType.TICKET_OPENED, reservation, ticketId = t.id, storeId = req.storeId,
            actorType = ActorType.STAFF,
            detail = "工单开立 ${t.ticketNo} 分类=${req.category} 根因=${req.rootCause}")
        return t
    }

    @Transactional
    fun handleTicket(id: Long, req: HandleTicketRequest): SupportTicket {
        val t = ticketRepo.findById(id).orElseThrow { NotFoundException("工单", id) }
        t.status = TicketStatus.IN_PROGRESS
        t.staffId = req.staffId
        t.staffNote = req.note
        timeline.add(TimelineType.TICKET_HANDLED, ticketId = t.id, storeId = t.storeId,
            actorType = ActorType.STAFF, actorId = req.staffId, detail = "工单处理中：${req.note ?: ""}")
        return t
    }

    /**
     * 核定退款/补偿并完结工单。
     * - refund：退预约费（如提前离场/停电无法服务）
     * - compensation：门店额外补偿（优惠券折现，演示直接入负向流水）
     * - releaseSeat：同步完结在场订单并释放座位（退费后绝不能继续锁座）
     * - blacklistAgainstUser：权益滥用用户拉黑
     */
    @Transactional
    fun resolveTicket(id: Long, req: ResolveTicketRequest): SupportTicket {
        val t = ticketRepo.findById(id).orElseThrow { NotFoundException("工单", id) }
        t.status = TicketStatus.RESOLVED
        t.staffId = req.staffId
        t.staffNote = req.note
        t.resolvedRefund = req.refund
        t.resolvedCompensation = req.compensation
        t.resolvedAt = time.now()

        val reservation = t.reservationId?.let { reservationRepo.findById(it).orElse(null) }
        if (reservation != null) {
            // 在场且要求释放座位：先结算（押金/延时），再做退款补偿
            if (req.releaseSeat && reservation.status in listOf(ReservationStatus.IN_PROGRESS, ReservationStatus.DISPUTED)
                && reservation.checkOutAt == null) {
                reservations.checkoutLocked(reservation, actorType = ActorType.STAFF, actorId = req.staffId)
            }
            if (req.refund > java.math.BigDecimal.ZERO) {
                reservations.refundInternal(reservation, req.refund, "工单${t.ticketNo}核定退款")
                timeline.add(TimelineType.REFUNDED, reservation, ticketId = t.id,
                    actorType = ActorType.STAFF, actorId = req.staffId,
                    detail = "工单核定退款 ${req.refund}")
            }
            if (req.compensation > java.math.BigDecimal.ZERO) {
                reservations.compensateInternal(reservation, req.compensation, "工单${t.ticketNo}门店补偿")
                timeline.add(TimelineType.COMPENSATED, reservation, ticketId = t.id,
                    actorType = ActorType.STAFF, actorId = req.staffId,
                    detail = "门店补偿 ${req.compensation}")
            }
            if (req.releaseSeat) {
                // 已结算或本就未进场：确保座位释放、凭证失效
                reservations.releaseSeat(reservation)
                if (reservation.checkOutAt != null && reservation.status == ReservationStatus.DISPUTED) {
                    reservation.status = ReservationStatus.REFUNDED
                } else if (reservation.status == ReservationStatus.DISPUTED && reservation.checkOutAt == null) {
                    // 未进场争议：取消占位
                    reservation.status = ReservationStatus.REFUNDED
                }
            }
            if (req.blacklistAgainstUser) {
                userRepo.findById(reservation.user.id!!).ifPresent { it.blacklisted = true }
            }
        }
        timeline.add(TimelineType.TICKET_CLOSED, ticketId = t.id, storeId = t.storeId,
            actorType = ActorType.STAFF, actorId = req.staffId,
            detail = "工单完结 退款=${req.refund} 补偿=${req.compensation} 释放座位=${req.releaseSeat}")
        return t
    }

    // ---------------- 证据包（客服争议处理） ----------------

    @Transactional(readOnly = true)
    fun evidence(ticketId: Long): EvidenceBundle {
        val t = ticketRepo.findById(ticketId).orElseThrow { NotFoundException("工单", ticketId) }
        val rid = t.reservationId
        val seatId = rid?.let { reservationRepo.findById(it).orElse(null)?.seat?.id }

        val timelineEvents = timelineRepo.findByTicketIdOrderByAtAsc(ticketId)
            .let { own -> if (rid != null) own + timelineRepo.findByReservationIdOrderByAtAsc(rid) else own }
            .distinctBy { it.id }
            .sortedBy { it.at }

        val gateLogs = rid?.let { gateLogRepo.findByReservationIdOrderByOccurredAtAsc(it) } ?: emptyList()
        val sensorReadings = seatId?.let { sid ->
            sensorRepo.findBySeatIdOrderByReportedAtDesc(sid).take(50)
        } ?: emptyList()
        val complaints = rid?.let { complaintRepo.findByReservationId(it) }
            ?: complaintRepo.findByStoreIdOrderByCreatedAtDesc(t.storeId).filter { it.ticketId == ticketId }
        val deviceEvents = buildList {
            addAll(deviceRepo.findByStoreIdOrderByCreatedAtDesc(t.storeId))
            seatId?.let { addAll(deviceRepo.findBySeatIdOrderByCreatedAtDesc(it)) }
        }.distinctBy { it.id }
        val appeals = buildList {
            addAll(appealRepo.findByTicketId(ticketId))
            rid?.let { addAll(appealRepo.findByReservationId(it)) }
        }.distinctBy { it.id }

        return EvidenceBundle(
            ticket = toTicketVo(t),
            timeline = timelineEvents.map { e ->
                TimelineEventVo(e.id!!, e.type, e.at, e.reservation?.id, e.storeId, e.seatId,
                    e.actorType, e.actorId, e.detail)
            },
            gateLogs = gateLogs.map { g ->
                GateLogVo(g.id!!, g.reservation?.id, g.storeId, g.seatId, g.userId,
                    g.direction, g.result, g.reason, g.occurredAt, g.deviceSn, g.cctvEventId)
            },
            sensorReadings = sensorReadings.map { s ->
                SensorReadingVo(s.id!!, s.seatId, s.sensorType, s.value, s.reportedAt)
            },
            complaints = complaints.map(::toComplaintVo),
            deviceEvents = deviceEvents.map { d ->
                DeviceEventVo(d.id!!, d.storeId, d.seatId, d.deviceType, d.eventType, d.at,
                    d.responsible, d.note, d.cctvEventId, d.affectedReservationIds.toList(), d.ticketId)
            },
            appeals = appeals.map { a ->
                AppealVo(a.id!!, a.ticketId, a.userId, a.reservationId, a.storeId, a.content, a.createdAt)
            }
        )
    }

    @Transactional(readOnly = true)
    fun listTickets(storeId: Long?): List<TicketVo> =
        (storeId?.let { ticketRepo.findByStoreIdOrderByCreatedAtDesc(it) } ?: ticketRepo.findAll())
            .map(::toTicketVo)

    @Transactional(readOnly = true)
    fun listComplaints(storeId: Long): List<ComplaintVo> =
        complaintRepo.findByStoreIdOrderByCreatedAtDesc(storeId).map(::toComplaintVo)

    private fun toTicketVo(t: SupportTicket) = TicketVo(
        t.id!!, t.ticketNo, t.storeId, t.reservationId, t.userId, t.category, t.rootCause,
        t.status, t.summary, t.staffId, t.staffNote, t.resolvedRefund, t.resolvedCompensation,
        t.openedAt, t.resolvedAt, t.closedAt
    )

    private fun toComplaintVo(c: Complaint) = ComplaintVo(
        c.id!!, c.storeId, c.reservationId, c.userId, c.seatId, c.reason, c.status,
        c.detail, c.againstUserId, c.deviceEventId, c.cctvEventId,
        c.handledByStaffId, c.handledAt, c.handleNote, c.ticketId
    )
}
