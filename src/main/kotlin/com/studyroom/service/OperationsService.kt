package com.studyroom.service

import com.studyroom.domain.*
import com.studyroom.repo.*
import com.studyroom.support.BizException
import com.studyroom.support.NotFoundException
import com.studyroom.support.TimeProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant

data class StoreMetrics(
    val storeId: Long,
    val from: Instant,
    val to: Instant,
    /** 营业座位数 */
    val activeSeats: Int,
    /** 有效进入订单数 */
    val checkedInBookings: Int,
    /** 含延时的订单数 */
    val extendedBookings: Int,
    /** 投诉数 */
    val complaints: Long,
    /** 设备故障事件数（含停电/空调/门禁等） */
    val deviceIncidents: Long,
    /** 上座率（实际占用座位分钟 / 可用座位分钟） */
    val occupancyRate: Double,
    /** 延时率（含延时订单 / 进入订单） */
    val extensionRate: Double,
    /** 投诉率（投诉数 / 进入订单） */
    val complaintRate: Double,
    /** 争议按根因分布 */
    val disputesByCause: Map<String, Long>
)

data class PriceAdjustment(val priceMultiplier: Double, val note: String? = null)
data class SeatLayoutAdjustment(val zone: String? = null, val hourlyPrice: Double? = null, val active: Boolean? = null)
data class PlanRuleAdjustment(
    val price: Double? = null,
    val active: Boolean? = null,
    val fixedSeat: Boolean? = null,
    val days: Int? = null
)

@Service
class OperationsService(
    private val storeRepo: StoreRepository,
    private val seatRepo: SeatRepository,
    private val bookingRepo: BookingRepository,
    private val complaintRepo: ComplaintRepository,
    private val incidentRepo: IncidentRepository,
    private val ticketRepo: SupportTicketRepository,
    private val planRepo: MembershipPlanRepository,
    private val timeline: TimelineService,
    private val clock: TimeProvider
) {
    @Transactional(readOnly = true)
    fun metrics(storeId: Long, from: Instant, to: Instant): StoreMetrics {
        storeRepo.findById(storeId).orElseThrow { NotFoundException("门店不存在") }
        if (!to.isAfter(from)) throw BizException("BAD_RANGE", "结束时间必须晚于开始时间")

        val seats = seatRepo.findByStoreIdAndActiveTrue(storeId)
        val windowMinutes = Duration.between(from, to).toMinutes().coerceAtLeast(1)
        val availableSeatMinutes = seats.size * windowMinutes

        val bookings = bookingRepo.findByStoreAndTimeRange(storeId, from, to)
        val checkedIn = bookings.filter { it.actualCheckIn != null }

        val usedMinutes = checkedIn.sumOf { b ->
            val start = maxOf(b.actualCheckIn!!, from)
            val endRaw = b.actualCheckOut ?: b.effectiveEndTime
            val end = minOf(endRaw, to)
            if (end.isAfter(start)) Duration.between(start, end).toMinutes() else 0L
        }
        val extendedCount = checkedIn.count { it.extended }
        val complaints = complaintRepo.countByStoreIdAndCreatedAtBetween(storeId, from, to)
        val incidents = incidentRepo.countByStoreIdAndOpenedAtBetween(storeId, from, to)

        val causeCounts = DisputeCause.entries.associateWith { cause ->
            ticketRepo.countByStoreIdAndCauseAndCreatedAtBetween(storeId, cause, from, to)
        }.filterValues { it > 0 }

        return StoreMetrics(
            storeId = storeId, from = from, to = to,
            activeSeats = seats.size,
            checkedInBookings = checkedIn.size,
            extendedBookings = extendedCount,
            complaints = complaints,
            deviceIncidents = incidents,
            occupancyRate = pct(usedMinutes.toDouble(), availableSeatMinutes.toDouble()),
            extensionRate = pct(extendedCount.toDouble(), checkedIn.size.toDouble()),
            complaintRate = pct(complaints.toDouble(), checkedIn.size.toDouble()),
            disputesByCause = causeCounts.mapKeys { it.key.name }
        )
    }

    /** 运营按上座率/故障率调整价格倍率（立即影响新预约价格） */
    @Transactional
    fun adjustPrice(storeId: Long, adj: PriceAdjustment): Store {
        val store = storeRepo.findById(storeId).orElseThrow { NotFoundException("门店不存在") }
        if (adj.priceMultiplier <= 0 || adj.priceMultiplier > 3) {
            throw BizException("BAD_MULTIPLIER", "价格倍率需在 0~3 之间")
        }
        val old = store.priceMultiplier
        store.priceMultiplier = BigDecimal(adj.priceMultiplier).setScale(2, RoundingMode.HALF_UP).toDouble()
        timeline.record("PRICE_ADJUSTED", store.id, null, "ADMIN",
            "倍率 $old → ${store.priceMultiplier}${adj.note?.let { "：$it" } ?: ""}")
        return store
    }

    /** 运营调整座位布局：分区、定价、启用/停用 */
    @Transactional
    fun adjustSeat(storeId: Long, seatId: Long, adj: SeatLayoutAdjustment): Seat {
        val seat = seatRepo.findByStoreIdAndId(storeId, seatId) ?: throw NotFoundException("座位不存在")
        adj.zone?.let { seat.zone = it }
        adj.hourlyPrice?.let {
            if (it <= 0) throw BizException("BAD_PRICE", "价格必须大于 0")
            seat.hourlyPrice = BigDecimal(it).setScale(2, RoundingMode.HALF_UP)
        }
        adj.active?.let { seat.active = it }
        timeline.record("SEAT_LAYOUT_ADJUSTED", storeId, null, "ADMIN",
            "座位 ${seat.code}：zone=${seat.zone} 价格=${seat.hourlyPrice} 启用=${seat.active}")
        return seat
    }

    /** 运营调整包月规则 */
    @Transactional
    fun adjustPlan(planId: Long, adj: PlanRuleAdjustment): MembershipPlan {
        val plan = planRepo.findById(planId).orElseThrow { NotFoundException("套餐不存在") }
        adj.price?.let {
            if (it < 0) throw BizException("BAD_PRICE", "价格不能为负")
            plan.price = BigDecimal(it).setScale(2, RoundingMode.HALF_UP)
        }
        adj.active?.let { plan.active = it }
        adj.fixedSeat?.let { plan.fixedSeat = it }
        adj.days?.let { d -> if (d <= 0) throw BizException("BAD_DAYS", "天数必须大于 0"); plan.days = d }
        timeline.record("PLAN_RULE_ADJUSTED", plan.store.id, null, "ADMIN",
            "套餐 ${plan.name}：价格=${plan.price} 固定座=${plan.fixedSeat} 天数=${plan.days} 启用=${plan.active}")
        return plan
    }

    private fun pct(n: Double, d: Double): Double =
        if (d <= 0) 0.0 else BigDecimal(n / d).setScale(4, RoundingMode.HALF_UP).toDouble()
}
