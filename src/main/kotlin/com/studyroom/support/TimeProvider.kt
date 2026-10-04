package com.studyroom.support

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * 全局时钟。默认取系统时间；可通过运维接口写入 Redis 覆盖值，
 * 便于在无人工干预的自助场景下复现“迟到、顺延、跨订单碰撞”等连续事件。
 */
@Component
class TimeProvider(private val redis: StringRedisTemplate) {

    fun now(): Instant {
        val override = redis.opsForValue().get(CLOCK_KEY)
        if (!override.isNullOrBlank()) {
            return Instant.from(DateTimeFormatter.ISO_INSTANT.parse(override))
        }
        return Instant.now()
    }

    fun setOverride(instant: Instant) {
        redis.opsForValue().set(CLOCK_KEY, DateTimeFormatter.ISO_INSTANT.format(instant))
    }

    fun clearOverride() {
        redis.delete(CLOCK_KEY)
    }

    companion object {
        const val CLOCK_KEY = "system:clock:override"
    }
}
