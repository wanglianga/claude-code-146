package com.studybuddy.infra

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.StringRedisTemplate

@Configuration
class CoordinatorConfig {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 启动探测：Redis 可达则使用分布式实现，否则降级为进程内实现。
     * 降级期间仅保证单实例一致性；多实例部署请确保 compose 中的 redis 服务可达。
     */
    @Bean
    fun coordinator(redis: StringRedisTemplate): Coordinator {
        val redisCoord = RedisCoordinator(redis)
        return try {
            if (redisCoord.alive()) {
                log.info("Coordinator 使用 Redis 分布式实现")
                redisCoord
            } else {
                log.warn("Redis PING 无响应，Coordinator 降级为本地实现（仅限单实例）")
                LocalCoordinator()
            }
        } catch (e: Exception) {
            log.warn("Redis 不可达（{}），Coordinator 降级为本地实现（仅限单实例）", e.message)
            LocalCoordinator()
        }
    }
}
