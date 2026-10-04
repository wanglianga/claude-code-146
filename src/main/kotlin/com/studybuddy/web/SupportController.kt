package com.studybuddy.web

import com.studybuddy.api.*
import com.studybuddy.service.SupportService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@Tag(name = "投诉与申诉")
@RestController
@RequestMapping("/api")
class ComplaintController(private val support: SupportService) {

    @Operation(summary = "提交投诉（邻座噪音/座位被占/空调故障/门禁打不开/停电），可附监控事件编号")
    @PostMapping("/stores/{storeId}/complaints")
    @ResponseStatus(HttpStatus.CREATED)
    fun file(@PathVariable storeId: Long, @RequestBody @Valid req: ComplaintRequest): ComplaintVo {
        val c = support.fileComplaint(storeId, req)
        return ComplaintVo(c.id!!, c.storeId, c.reservationId, c.userId, c.seatId, c.reason, c.status,
            c.detail, c.againstUserId, c.deviceEventId, c.cctvEventId,
            c.handledByStaffId, c.handledAt, c.handleNote, c.ticketId)
    }

    @Operation(summary = "门店投诉列表")
    @GetMapping("/stores/{storeId}/complaints")
    fun list(@PathVariable storeId: Long): List<ComplaintVo> = support.listComplaints(storeId)

    @Operation(summary = "店员受理投诉")
    @PostMapping("/complaints/{id}/handle")
    fun handle(@PathVariable id: Long, @RequestBody @Valid req: HandleTicketRequest): ComplaintVo {
        val c = support.handleComplaint(id, req.staffId, req.note)
        return ComplaintVo(c.id!!, c.storeId, c.reservationId, c.userId, c.seatId, c.reason, c.status,
            c.detail, c.againstUserId, c.deviceEventId, c.cctvEventId,
            c.handledByStaffId, c.handledAt, c.handleNote, c.ticketId)
    }

    @Operation(summary = "用户申诉（随工单提交）")
    @PostMapping("/appeals")
    @ResponseStatus(HttpStatus.CREATED)
    fun appeal(@RequestBody @Valid req: AppealRequest): AppealVo {
        val a = support.fileAppeal(req)
        return AppealVo(a.id!!, a.ticketId, a.userId, a.reservationId, a.storeId, a.content, a.createdAt)
    }
}

@Tag(name = "客服工单")
@RestController
@RequestMapping("/api/tickets")
class TicketController(private val support: SupportService) {

    @Operation(summary = "开立争议工单（指定分类与根因：设备故障/用户迟到/权益滥用等）")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun open(@RequestBody @Valid req: CreateTicketRequest): TicketVo {
        val t = support.openTicket(req)
        return TicketVo(t.id!!, t.ticketNo, t.storeId, t.reservationId, t.userId, t.category, t.rootCause,
            t.status, t.summary, t.staffId, t.staffNote, t.resolvedRefund, t.resolvedCompensation,
            t.openedAt, t.resolvedAt, t.closedAt)
    }

    @Operation(summary = "工单列表（可按门店）")
    @GetMapping
    fun list(@RequestParam(required = false) storeId: Long?): List<TicketVo> = support.listTickets(storeId)

    @Operation(summary = "客服开始处理")
    @PostMapping("/{id}/handle")
    fun handle(@PathVariable id: Long, @RequestBody @Valid req: HandleTicketRequest): TicketVo {
        val t = support.handleTicket(id, req)
        return TicketVo(t.id!!, t.ticketNo, t.storeId, t.reservationId, t.userId, t.category, t.rootCause,
            t.status, t.summary, t.staffId, t.staffNote, t.resolvedRefund, t.resolvedCompensation,
            t.openedAt, t.resolvedAt, t.closedAt)
    }

    @Operation(summary = "核定退款/补偿并完结；releaseSeat=true 时同步释放座位，杜绝退费后锁座")
    @PostMapping("/{id}/resolve")
    fun resolve(@PathVariable id: Long, @RequestBody @Valid req: ResolveTicketRequest): TicketVo {
        val t = support.resolveTicket(id, req)
        return TicketVo(t.id!!, t.ticketNo, t.storeId, t.reservationId, t.userId, t.category, t.rootCause,
            t.status, t.summary, t.staffId, t.staffNote, t.resolvedRefund, t.resolvedCompensation,
            t.openedAt, t.resolvedAt, t.closedAt)
    }

    @Operation(summary = "争议证据包：时间轴+门禁日志+传感器+投诉+设备事件+申诉")
    @GetMapping("/{id}/evidence")
    fun evidence(@PathVariable id: Long): EvidenceBundle = support.evidence(id)
}
