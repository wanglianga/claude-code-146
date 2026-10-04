package com.studyroom.web

import com.studyroom.service.SupportService
import com.studyroom.service.TicketDecision
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

@Tag(name = "客服工单", description = "证据聚合（门禁日志/传感器/申诉/处理/监控编号）与责任判定退款")
@RestController
@RequestMapping("/api/tickets")
@org.springframework.transaction.annotation.Transactional
class SupportController(private val supportService: SupportService) {

    @Operation(summary = "客服查看争议证据包")
    @GetMapping("/{ticketNo}/evidence")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    fun evidence(@PathVariable ticketNo: String): EvidenceView = Views.of(supportService.evidence(ticketNo))

    @Operation(summary = "用户补充申诉")
    @PostMapping("/{ticketNo}/appeal")
    fun appeal(@PathVariable ticketNo: String, @RequestBody @Valid req: AppealRequest): TicketView =
        Views.of(supportService.appeal(ticketNo, req.text))

    @Operation(
        summary = "客服判定责任并结案",
        description = "STORE/SHARED 可退款，退款与结案在同一事务；USER 责任不退。同步关闭关联投诉。"
    )
    @PostMapping("/{ticketNo}/decide")
    fun decide(@PathVariable ticketNo: String, @RequestBody @Valid req: DecideTicketRequest): TicketView =
        Views.of(
            supportService.decide(
                ticketNo,
                TicketDecision(req.responsibility, req.refundAmount, req.note)
            )
        )

    @Operation(summary = "门店工单列表，可按根因筛选（DEVICE_FAULT/USER_LATE/PRIVILEGE_ABUSE/...）")
    @GetMapping
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    fun list(@RequestParam storeId: Long, cause: com.studyroom.domain.DisputeCause? = null): List<TicketView> =
        supportService.listByStore(storeId, cause).map { Views.of(it) }
}
