package com.studybuddy.service

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 定时触发器（可通过 studyroom.scheduler.enabled=false 关闭，测试中避免后台扫描干扰）。
 * 业务逻辑见 [HousekeepingService]。
 */
@Component
@ConditionalOnProperty(name = ["studyroom.scheduler.enabled"], havingValue = "true", matchIfMissing = true)
class HousekeepingScheduler(private val housekeeping: HousekeepingService) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 60_000L, initialDelay = 5_000L)
    fun scanLateAndNoShow() = housekeeping.scanLateAndNoShow()

    @Scheduled(fixedDelay = 60_000L, initialDelay = 10_000L)
    fun scanOvertime() = housekeeping.scanOvertime()

    @Scheduled(cron = "0 5 0 * * *")
    fun expirePasses() {
        val n = housekeeping.expirePasses()
        if (n > 0) log.info("包月权益到期处理 {} 条", n)
    }
}
