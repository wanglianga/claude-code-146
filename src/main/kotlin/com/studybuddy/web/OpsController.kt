package com.studybuddy.web

import com.studybuddy.api.ReconcileReport
import com.studybuddy.api.StoreOperationReport
import com.studybuddy.service.OpsService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.*
import java.time.Instant

@Tag(name = "门店运营")
@RestController
@RequestMapping("/api/ops")
class OpsController(private val ops: OpsService) {

    @Operation(summary = "门店运营报表（上座率/延时率/投诉率/设备故障/争议根因/收支）")
    @GetMapping("/stores/{storeId}/report")
    fun report(
        @PathVariable storeId: Long,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) from: Instant,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) to: Instant
    ): StoreOperationReport = ops.report(storeId, from, to)

    @Operation(summary = "执行一致性对账并自动修复（门禁已放行订单未开始 / 退费后座位仍锁 / Redis 镜像漂移）")
    @PostMapping("/reconcile")
    fun reconcile(): ReconcileReport = ops.reconcile()
}
