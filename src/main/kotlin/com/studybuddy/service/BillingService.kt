package com.studybuddy.service

import com.studybuddy.domain.*
import com.studybuddy.infra.TimeService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode

@Service
class BillingService(
    private val billingRepo: BillingEntryRepository,
    private val reservationRepo: ReservationRepository,
    private val time: TimeService
) {
    @Transactional
    fun record(
        reservation: Reservation,
        type: BillingType,
        amount: BigDecimal,
        note: String? = null,
        ticketId: Long? = null,
        at: java.time.Instant? = null
    ): BillingEntry {
        val e = BillingEntry(reservation = reservation, type = type, amount = amount,
            at = at ?: time.now(), note = note, ticketId = ticketId)
        return billingRepo.save(e)
    }

    fun list(reservationId: Long): List<BillingEntry> = billingRepo.findByReservationIdOrderByAtAsc(reservationId)

    /** 预约基础价（按小时向上取整，包间/夜间座沿用座位费率；演示价格策略） */
    fun quote(startTime: Long, endTime: Long, hourlyPrice: BigDecimal): BigDecimal {
        val minutes = ((endTime - startTime) / 60000).coerceAtLeast(1)
        val hours = Math.ceil(minutes / 60.0)
        return hourlyPrice.multiply(BigDecimal.valueOf(hours)).setScale(2, RoundingMode.HALF_UP)
    }

    /** 延时费：每分钟费率 × 分钟 */
    fun overtimeFee(minutes: Int, perMinute: BigDecimal): BigDecimal =
        perMinute.multiply(BigDecimal(minutes)).setScale(2, RoundingMode.HALF_UP)

    /**
     * 提前离场退费计算：
     * - 仅对“用户主动提前离场”且已用时长不超过预约时长 refundUsedCapRatio 的部分退还未使用时段费用
     * - 设备故障/停电等门店责任走工单补偿，不受此比例限制
     */
    fun earlyLeaveRefund(reservation: Reservation, checkOutAt: java.time.Instant, capRatio: Double): BigDecimal {
        if (reservation.isMonthly) return BigDecimal.ZERO
        val total = reservation.endTime - reservation.startTime
        val used = checkOutAt.toEpochMilli() - reservation.startTime
        if (total <= 0 || used < 0) return BigDecimal.ZERO
        if (used.toDouble() / total > capRatio) return BigDecimal.ZERO
        val unused = (total - used).coerceAtLeast(0)
        return reservation.price
            .multiply(BigDecimal.valueOf(unused.toDouble() / total))
            .setScale(2, RoundingMode.HALF_UP)
    }
}
