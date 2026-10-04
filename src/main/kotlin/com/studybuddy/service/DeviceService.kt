package com.studybuddy.service

import com.studybuddy.api.DeviceEventRequest
import com.studybuddy.api.DeviceEventVo
import com.studybuddy.api.SensorReadingRequest
import com.studybuddy.api.SensorReadingVo
import com.studybuddy.domain.*
import com.studybuddy.infra.Locks
import com.studybuddy.infra.TimeService
import com.studybuddy.infra.Tx
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 设备与停电事件。
 * 停电（POWER 故障）期间：
 * - 门店置 OUTAGE，门禁拒绝新进场，允许在场用户离场；
 * - 所有在场/待进场订单记录 OUTAGE_START，列入设备事件影响范围，供客服批量核账与补偿；
 * - 恢复后记录 OUTAGE_END，退款/补偿由工单核定（区分门店责任与供应商责任）。
 */
@Service
class DeviceService(
    private val deviceRepo: DeviceEventRepository,
    private val storeRepo: StoreRepository,
    private val reservationRepo: ReservationRepository,
    private val sensorRepo: SensorReadingRepository,
    private val timeline: TimelineService,
    private val tx: Tx,
    private val locks: Locks,
    private val time: TimeService
) {
    fun report(storeId: Long, req: DeviceEventRequest, cctv: String? = null): DeviceEvent =
        tx.run {
            locks.withLock(Locks.storeKey(storeId)) {
                val store = storeRepo.findById(storeId).orElseThrow { NotFoundException("门店", storeId) }
                val now = time.now()

                // 同一设备存在未恢复事件时不重复登记
                val open = when {
                    req.seatId != null -> deviceRepo.findTopBySeatIdAndDeviceTypeOrderByCreatedAtDesc(req.seatId, req.deviceType)
                    else -> deviceRepo.findTopByStoreIdAndDeviceTypeOrderByCreatedAtDesc(storeId, req.deviceType)
                }
                if (open != null && open.eventType == DeviceEventType.REPORTED) {
                    throw ConflictException("该设备已有未恢复故障事件 #${open.id}")
                }

                val affected = if (req.deviceType == DeviceType.POWER) {
                    reservationRepo.findByStoreIdAndStatusIn(
                        storeId,
                        listOf(ReservationStatus.IN_PROGRESS, ReservationStatus.BOOKED, ReservationStatus.DISPUTED)
                    )
                } else emptyList()

                val ev = deviceRepo.save(
                    DeviceEvent(
                        storeId = storeId,
                        seatId = req.seatId,
                        deviceType = req.deviceType,
                        eventType = DeviceEventType.REPORTED,
                        at = now,
                        affectedReservationIds = affected.mapNotNull { it.id }.toMutableList(),
                        responsible = req.responsible,
                        note = req.note,
                        cctvEventId = cctv ?: req.cctvEventId
                    )
                )

                when (req.deviceType) {
                    DeviceType.POWER -> {
                        store.status = StoreStatus.OUTAGE
                        store.outageSince = now
                        affected.forEach { r ->
                            timeline.add(TimelineType.OUTAGE_START, r, ticketId = null,
                                detail = "门店停电，事件#${ev.id}，${req.note ?: ""}")
                        }
                    }
                    else -> {
                        affected.forEach { _ -> }
                        timeline.add(TimelineType.DEVICE_FAULT_REPORTED, storeId = storeId, seatId = req.seatId,
                            detail = "${req.deviceType} 故障上报 事件#${ev.id} 责任=${req.responsible} ${req.note ?: ""}")
                    }
                }
                ev
            }
        }

    fun resolve(storeId: Long, deviceEventId: Long, note: String?): DeviceEvent = tx.run {
        locks.withLock(Locks.storeKey(storeId)) {
            val ev = deviceRepo.findById(deviceEventId).orElseThrow { NotFoundException("设备事件", deviceEventId) }
            if (ev.eventType == DeviceEventType.RESOLVED) return@withLock ev
            val store = storeRepo.findById(storeId).orElseThrow { NotFoundException("门店", storeId) }
            val now = time.now()

            val resolved = deviceRepo.save(
                DeviceEvent(
                    storeId = ev.storeId, seatId = ev.seatId,
                    deviceType = ev.deviceType, eventType = DeviceEventType.RESOLVED, at = now,
                    affectedReservationIds = ev.affectedReservationIds.toMutableList(),
                    responsible = ev.responsible, note = note
                )
            )
            ev.affectedReservationIds.forEach { rid ->
                reservationRepo.findById(rid).ifPresent { r ->
                    timeline.add(TimelineType.OUTAGE_END, r,
                        detail = "设备/供电恢复，事件#${resolved.id}，退款补偿请走争议工单")
                }
            }
            timeline.add(TimelineType.DEVICE_RESOLVED, storeId = storeId, seatId = ev.seatId,
                detail = "${ev.deviceType} 恢复 事件#${resolved.id} ${note ?: ""}")
            if (ev.deviceType == DeviceType.POWER) {
                store.status = StoreStatus.OPEN
                store.outageSince = null
            }
            resolved
        }
    }

    @Transactional
    fun listStoreEvents(storeId: Long): List<DeviceEventVo> =
        deviceRepo.findByStoreIdOrderByCreatedAtDesc(storeId).map(::toVo)

    fun ingest(seatId: Long, req: SensorReadingRequest): SensorReading {
        val reading = SensorReading(seatId = seatId, sensorType = req.sensorType, value = req.value, reportedAt = time.now())
        return sensorRepo.save(reading)
    }

    @Transactional
    fun listSeatReadings(seatId: Long): List<SensorReadingVo> =
        sensorRepo.findBySeatIdOrderByReportedAtDesc(seatId).map {
            SensorReadingVo(it.id!!, it.seatId, it.sensorType, it.value, it.reportedAt)
        }

    fun toVo(ev: DeviceEvent): DeviceEventVo = DeviceEventVo(
        id = ev.id!!, storeId = ev.storeId, seatId = ev.seatId, deviceType = ev.deviceType,
        eventType = ev.eventType, at = ev.at, responsible = ev.responsible, note = ev.note,
        cctvEventId = ev.cctvEventId,
        affectedReservationIds = ev.affectedReservationIds.toList(), ticketId = ev.ticketId
    )
}
