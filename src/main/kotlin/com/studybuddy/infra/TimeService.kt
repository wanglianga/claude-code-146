package com.studybuddy.infra

import org.springframework.stereotype.Component
import java.time.Instant

/**
 * 统一时钟：业务代码一律通过该服务取当前时间，
 * 集成测试可拨动时钟验证迟到/顺延/超时等连续事件。
 */
@Component
class TimeService {
    @Volatile
    private var fixed: Instant? = null

    fun now(): Instant = fixed ?: Instant.now()

    fun nowMillis(): Long = now().toEpochMilli()

    /** 测试：固定时间 */
    fun setFixed(instant: Instant?) {
        fixed = instant
    }

    /** 测试：相对当前固定/真实时钟推进 */
    fun advanceMillis(ms: Long) {
        fixed = now().plusMillis(ms)
    }
}
