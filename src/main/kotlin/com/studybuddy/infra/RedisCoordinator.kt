package com.studybuddy.infra

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Redis 实现：
 * - 锁：SET key token NX PX ttl；释放用 Lua 校验 token，避免误删他人锁
 * - KV：普通 SET / SET NX EX/PX
 */
class RedisCoordinator(private val redis: StringRedisTemplate) : Coordinator {

    private val releaseScript = DefaultRedisScript(
        """
        if redis.call('get', KEYS[1]) == ARGV[1] then
            return redis.call('del', KEYS[1])
        else
            return 0
        end
        """.trimIndent(),
        java.lang.Long::class.java
    )

    override fun tryLock(key: String, ttlMillis: Long): String? {
        val token = UUID.randomUUID().toString()
        val ok = redis.opsForValue().setIfAbsent(key, token, ttlMillis, TimeUnit.MILLISECONDS)
        return if (ok == true) token else null
    }

    override fun releaseLock(key: String, token: String): Boolean {
        return (redis.execute(releaseScript, listOf(key), token)?.toLong() ?: -1L) == 1L
    }

    override fun setKV(key: String, value: String, ttlMillis: Long) {
        if (ttlMillis > 0) redis.opsForValue().set(key, value, ttlMillis, TimeUnit.MILLISECONDS)
        else redis.opsForValue().set(key, value)
    }

    override fun getKV(key: String): String? = redis.opsForValue().get(key)

    override fun setIfAbsentKV(key: String, value: String, ttlMillis: Long): Boolean {
        val r = if (ttlMillis > 0)
            redis.opsForValue().setIfAbsent(key, value, ttlMillis, TimeUnit.MILLISECONDS)
        else redis.opsForValue().setIfAbsent(key, value)
        return r == true
    }

    override fun deleteKV(key: String): Boolean = redis.delete(key)

    override fun alive(): Boolean = try {
        redis.requiredConnectionFactory.connection.use { it.ping() == "PONG" }
    } catch (_: Exception) {
        false
    }
}
