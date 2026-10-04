package com.studyroom.web

import com.studyroom.service.BookingService
import com.studyroom.service.IncidentService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

@Tag(name = "门店运营", description = "临时停电/恢复、迟到爽约扫描")
@RestController
@RequestMapping("/api/stores")
class StoreOpsController(
    private val incidentService: IncidentService,
    private val bookingService: BookingService
) {
    @Operation(
        summary = "设置/解除门店临时停电",
        description = "停电开始时对在场订单按门店责任强制结算（退剩余费用+押金并释放座位），期间门禁拒绝进入。"
    )
    @PostMapping("/{storeId}/blackout")
    fun blackout(@PathVariable storeId: Long, @RequestBody @Valid req: BlackoutRequest): Map<String, Any> {
        val affected = incidentService.setBlackout(storeId, req.blackout, req.reason)
        return mapOf("storeId" to storeId, "blackout" to req.blackout, "forceCheckedOut" to affected)
    }

    @Operation(summary = "运营扫描：迟到爽约计单退押金；到期包月权益关闭退押金")
    @PostMapping("/no-show-scan")
    fun noShowScan(): Map<String, Any> {
        val result = bookingService.runSweep()
        return mapOf(
            "noShowBookings" to result.noShowBookings,
            "closedMemberships" to result.closedMemberships
        )
    }
}
