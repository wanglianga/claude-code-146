package com.studybuddy.api

import com.studybuddy.domain.*

object Mappers {
    fun store(s: Store) = StoreVo(s.id!!, s.name, s.address, s.status, s.unattended, s.outageSince)

    fun seat(s: Seat) = SeatVo(
        s.id!!, s.store.id!!, s.seatCode, s.seatType, s.zone, s.status,
        s.hourlyPrice, s.overtimePerMinute, s.hasLocker, s.occupiedByReservationId
    )

    fun user(u: AppUser) = UserVo(u.id!!, u.name, u.phone, u.idVerified, u.idCardMasked, u.blacklisted)

    fun reservation(r: Reservation) = ReservationVo(
        id = r.id!!, orderNo = r.orderNo, userId = r.user.id!!, storeId = r.store.id!!,
        seatId = r.seat?.id, reservationType = r.reservationType,
        startTime = r.startTime, endTime = r.endTime, scheduledEndTime = r.scheduledEndTime,
        status = r.status, price = r.price, deposit = r.deposit, depositStatus = r.depositStatus,
        needLocker = r.needLocker, silentRuleAccepted = r.silentRuleAccepted, paid = r.paid,
        checkInAt = r.checkInAt, checkOutAt = r.checkOutAt, lateMarkedAt = r.lateMarkedAt,
        overtimeMinutes = r.overtimeMinutes, overtimeFee = r.overtimeFee,
        postponeCount = r.postponeCount, encroachedByReservationId = r.encroachedByReservationId,
        movedFromSeatId = r.movedFromSeatId, equipmentCheck = r.equipmentCheck,
        lockerCheck = r.lockerCheck, damageCharge = r.damageCharge,
        refundedAmount = r.refundedAmount, compensatedAmount = r.compensatedAmount,
        ticketId = r.ticketId, remark = r.remark, createdAt = r.createdAt
    )

    fun settlement(d: com.studybuddy.service.ReservationService.SettlementData) = SettlementVo(
        reservationId = d.reservation.id!!,
        status = d.reservation.status,
        overtimeMinutes = d.reservation.overtimeMinutes,
        overtimeFee = d.reservation.overtimeFee,
        damageCharge = d.reservation.damageCharge,
        depositStatus = d.reservation.depositStatus,
        depositReturned = d.depositReturned,
        depositDeducted = d.depositDeducted,
        refundedAmount = d.reservation.refundedAmount,
        seatReleased = d.seatReleased
    )
}
