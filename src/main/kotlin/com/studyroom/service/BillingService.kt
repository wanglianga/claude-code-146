package com.studyroom.service

import com.studyroom.domain.*
import com.studyroom.repo.ChargeRepository
import com.studyroom.repo.RefundRepository
import com.studyroom.support.BizException
import com.studyroom.support.TimeProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant

/** 离场结算方式 */
enum class CheckoutMode {
    /** 正常离场（到点或超时） */
    NORMAL,
    /** 用户主动提前离场 */
    EARLY_LEAVE,
    /** 门店责任离场（停电/空调故障等），按门店责任退剩余费用 */
    STORE_FAULT
}

@Service
class BillingService(
    private val chargeRepo: ChargeRepository,
    private val refundRepo: RefundRepository,
    private val timeline: TimelineService,
    private val clock: TimeProvider
) {
    fun money(v: Double): BigDecimal = BigDecimal(v).setScale(2, RoundingMode.HALF_UP)

    /** 单座/包间按小时；夜间座一口价 */
    fun baseFee(seat: Seat, store: Store, type: BookingType, minutes: Long): BigDecimal {
        if (type == BookingType.NIGHT) return seat.hourlyPrice.multiply(BigDecimal.valueOf(store.priceMultiplier)).scale2()
        val hours = (minutes.toDouble() / 60.0)
        return seat.hourlyPrice.multiply(BigDecimal.valueOf(hours))
            .multiply(BigDecimal.valueOf(store.priceMultiplier)).scale2()
    }

    /** 延时费用：按 30 分钟为最小计费单位向上取整 */
    fun extensionFee(seat: Seat, store: Store, minutes: Long): BigDecimal {
        if (minutes <= 0) return BigDecimal.ZERO.setScale(2)
        val units = (minutes + 29) / 30
        return seat.hourlyPrice.multiply(BigDecimal.valueOf(0.5))
            .multiply(BigDecimal.valueOf(units))
            .multiply(BigDecimal.valueOf(store.priceMultiplier)).scale2()
    }

    fun depositFor(seat: Seat, useLocker: Boolean): BigDecimal {
        var d = when (seat.seatType) {
            SeatType.ROOM -> 100.0
            else -> 20.0
        }
        if (useLocker) d += 30.0
        return money(d)
    }

    @Transactional
    fun charge(booking: Booking, type: ChargeType, amount: BigDecimal, note: String? = null): Charge {
        val c = chargeRepo.save(Charge(booking = booking, type = type, amount = amount, note = note, createdAt = clock.now()))
        if (type == ChargeType.EXTENSION_FEE || type == ChargeType.OVERTIME_FEE) {
            booking.extraChargedAmount = booking.extraChargedAmount.add(amount)
        }
        timeline.record("CHARGE_${type.name}", booking.store.id, booking.bookingNo, "SYSTEM",
            "$amount 元 $note")
        return c
    }

    /**
     * 完成一笔退款（模拟原路退回，立即 COMPLETED）。
     * 退款与座位释放必须在同一业务流程内由调用方保证。
     */
    @Transactional
    fun refund(
        booking: Booking,
        reason: RefundReason,
        amount: BigDecimal,
        depositRefund: Boolean = false,
        ticketNo: String? = null,
        incidentNo: String? = null,
        note: String? = null
    ): Refund {
        val capped = capRefund(booking, amount)
        if (capped.signum() <= 0) {
            throw BizException("REFUND_EXCEEDED", "可退金额不足（已退 ${booking.refundedAmount}，已付 ${booking.paidAmount}）")
        }
        val saved = refundRepo.save(
            Refund(
                booking = booking,
                reason = reason,
                amount = capped,
                status = RefundStatus.COMPLETED,
                depositRefund = depositRefund,
                ticketNo = ticketNo,
                incidentNo = incidentNo,
                note = note,
                createdAt = clock.now(),
                completedAt = clock.now()
            )
        )
        saved.refundNo = BizNoGenerator.refund(saved.id!!)
        booking.refundedAmount = booking.refundedAmount.add(capped)
        timeline.record(
            "REFUND_${reason.name}", booking.store.id, booking.bookingNo, "SYSTEM",
            "退款 $capped 元（${if (depositRefund) "押金" else "费用"}）$note"
        )
        return saved
    }

    /** 可退上限 = 已付 - 已退 - 押金扣赔（扣赔部分不退） */
    private fun capRefund(booking: Booking, want: BigDecimal): BigDecimal {
        val max = booking.paidAmount.subtract(booking.refundedAmount).subtract(booking.depositDeducted)
        return if (want > max) max.max(BigDecimal.ZERO.setScale(2)) else want
    }

    /**
     * 离场结算：超时费 / 设备储物柜扣赔 / 提前离场退费 / 押金退还。
     * 返回结算明细（金额均为正数，方向见字段名）。
     */
    @Transactional
    fun settleCheckout(
        booking: Booking,
        checkoutAt: Instant,
        lockerReturned: Boolean,
        seatEquipmentOk: Boolean,
        mode: CheckoutMode,
        incidentNo: String? = null
    ): CheckoutSettlement {
        val seat = booking.seat ?: throw BizException("NO_SEAT", "预约未分配座位，无法结算")
        var overtime = BigDecimal.ZERO.setScale(2)
        var earlyRefund = BigDecimal.ZERO.setScale(2)
        var deduction = BigDecimal.ZERO.setScale(2)
        val notes = mutableListOf<String>()

        // 1) 超时费（门店责任导致的滞留不收超时费）
        if (mode != CheckoutMode.STORE_FAULT && checkoutAt > booking.effectiveEndTime) {
            val overMin = Duration.between(booking.effectiveEndTime, checkoutAt).toMinutes()
            if (overMin > 0) {
                val raw = extensionFee(seat, booking.store, overMin)
                overtime = raw.multiply(BigDecimal.valueOf(booking.store.overtimeRate)).scale2()
                charge(booking, ChargeType.OVERTIME_FEE, overtime, "超时 ${overMin} 分钟")
                notes.add("超时 $overMin 分钟，加收 $overtime 元")
            }
        }

        // 2) 储物柜/设备扣赔（仅在非门店责任时由用户承担）
        if (mode != CheckoutMode.STORE_FAULT) {
            if (!lockerReturned) {
                deduction = deduction.add(money(30.0)); notes.add("储物柜未清空归还，扣赔 30 元")
            }
            if (!seatEquipmentOk) {
                deduction = deduction.add(money(50.0)); notes.add("座位设备损坏，扣赔 50 元")
            }
            if (deduction.signum() > 0) {
                booking.depositDeducted = booking.depositDeducted.add(deduction)
                charge(booking, ChargeType.DEPOSIT_DEDUCT, deduction, notes.last())
            }
        }
        booking.lockerReturned = lockerReturned || mode == CheckoutMode.STORE_FAULT
        booking.seatEquipmentOk = seatEquipmentOk || mode == CheckoutMode.STORE_FAULT

        // 3) 提前离场 / 门店责任：按剩余时长退使用费
        val plannedEnd = booking.effectiveEndTime
        if (checkoutAt < plannedEnd && booking.type != BookingType.MONTHLY && booking.actualCheckIn != null) {
            val total = Duration.between(booking.actualCheckIn, plannedEnd).toMinutes().coerceAtLeast(1)
            val remain = Duration.between(checkoutAt, plannedEnd).toMinutes().coerceAtLeast(0)
            if (remain > 0) {
                val rate = when (mode) {
                    CheckoutMode.STORE_FAULT -> 1.0
                    CheckoutMode.EARLY_LEAVE -> booking.store.earlyLeaveRefundRate
                    CheckoutMode.NORMAL -> 0.0
                }
                val raw = booking.price.multiply(BigDecimal.valueOf(remain.toDouble() / total))
                    .multiply(BigDecimal.valueOf(rate)).scale2()
                if (raw.signum() > 0) {
                    val reason = if (mode == CheckoutMode.STORE_FAULT) RefundReason.STORE_FAULT else RefundReason.EARLY_LEAVE
                    earlyRefund = capRefund(booking, raw)
                    if (earlyRefund.signum() > 0) {
                        refund(
                            booking, reason, earlyRefund,
                            incidentNo = incidentNo,
                            note = "剩余 $remain 分钟，比例 $rate"
                        )
                        notes.add("提前离场退使用费 $earlyRefund 元")
                    }
                }
            }
        }

        // 4) 押金余额处理。
        if (booking.type == BookingType.MONTHLY) {
            // 包月日常离场权益仍有效：押金保留至权益结束；若本次离场已到期，则结清押金。
            val expired = !checkoutAt.isBefore(booking.effectiveEndTime)
            var depositBack = BigDecimal.ZERO.setScale(2)
            if (expired) {
                depositBack = booking.deposit.subtract(booking.depositDeducted).max(BigDecimal.ZERO.setScale(2))
                if (depositBack.signum() > 0) {
                    notes.add("包月到期，退押金 $depositBack 元")
                    refund(booking, RefundReason.DEPOSIT_RETURN, depositBack, depositRefund = true, note = "包月到期押金退回")
                }
            }
            return CheckoutSettlement(
                overtime, earlyRefund, deduction, depositBack,
                notes.joinToString("；").ifBlank { if (expired) "包月权益到期" else "包月权益持续有效，押金保留" }
            )
        }
        val depositBack = booking.deposit.subtract(booking.depositDeducted).max(BigDecimal.ZERO.setScale(2))
        if (depositBack.signum() > 0) {
            val depositReason = when (mode) {
                CheckoutMode.STORE_FAULT -> RefundReason.STORE_FAULT
                CheckoutMode.EARLY_LEAVE -> RefundReason.EARLY_LEAVE
                CheckoutMode.NORMAL -> RefundReason.DEPOSIT_RETURN
            }
            refund(booking, depositReason, depositBack, depositRefund = true, incidentNo = incidentNo, note = "押金余额原路退回")
        }

        return CheckoutSettlement(overtime, earlyRefund, deduction, depositBack, notes.joinToString("；"))
    }
}

private fun BigDecimal.scale2(): BigDecimal = setScale(2, RoundingMode.HALF_UP)

data class CheckoutSettlement(
    val overtimeFee: BigDecimal,
    val earlyRefund: BigDecimal,
    val depositDeduction: BigDecimal,
    val depositReturned: BigDecimal,
    val note: String
)
