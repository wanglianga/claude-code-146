package com.studyroom.service

import com.studyroom.domain.SeatSensorReading
import com.studyroom.domain.SeatStatus
import com.studyroom.repo.SeatRepository
import com.studyroom.repo.SeatSensorRepository
import com.studyroom.support.NotFoundException
import com.studyroom.support.TimeProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 座位传感器/店员巡检上报：无人值守门店发现“座位被占”等异常的入口 */
@Service
class SensorService(
    private val seatRepo: SeatRepository,
    private val sensorRepo: SeatSensorRepository,
    private val timeline: TimelineService,
    private val clock: TimeProvider
) {
    @Transactional
    fun report(storeId: Long, seatId: Long, occupied: Boolean, occupiedByBookingNo: String? = null): SeatSensorReading {
        val seat = seatRepo.findByStoreIdAndId(storeId, seatId) ?: throw NotFoundException("座位不存在")
        val now = clock.now()
        val reading = sensorRepo.findBySeatId(seatId)?.apply {
            this.occupied = occupied
            this.occupiedByBookingNo = if (occupied) occupiedByBookingNo else null
            this.updatedAt = now
        } ?: SeatSensorReading(
            seat = seat,
            occupied = occupied,
            occupiedByBookingNo = if (occupied) occupiedByBookingNo else null,
            updatedAt = now
        )
        if (occupied && occupiedByBookingNo != null && seat.status != SeatStatus.OCCUPIED) {
            seat.status = SeatStatus.OCCUPIED
        }
        val saved = sensorRepo.save(reading)
        timeline.record(
            "SENSOR_REPORT", storeId, occupiedByBookingNo, "SENSOR",
            "座位 ${seat.code} occupied=$occupied"
        )
        return saved
    }
}
