package com.studybuddy.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.time.LocalDate

interface StoreRepository : JpaRepository<Store, Long> {
    fun findByStatus(status: StoreStatus): List<Store>
}

interface SeatRepository : JpaRepository<Seat, Long> {
    fun findByStoreId(storeId: Long): List<Seat>
    fun findByStoreIdAndStatus(storeId: Long, status: SeatStatus): List<Seat>
    fun findByOccupiedByReservationId(reservationId: Long): List<Seat>
}

interface AppUserRepository : JpaRepository<AppUser, Long> {
    fun findByPhone(phone: String): AppUser?
}

interface ReservationRepository : JpaRepository<Reservation, Long> {
    fun findByOrderNo(orderNo: String): Reservation?
    fun findByIdempotencyKey(key: String): Reservation?
    fun findByUserId(userId: Long): List<Reservation>
    fun findByStoreId(storeId: Long): List<Reservation>

    /** 与给定时段重叠且占用座位的预约（BOOKED/IN_PROGRESS/顺延中） */
    @Query("""
        select r from Reservation r
        where r.seat.id = :seatId
          and r.status in :statuses
          and r.startTime < :endTime
          and r.scheduledEndTime > :startTime
        order by r.startTime
    """)
    fun findOverlapping(
        @Param("seatId") seatId: Long,
        @Param("startTime") startTime: Long,
        @Param("endTime") endTime: Long,
        @Param("statuses") statuses: Collection<ReservationStatus>
    ): List<Reservation>

    /** 某座位在某预约之后的下一位（用于顺延侵占判定） */
    @Query("""
        select r from Reservation r
        where r.seat.id = :seatId
          and r.status in :statuses
          and r.startTime >= :fromTime
          and r.id <> :excludeId
        order by r.startTime asc
    """)
    fun findNextOnSeat(
        @Param("seatId") seatId: Long,
        @Param("fromTime") fromTime: Long,
        @Param("excludeId") excludeId: Long,
        @Param("statuses") statuses: Collection<ReservationStatus>
    ): List<Reservation>

    @Query("""
        select r from Reservation r
        where r.status in :statuses and r.startTime <= :now
    """)
    fun findStartedButNotCheckedIn(
        @Param("statuses") statuses: Collection<ReservationStatus>,
        @Param("now") now: Long
    ): List<Reservation>

    @Query("""
        select r from Reservation r
        where r.status = :status and r.scheduledEndTime < :now
    """)
    fun findOvertime(@Param("status") status: ReservationStatus, @Param("now") now: Long): List<Reservation>

    fun findByStoreIdAndStatusIn(storeId: Long, statuses: Collection<ReservationStatus>): List<Reservation>

    @Query("select r.seat.id from Reservation r where r.id = :id")
    fun findSeatIdById(@Param("id") id: Long): Long?
}

interface TimelineEventRepository : JpaRepository<TimelineEvent, Long> {
    fun findByReservationIdOrderByAtAsc(reservationId: Long): List<TimelineEvent>
    fun findByStoreIdOrderByAtAsc(storeId: Long): List<TimelineEvent>
    fun findByTicketIdOrderByAtAsc(ticketId: Long): List<TimelineEvent>

    fun findFirstByReservationIdAndTypeAndActorTypeOrderByAtDesc(
        reservationId: Long, type: TimelineType, actorType: ActorType
    ): TimelineEvent?
}

interface GateLogRepository : JpaRepository<GateLog, Long> {
    fun findByReservationIdOrderByOccurredAtAsc(reservationId: Long): List<GateLog>
    fun findByStoreIdOrderByOccurredAtDesc(storeId: Long): List<GateLog>
    fun findByUserIdOrderByOccurredAtDesc(userId: Long): List<GateLog>
    fun findByResultAndOccurredAtBetween(result: GateResult, from: Instant, to: Instant): List<GateLog>
}

