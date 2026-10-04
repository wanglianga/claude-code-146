package com.studyroom.web

import com.studyroom.repo.AccessLogRepository
import com.studyroom.repo.TimelineEventRepository
import com.studyroom.service.AccessService
import com.studyroom.service.CheckoutMode
import com.studyroom.service.SensorService
import com.studyroom.support.BizException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

@Tag(name = "门禁与传感器", description = "进场/离场判定、座位传感器上报、时间轴查询")
@RestController
@RequestMapping("/api")
class AccessController(
    private val accessService: AccessService,
    private val sensorService: SensorService,
    private val accessLogRepo: AccessLogRepository,
    private val timelineRepo: TimelineEventRepository
) {
    @Operation(
        summary = "刷卡进场",
        description = "按预约、支付、实名、时间窗判定；遇顺延占座自动换座放行；停电/门禁故障拒绝。" +
            "放行与订单置 IN_PROGRESS 在同一事务完成。"
    )
    @PostMapping("/access/enter")
    fun enter(@RequestBody @Valid req: EnterRequest): EntryDecisionView {
        val d = accessService.enter(req.userId, req.storeId, req.doorId)
        return EntryDecisionView(d.granted, d.result, d.bookingNo, d.seatCode, d.detail)
    }

    @Operation(
        summary = "刷卡离场",
        description = "记录实际离开、超时费、设备与储物柜状态；提前离场/门店责任触发退费，" +
            "退款与座位释放在同一事务完成。"
    )
    @PostMapping("/access/leave")
    fun leave(@RequestBody @Valid req: LeaveRequest): ExitResultView {
        val mode = try {
            CheckoutMode.valueOf(req.mode.uppercase())
        } catch (e: IllegalArgumentException) {
            throw BizException("BAD_MODE", "mode 必须是 NORMAL / EARLY_LEAVE / STORE_FAULT")
        }
        val r = accessService.leave(
            userId = req.userId, storeId = req.storeId,
            lockerReturned = req.lockerReturned, seatEquipmentOk = req.seatEquipmentOk,
            mode = mode, incidentNo = req.incidentNo, doorId = req.doorId
        )
        return ExitResultView(r.bookingNo, r.seatCode, Views.of(r.settlement), r.status, r.detail)
    }

    @Operation(summary = "座位传感器/巡检上报占用情况（无人值守门店发现座位被占）")
    @PostMapping("/sensors/report")
    fun sensor(@RequestBody @Valid req: SensorReportRequest): SensorView =
        Views.of(sensorService.report(req.storeId, req.seatId, req.occupied, req.occupiedByBookingNo))

    @Operation(summary = "按预约查门禁日志")
    @GetMapping("/bookings/{bookingNo}/access-logs")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    fun accessLogs(@PathVariable bookingNo: String): List<AccessLogView> =
        accessLogRepo.findByBookingNoOrderByCreatedAtAsc(bookingNo).map { Views.of(it) }

    @Operation(summary = "按预约查统一时间轴")
    @GetMapping("/bookings/{bookingNo}/timeline")
    fun timeline(@PathVariable bookingNo: String): List<TimelineEventView> =
        timelineRepo.findByBookingNoOrderByOccurredAtAsc(bookingNo).map { Views.of(it) }
}
