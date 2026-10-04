package com.studybuddy.web

import com.studybuddy.api.*
import com.studybuddy.service.DeviceService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@Tag(name = "设备与传感器")
@RestController
@RequestMapping("/api/stores/{storeId}")
class DeviceController(private val deviceService: DeviceService) {

    @Operation(summary = "设备故障上报（门锁/空调/照明/总电源/储物柜；POWER 会触发门店停电流程）")
    @PostMapping("/device-events")
    @ResponseStatus(HttpStatus.CREATED)
    fun report(@PathVariable storeId: Long, @RequestBody @Valid req: DeviceEventRequest): DeviceEventVo =
        deviceService.toVo(deviceService.report(storeId, req))

    @Operation(summary = "设备恢复（停电恢复后门店重新开放，受影响订单列入补偿范围）")
    @PostMapping("/device-events/{eventId}/resolve")
    fun resolve(@PathVariable storeId: Long, @PathVariable eventId: Long,
                @RequestParam(required = false) note: String?): DeviceEventVo =
        deviceService.toVo(deviceService.resolve(storeId, eventId, note))

    @Operation(summary = "门店设备事件列表")
    @GetMapping("/device-events")
    fun events(@PathVariable storeId: Long): List<DeviceEventVo> = deviceService.listStoreEvents(storeId)
}

@Tag(name = "座位传感器")
@RestController
@RequestMapping("/api/seats/{seatId}")
class SensorController(private val deviceService: DeviceService) {

    @Operation(summary = "上报传感器读数（OCCUPANCY 1/0、NOISE dB、TEMPERATURE ℃、POWER 1/0）")
    @PostMapping("/sensor-readings")
    @ResponseStatus(HttpStatus.CREATED)
    fun ingest(@PathVariable seatId: Long, @RequestBody @Valid req: SensorReadingRequest): SensorReadingVo {
        val r = deviceService.ingest(seatId, req)
        return SensorReadingVo(r.id!!, r.seatId, r.sensorType, r.value, r.reportedAt)
    }

    @Operation(summary = "座位传感器读数（客服争议处理时查看）")
    @GetMapping("/sensor-readings")
    fun list(@PathVariable seatId: Long): List<SensorReadingVo> = deviceService.listSeatReadings(seatId)
}