interface DeviceEventRepository : JpaRepository<DeviceEvent, Long> {
    fun findByStoreIdOrderByCreatedAtDesc(storeId: Long): List<DeviceEvent>
    fun findBySeatIdOrderByCreatedAtDesc(seatId: Long): List<DeviceEvent>
    fun findByEventTypeAndDeviceType(eventType: DeviceEventType, deviceType: DeviceType): List<DeviceEvent>
    fun findTopByStoreIdAndDeviceTypeOrderByCreatedAtDesc(storeId: Long, deviceType: DeviceType): DeviceEvent?
    fun findTopBySeatIdAndDeviceTypeOrderByCreatedAtDesc(seatId: Long, deviceType: DeviceType): DeviceEvent?
}

interface SensorReadingRepository : JpaRepository<SensorReading, Long> {
    fun findBySeatIdOrderByReportedAtDesc(seatId: Long): List<SensorReading>
    fun findFirstBySeatIdAndSensorTypeOrderByReportedAtDesc(seatId: Long, sensorType: SensorType): SensorReading?
}

interface AppealRepository : JpaRepository<Appeal, Long> {
    fun findByTicketId(ticketId: Long): List<Appeal>
    fun findByReservationId(reservationId: Long): List<Appeal>
}

interface ComplaintRepository : JpaRepository<Complaint, Long> {
    fun findByStoreIdOrderByCreatedAtDesc(storeId: Long): List<Complaint>
    fun findByReservationId(reservationId: Long): List<Complaint>
    fun countByStoreIdAndCreatedAtBetween(storeId: Long, from: Instant, to: Instant): Long
    fun countByStoreIdAndReasonAndCreatedAtBetween(storeId: Long, reason: ComplaintReason, from: Instant, to: Instant): Long
}

interface SupportTicketRepository : JpaRepository<SupportTicket, Long> {
    fun findByTicketNo(ticketNo: String): SupportTicket?
    fun findByStoreIdOrderByCreatedAtDesc(storeId: Long): List<SupportTicket>
    fun findByReservationId(reservationId: Long): List<SupportTicket>
    fun countByStoreIdAndRootCauseAndCreatedAtBetween(storeId: Long, rootCause: RootCause, from: Instant, to: Instant): Long
    fun findByStatus(status: TicketStatus): List<SupportTicket>
}

interface BillingEntryRepository : JpaRepository<BillingEntry, Long> {
    fun findByReservationIdOrderByAtAsc(reservationId: Long): List<BillingEntry>
}

interface MonthlyPassRepository : JpaRepository<MonthlyPass, Long> {
    fun findByUserId(userId: Long): List<MonthlyPass>
    fun findByStoreId(storeId: Long): List<MonthlyPass>
    fun findByFixedSeatId(seatId: Long): List<MonthlyPass>

    @Query("select p.fixedSeat.id from MonthlyPass p where p.id = :id")
    fun findFixedSeatId(@Param("id") id: Long): Long?

    @Query("""
        select p from MonthlyPass p
        where p.storeId = :storeId and p.status = :status
          and :day between p.validFrom and p.validTo
    """)
    fun findActiveInStore(
        @Param("storeId") storeId: Long,
        @Param("status") status: PassStatus,
        @Param("day") day: LocalDate
    ): List<MonthlyPass>

    @Query("""
        select p from MonthlyPass p
        where p.user.id = :userId and p.storeId = :storeId and p.status = :status
          and :day between p.validFrom and p.validTo
    """)
    fun findActiveForUser(
        @Param("userId") userId: Long,
        @Param("storeId") storeId: Long,
        @Param("status") status: PassStatus,
        @Param("day") day: LocalDate
    ): List<MonthlyPass>

    /** 固定座包月在某日的权益（用于固定座被普通预约挤占的冲突检测） */
    @Query("""
        select p from MonthlyPass p
        where p.fixedSeat.id = :seatId and p.mode = :mode and p.status = :status
          and :day between p.validFrom and p.validTo
    """)
    fun findActiveFixedOnSeat(
        @Param("seatId") seatId: Long,
        @Param("mode") mode: PassMode,
        @Param("status") status: PassStatus,
        @Param("day") day: LocalDate
    ): List<MonthlyPass>
}
