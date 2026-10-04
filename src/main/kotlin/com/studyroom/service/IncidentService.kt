package com.studyroom.service

import com.studyroom.domain.*
import com.studyroom.repo.*
import com.studyroom.support.NotFoundException
import com.studyroom.support.SeatLockManager
import com.studyroom.support.TimeProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class IncidentService(
    private val incidentRepo: IncidentRepository,
    private val storeRepo: StoreRepository,
    private val seatRepo: SeatRepository,
    private val sensorRepo: SeatSensorRepository,
    private val bookingRepo: BookingRepository,
    private val billing: BillingService,
    private val seatLocks: SeatLockManager,
    private val timeline: TimelineService,
    private val clock: TimeProvider
) {
    @Transactional
    fun open(storeId: Long, type: IncidentType, description: String, seatId: Long? = null): Incident {
        val store = storeRepo.findById(storeId).orElseThrow { NotFoundException("门店不存在") }
        val seat = seatId?.let { seatRepo.findById(it).orElseThrow { NotFoundException("座位不存在") } }
        val monitor = monitorEventNo(store, null, AccessResult.DENIED_DOOR_FAULT, clock.now())
        val inc = incidentRepo.save(
            Incident(
                incidentNo = "TMP", store = store, seat = seat, type = type,
                description = description, monitorEventNo = monitor, openedAt = clock.now()
            )
        )
        inc.incidentNo = BizNoGenerator.incident(inc.id!!)
        seat?.let {
            it.status = SeatStatus.MAINTENANCE
            timeline.record("SEAT_MAINTENANCE", store.id, null, "STAFF",
                "座位 ${it.code} 因 ${type.name} 暂停预约")
        }
        timeline.record("INCIDENT_OPENED", store.id, null, "STAFF",
            "${inc.incidentNo} ${type.name}：$description 监控编号 $monitor")
        return inc
    }

    @Transactional
    fun resolve(incidentNo: String, resolution: String): Incident {
        val inc = incidentRepo.findByIncidentNo(incidentNo) ?: throw NotFoundException("事件不存在")
        inc.status = IncidentStatus.RESOLVED
        inc.resolvedAt = clock.now()
        inc.resolution = resolution
        inc.seat?.let { if (it.status == SeatStatus.MAINTENANCE) it.status = SeatStatus.AVAILABLE }
        timeline.record("INCIDENT_RESOLVED", inc.store.id, null, "STAFF",
            "$incidentNo：$resolution")
        return inc
    }

    /**
     * 设置门店临时停电。
     * 停电开始时对所有在场订单按门店责任强制结算（退剩余使用费+押金、释放座位），
     * 保证“门店责任 → 计费 → 座位状态”一致；停电期间门禁拒绝一切新进入。
     */
    @Transactional
    fun setBlackout(storeId: Long, blackout: Boolean, reason: String = "门店临时停电"): List<String> {
        val store = storeRepo.findById(storeId).orElseThrow { NotFoundException("门店不存在") }
        store.blackout = blackout
        timeline.record(if (blackout) "STORE_BLACKOUT_ON" else "STORE_BLACKOUT_OFF", store.id, null, "STAFF", reason)

        val affected = mutableListOf<String>()
        if (blackout) {
            val incident = open(storeId, IncidentType.POWER_OUTAGE, reason)
            val now = clock.now()
            val inStore = bookingRepo.findAll().filter {
                it.store.id == storeId && it.status == BookingStatus.IN_PROGRESS
                // 包月权益不因停电终止整月有效期：会员可经离场闸机正常离场（停电只拦进场），
                // 权益保留，电力恢复后可再次进入
                && it.type != BookingType.MONTHLY
            }
            for (b in inStore) {
                val seat = b.seat ?: continue
                val sid = seat.id!!
                seatLocks.withSeatLock(sid) {
                    val settlement = billing.settleCheckout(b, now, true, true, CheckoutMode.STORE_FAULT, incident.incidentNo)
                    b.actualCheckOut = now
                    b.relatedIncidentNo = incident.incidentNo
                    seat.status = SeatStatus.AVAILABLE
                    seatLocks.release(sid, b.bookingNo)
                    sensorRepo.save(sensorRepo.findBySeatId(sid)?.apply {
                        occupied = false; occupiedByBookingNo = null; updatedAt = now
                    } ?: SeatSensorReading(seat = seat, occupied = false, occupiedByBookingNo = null, updatedAt = now))
                    b.status = BookingStatus.COMPLETED
                    timeline.record("BLACKOUT_FORCE_CHECKOUT", store.id, b.bookingNo, "SYSTEM",
                        "停电强制清场：${settlement.note}")
                    affected.add(b.bookingNo)
                }
            }
        }
        return affected
    }
}
