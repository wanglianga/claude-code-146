package com.studybuddy.infra

import org.springframework.stereotype.Component

/**
 * 分布式锁执行模板：
 * - 座位/门店/预约等关键状态变更都在对应锁内进行，避免并发放行/占位/换座竞争
 * - 采用自旋等待（无人值守连续事件中多请求可能同时到达同一座位）
 */
@Component
class Locks(private val coord: Coordinator) {

    fun <T> withLock(
        key: String,
        ttlMillis: Long = 10_000,
        waitMillis: Long = 2_000,
        block: () -> T
    ): T {
        val deadline = System.currentTimeMillis() + waitMillis
        var token: String
        while (true) {
            val acquired = coord.tryLock(key, ttlMillis)
            if (acquired != null) { token = acquired; break }
            if (System.currentTimeMillis() >= deadline) throw LockAcquireException(key)
            Thread.sleep(30)
        }
        try {
            return block()
        } finally {
            coord.releaseLock(key, token)
        }
    }

    companion object {
        fun seatKey(seatId: Long) = "lock:seat:$seatId"
        fun storeKey(storeId: Long) = "lock:store:$storeId"
        fun reservationKey(reservationId: Long) = "lock:res:$reservationId"
        fun userKey(userId: Long) = "lock:user:$userId"

        // 门禁凭证（支付+实名后签发，物理闸机读取）
        fun gatePassKey(reservationId: Long) = "gatepass:$reservationId"

        // 座位运行时占用镜像（reservationId 或空）
        fun seatOccKey(seatId: Long) = "seatocc:$seatId"

        // 幂等
        fun idemKey(key: String) = "idem:$key"
    }
}
