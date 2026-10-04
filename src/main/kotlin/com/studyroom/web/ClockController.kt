package com.studyroom.web

import com.studyroom.support.TimeProvider
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

@Tag(name = "测试时钟", description = "演示/测试用：覆写业务时间，便于复现迟到、顺延、碰撞连续事件")
@RestController
@RequestMapping("/api/admin/clock")
class ClockController(private val clock: TimeProvider) {

    @Operation(summary = "设置虚拟当前时间（ISO-8601）；time 为 null 时恢复真实时钟")
    @PostMapping
    fun set(@RequestBody @Valid req: ClockOverrideRequest): Map<String, Any?> {
        if (req.time == null) clock.clearOverride() else clock.setOverride(req.time)
        return mapOf("override" to req.time, "now" to clock.now().toString())
    }

    @Operation(summary = "查看当前业务时间")
    @GetMapping
    fun get(): Map<String, String> = mapOf("now" to clock.now().toString())
}
