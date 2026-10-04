package com.studyroom.web

import com.studyroom.repo.ComplaintRepository
import com.studyroom.service.ComplaintService
import com.studyroom.support.NotFoundException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

@Tag(name = "投诉", description = "邻座噪音、座位被占、设备环境投诉，自动归因并开工单")
@RestController
@RequestMapping("/api/complaints")
@org.springframework.transaction.annotation.Transactional
class ComplaintController(
    private val complaintService: ComplaintService,
    private val complaintRepo: ComplaintRepository
) {
    @Operation(
        summary = "提交投诉",
        description = "系统自动归因（设备故障/用户迟到顺延/权益滥用/用户行为），并生成客服工单。"
    )
    @PostMapping
    fun file(@RequestBody @Valid req: ComplaintRequest): ComplaintView =
        Views.of(
            complaintService.file(
                bookingNo = req.bookingNo, category = req.category, description = req.description,
                againstBookingNo = req.againstBookingNo, incidentNo = req.incidentNo
            )
        )

    @Operation(summary = "处理/驳回投诉")
    @PostMapping("/{complaintNo}/resolve")
    fun resolve(@PathVariable complaintNo: String, @RequestBody @Valid req: ResolveComplaintRequest): ComplaintView =
        Views.of(complaintService.resolve(complaintNo, req.resolution, req.rejected))

    @Operation(summary = "门店投诉列表")
    @GetMapping
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    fun list(@RequestParam storeId: Long): List<ComplaintView> =
        complaintRepo.findByStoreIdOrderByCreatedAtDesc(storeId).map { Views.of(it) }

    @Operation(summary = "投诉详情")
    @GetMapping("/{complaintNo}")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    fun get(@PathVariable complaintNo: String): ComplaintView =
        Views.of(complaintRepo.findByComplaintNo(complaintNo) ?: throw NotFoundException("投诉不存在"))
}
