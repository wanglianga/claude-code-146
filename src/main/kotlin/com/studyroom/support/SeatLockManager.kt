package com.studyroom.support

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * 基于 Redis 的短租座位分布式锁与实时占用状态。
 *
 * - lock:  `seat:lock:{seatId}` —— 进/离场、换座的临界区，保证两个连续订单不会被同时处理
 * - state: `seat:state:{seatId}` —— 当前在场预约号（与 JPA 订单状态互为校验）
 *
 * 一致性底线：
 * 1) 门禁放行与订单置 IN_PROGRESS 在同一业务流程内完成；
 * 2) 退款完成的同一流程内必须释放座位状态，禁止“退款后座位仍锁定”。
 */
@Component
class SeatLockManager(private val redis: StringRedisTemplate) {

    fun <T> withSeatLock(seatId: Long, ttl: Duration = Duration.ofSeconds(30), block: () -> T): T {
        val key = lockKey(seatId)
        val token = "lock-${seatId}-${System.nanoTime()}"
        var acquired = false
        var waited = 0L
        while (!acquired && waited < LOCK_WAIT_MS) {
            acquired = redis.opsForValue().setIfAbsent(key, token, ttl) == true
            if (!acquired) Thread.sleep(100)
            waited += 100
        }
        if (!acquired) throw ConflictException("SEAT_BUSY", "座位 $seatId 正在处理，请稍后重试")
        try {
            return block()
        } finally {
            // 仅释放自己的锁，防止 TTL 到期后误删他人锁
            if (redis.opsForValue().get(key) == token) redis.delete(key)
        }
    }

    fun currentOccupant(seatId: Long): String? = redis.opsForValue().get(stateKey(seatId))

    /** 标记在场；ttl 为最长在场时长，防止异常退出导致座位永久锁定 */
    fun markOccupied(seatId: Long, bookingNo: String, ttl: Duration) {
        redis.opsForValue().set(stateKey(seatId), bookingNo, ttl)
    }

    fun release(seatId: Long, bookingNo: String) {
        val key = stateKey(seatId)
        // 只在占用者确为本预约时释放，避免 A 离场误放 B
        if (redis.opsForValue().get(key) == bookingNo) redis.delete(key)
    }

    fun isOccupied(seatId: Long): Boolean = redis.hasKey(stateKey(seatId))

    companion object {
        private const val LOCK_WAIT_MS = 5_000L
        fun lockKey(seatId: Long) = "seat:lock:$seatId"
        fun stateKey(seatId: Long) = "seat:state:$seatId"
    }
}
