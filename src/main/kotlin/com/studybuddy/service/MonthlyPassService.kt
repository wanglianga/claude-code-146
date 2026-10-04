package com.studybuddy.service

import com.studybuddy.api.CreatePassRequest
import com.studybuddy.api.PassVo
import com.studybuddy.domain.*
import com.studybuddy.infra.TimeService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicLong

@Service
class MonthlyPassService(
    private val passRepo: MonthlyPassRepository,
    private val userRepo: AppUserRepository,
    private val storeRepo: StoreRepository,
    private val seatRepo: SeatRepository,
    private val time: TimeService
) {
    private val seq = AtomicLong(0)

    @Transactional
    fun create(req: CreatePassRequest): MonthlyPass {
        val user = userRepo.findById(req.userId).orElseThrow { NotFoundException("用户", req.userId) }
        val store = storeRepo.findById(req.storeId).orElseThrow { NotFoundException("门店", req.storeId) }
        if (req.validTo.isBefore(req.validFrom)) throw RuleRejectException("包月结束日期不得早于开始日期")

        val fixedSeat = req.fixedSeatId?.let { sid ->
            val s = seatRepo.findById(sid).orElseThrow { NotFoundException("座位", sid) }
            if (s.store.id != store.id) throw RuleRejectException("固定座不属于该门店")
            // 同一座位的固定座包月有效期不得重叠（包月权益冲突）
            var d = req.validFrom
            while (!d.isAfter(req.validTo)) {
                val holders = passRepo.findActiveFixedOnSeat(sid, PassMode.FIXED_SEAT, PassStatus.ACTIVE, d)
                if (holders.any { it.user.id != user.id }) {
                    throw ConflictException("该座位在 $d 已售出固定座包月，包月权益冲突")
                }
                d = d.plusMonths(1)
            }
            s
        }
        if (req.mode == PassMode.FIXED_SEAT && fixedSeat == null) {
            throw RuleRejectException("固定座包月必须绑定座位")
        }

        return passRepo.save(
            MonthlyPass(
                passNo = "MP${req.validFrom}${seq.incrementAndGet().toString().padStart(4, '0')}",
                user = user,
                storeId = store.id!!,
                fixedSeat = fixedSeat,
                mode = req.mode,
                validFrom = req.validFrom,
                validTo = req.validTo,
                status = PassStatus.ACTIVE,
                price = req.price
            )
        )
    }

    @Transactional
    fun revoke(id: Long): MonthlyPass {
        val p = passRepo.findById(id).orElseThrow { NotFoundException("包月权益", id) }
        p.status = PassStatus.REVOKED
        return p
    }

    @Transactional(readOnly = true)
    fun listByStore(storeId: Long): List<PassVo> =
        passRepo.findByStoreId(storeId).map(::toVo)

    @Transactional(readOnly = true)
    fun listByUser(userId: Long): List<PassVo> =
        passRepo.findByUserId(userId).map(::toVo)

    /** 到期批处理（调度器调用） */
    @Transactional
    fun expireOutdated(today: LocalDate = LocalDate.ofInstant(time.now(), java.time.ZoneOffset.UTC)): Int {
        var n = 0
        passRepo.findAll().forEach { p ->
            if (p.status == PassStatus.ACTIVE && p.validTo.isBefore(today)) {
                p.status = PassStatus.EXPIRED; n++
            }
        }
        return n
    }

    private fun toVo(p: MonthlyPass) = PassVo(
        p.id!!, p.passNo, p.user.id!!, p.storeId, p.fixedSeat?.id, p.mode,
        p.validFrom, p.validTo, p.status, p.price
    )
}
