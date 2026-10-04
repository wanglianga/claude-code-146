package com.studybuddy.infra

import com.fasterxml.jackson.databind.ObjectMapper
import com.studybuddy.domain.ReservationStatus
import org.springframework.stereotype.Service

/** 门禁凭证载荷（物理闸机/边缘网关可凭此快速判断，不必每次打 DB） */
data class GatePass(
    val reservationId: Long,
    val storeId: Long,
    val seatId: Long?,
    val userId: Long,
    val status: ReservationStatus,
    val startTime: Long,
    val scheduledEndTime: Long,
    val paid: Boolean,
    val idVerified: Boolean,
    /** 凭证版本：状态机迁移时旧版本立即失效 */
    val version: Int
)

/**
 * 门禁凭证 + 座位占用 Redis 镜像。
 * - 支付成功签发；状态每次变化（进场/顺延/延时/离场/退款）同步刷新版本
 * - 座位镜像仅做快速准入提示；放行的最终权威判定仍走 DB + 座位锁
 */
@Service
class GatePassService(
    private val coord: Coordinator,
    private val mapper: ObjectMapper
) {
    fun issue(pass: GatePass, ttlMillis: Long) {
        coord.setKV(Locks.gatePassKey(pass.reservationId), mapper.writeValueAsString(pass), ttlMillis)
    }

    fun read(reservationId: Long): GatePass? =
        coord.getKV(Locks.gatePassKey(reservationId))?.let {
            runCatching { mapper.readValue(it, GatePass::class.java) }.getOrNull()
        }

    fun revoke(reservationId: Long) {
        coord.deleteKV(Locks.gatePassKey(reservationId))
    }

    fun mirrorSeatOccupied(seatId: Long, reservationId: Long) {
        coord.setKV(Locks.seatOccKey(seatId), reservationId.toString())
    }

    fun mirrorSeatFree(seatId: Long) {
        coord.deleteKV(Locks.seatOccKey(seatId))
    }

    fun mirrorOccupant(seatId: Long): Long? = coord.getKV(Locks.seatOccKey(seatId))?.toLongOrNull()

    /** 幂等占位：key 在 ttl 内只能成功一次 */
    fun firstTime(idempotencyKey: String, ttlMillis: Long = 24 * 3600_000L): Boolean =
        coord.setIfAbsentKV(Locks.idemKey(idempotencyKey), "1", ttlMillis)
}
