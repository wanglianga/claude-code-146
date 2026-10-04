package com.studyroom.web

import com.studyroom.repo.IncidentRepository
import com.studyroom.service.IncidentService
import com.studyroom.support.NotFoundException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

@Tag(name = "设备与门店状态", description = "门禁/空调/停电等设备事件上报与处理")
@RestController
@RequestMapping("/api/incidents")
@org.springframework.transaction.annotation.Transactional
class IncidentController(
    private val incidentService: IncidentService,
    private val incidentRepo: IncidentRepository
) {
    @Operation(summary = "上报设备/环境事件（门禁打不开、空调故障等），可选挂起座位")
    @PostMapping
    fun open(@RequestBody @Valid req: IncidentRequest): IncidentView =
        Views.of(incidentService.open(req.storeId, req.type, req.description, req.seatId))

    @Operation(summary = "解决设备事件（座位恢复可约）")
    @PostMapping("/{incidentNo}/resolve")
    fun resolve(@PathVariable incidentNo: String, @RequestBody @Valid req: ResolveIncidentRequest): IncidentView =
        Views.of(incidentService.resolve(incidentNo, req.resolution))

    @Operation(summary = "门店事件列表")
    @GetMapping
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    fun list(@RequestParam storeId: Long): List<IncidentView> =
        incidentRepo.findByStoreIdOrderByOpenedAtDesc(storeId).map { Views.of(it) }

    @Operation(summary = "事件详情")
    @GetMapping("/{incidentNo}")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    fun get(@PathVariable incidentNo: String): IncidentView =
        Views.of(incidentRepo.findByIncidentNo(incidentNo) ?: throw NotFoundException("事件不存在"))
}
