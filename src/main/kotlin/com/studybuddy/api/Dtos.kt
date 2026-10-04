package com.studybuddy.api

import com.studybuddy.domain.*
import jakarta.validation.constraints.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

// ---------------- 基础资料 ----------------

data class CreateStoreRequest(
    @field:NotBlank val name: String,
    val address: String? = null,
    val unattended: Boolean = false
)

data class StoreVo(
    val id: Long, val name: String, val address: String?,
    val status: StoreStatus, val unattended: Boolean, val outageSince: Instant?
)

data class CreateSeatRequest(
    @field:NotBlank val seatCode: String,
    val seatType: SeatType,
    val zone: String = "静音区",
    @field:Positive val hourlyPrice: BigDecimal = BigDecimal("10.00"),
    @field:PositiveOrZero val overtimePerMinute: BigDecimal = BigDecimal("0.50"),
    val hasLocker: Boolean = false
)

data class SeatVo(
    val id: Long, val storeId: Long, val seatCode: String, val seatType: SeatType,
    val zone: String, val status: SeatStatus, val hourlyPrice: BigDecimal,
    val overtimePerMinute: BigDecimal, val hasLocker: Boolean,
    val occupiedByReservationId: Long?
)

/** 运营调价（上座率/设备表现驱动） */
data class AdjustSeatPriceRequest(
    @field:NotNull @field:Positive val hourlyPrice: BigDecimal,
    @field:NotNull @field:PositiveOrZero val overtimePerMinute: BigDecimal
)

data class CreateUserRequest(
    @field:NotBlank val name: String,
    @field:NotBlank @field:Pattern(regexp = "1\\d{10}", message = "手机号格式不正确") val phone: String,
    val idCardMasked: String? = null
)

data class RealNameRequest(@field:NotBlank val idCardMasked: String)

data class UserVo(
    val id: Long, val name: String, val phone: String,
    val idVerified: Boolean, val idCardMasked: String?, val blacklisted: Boolean
)

// ---------------- 预约 ----------------

data class CreateReservationRequest(
    val userId: Long,
    val storeId: Long,
    val seatId: Long? = null,
    val reservationType: ReservationType = ReservationType.SINGLE_SEAT,
    /** epoch 毫秒 */
    val startTime: Long,
    val endTime: Long,
    val needLocker: Boolean = false,
    val silentRuleAccepted: Boolean = false,
    /** 包月权益 ID（浮动座用包月下单时传入） */
    val passId: Long? = null,
    val idempotencyKey: String? = null
)

data class PayRequest(val idempotencyKey: String? = null)

data class ReservationVo(
    val id: Long, val orderNo: String, val userId: Long, val storeId: Long,
    val seatId: Long?, val reservationType: ReservationType,
    val startTime: Long, val endTime: Long, val scheduledEndTime: Long,
    val status: ReservationStatus, val price: BigDecimal, val deposit: BigDecimal,
    val depositStatus: DepositStatus, val needLocker: Boolean,
    val silentRuleAccepted: Boolean, val paid: Boolean,
    val checkInAt: Instant?, val checkOutAt: Instant?,
    val lateMarkedAt: Instant?, val overtimeMinutes: Int, val overtimeFee: BigDecimal,
    val postponeCount: Int, val encroachedByReservationId: Long?,
    val movedFromSeatId: Long?, val equipmentCheck: EquipmentCheck,
    val lockerCheck: LockerCheck, val damageCharge: BigDecimal,
    val refundedAmount: BigDecimal, val compensatedAmount: BigDecimal,
    val ticketId: Long?, val remark: String?, val createdAt: Instant
)

// ---------------- 门禁 ----------------

data class GateAttemptRequest(
    val reservationId: Long? = null,
    val orderNo: String? = null,
    val userId: Long? = null,
    val direction: GateDirection,
    val deviceSn: String? = null
)

data class GateAttemptResponse(
    val result: GateResult,
    val reason: GateReason,
    val gateLogId: Long,
    val cctvEventId: String,
    val reservationId: Long?,
    val at: Instant
)

// ---------------- 迟到/顺延/换座 ----------------

data class PostponeRequest(
    /** 顺延分钟数（从原开始时间延后） */
    @field:Min(1) val delayMinutes: Int
)

data class ExtendRequest(
    @field:Min(1) val minutes: Int
)

data class ReassignSeatRequest(
    @field:NotNull val targetSeatId: Long,
    val reason: String? = null
)

// ---------------- 离场检查/退费 ----------------

data class DepartureCheckRequest(
    val equipmentCheck: EquipmentCheck,
    val lockerCheck: LockerCheck = LockerCheck.UNCHECKED,
    @field:PositiveOrZero val damageCharge: BigDecimal = BigDecimal.ZERO,
    val note: String? = null
)

data class RefundRequest(val reason: String? = null)

data class SettlementVo(
    val reservationId: Long, val status: ReservationStatus,
    val overtimeMinutes: Int, val overtimeFee: BigDecimal,
    val damageCharge: BigDecimal, val depositStatus: DepositStatus,
    val depositReturned: BigDecimal, val depositDeducted: BigDecimal,
    val refundedAmount: BigDecimal, val seatReleased: Boolean
)

// ---------------- 设备/传感器 ----------------

data class DeviceEventRequest(
    @field:NotNull val deviceType: DeviceType,
    val seatId: Long? = null,
    val responsible: ResponsibleParty = ResponsibleParty.UNKNOWN,
    val note: String? = null,
    val cctvEventId: String? = null
)
data class DeviceEventVo(
    val id: Long, val storeId: Long, val seatId: Long?, val deviceType: DeviceType,
    val eventType: DeviceEventType, val at: Instant, val responsible: ResponsibleParty,
    val note: String?, val cctvEventId: String?, val affectedReservationIds: List<Long>,
    val ticketId: Long?
)

data class SensorReadingRequest(
    @field:NotNull val sensorType: SensorType,
    val value: Double
)

// ---------------- 投诉/申诉/工单 ----------------

data class ComplaintRequest(
    val reservationId: Long? = null,
    val userId: Long? = null,
    @field:NotNull val reason: ComplaintReason,
    val detail: String? = null,
    val againstUserId: Long? = null,
    val seatId: Long? = null,
    val cctvEventId: String? = null
)

data class AppealRequest(
    @field:NotNull val userId: Long,
    val reservationId: Long? = null,
    @field:NotBlank val content: String,
    val ticketId: Long? = null
)

data class CreateTicketRequest(
    @field:NotNull val storeId: Long,
    val reservationId: Long? = null,
    val userId: Long? = null,
    @field:NotNull val category: TicketCategory,
    @field:NotNull val rootCause: RootCause,
    val summary: String? = null,
    val complaintId: Long? = null
)

data class HandleTicketRequest(
    @field:NotBlank val staffId: String,
    val note: String? = null
)

data class ResolveTicketRequest(
    @field:NotBlank val staffId: String,
    @field:PositiveOrZero val refund: BigDecimal = BigDecimal.ZERO,
    @field:PositiveOrZero val compensation: BigDecimal = BigDecimal.ZERO,
    val note: String? = null,
    /** 同时释放座位/完结预约（如退费后解锁） */
    val releaseSeat: Boolean = false,
    val blacklistAgainstUser: Boolean = false
)

data class TicketVo(
    val id: Long, val ticketNo: String, val storeId: Long,
    val reservationId: Long?, val userId: Long?,
    val category: TicketCategory, val rootCause: RootCause, val status: TicketStatus,
    val summary: String?, val staffId: String?, val staffNote: String?,
    val resolvedRefund: BigDecimal, val resolvedCompensation: BigDecimal,
    val openedAt: Instant, val resolvedAt: Instant?, val closedAt: Instant?
)

data class EvidenceBundle(
    val ticket: TicketVo,
    val timeline: List<TimelineEventVo>,
    val gateLogs: List<GateLogVo>,
    val sensorReadings: List<SensorReadingVo>,
    val complaints: List<ComplaintVo>,
    val deviceEvents: List<DeviceEventVo>,
    val appeals: List<AppealVo>
)

data class TimelineEventVo(
    val id: Long, val type: TimelineType, val at: Instant,
    val reservationId: Long?, val storeId: Long?, val seatId: Long?,
    val actorType: ActorType?, val actorId: String?, val detail: String?
)

data class GateLogVo(
    val id: Long, val reservationId: Long?, val storeId: Long, val seatId: Long?,
    val userId: Long?, val direction: GateDirection, val result: GateResult,
    val reason: GateReason, val occurredAt: Instant,
    val deviceSn: String?, val cctvEventId: String?
)

data class SensorReadingVo(
    val id: Long, val seatId: Long, val sensorType: SensorType,
    val value: Double, val reportedAt: Instant
)

data class ComplaintVo(
    val id: Long, val storeId: Long, val reservationId: Long?, val userId: Long?,
    val seatId: Long?, val reason: ComplaintReason, val status: ComplaintStatus,
    val detail: String?, val againstUserId: Long?, val deviceEventId: Long?,
    val cctvEventId: String?, val handledByStaffId: String?,
    val handledAt: Instant?, val handleNote: String?, val ticketId: Long?
)

data class AppealVo(
    val id: Long, val ticketId: Long?, val userId: Long,
    val reservationId: Long?, val storeId: Long?, val content: String, val createdAt: Instant
)

// ---------------- 包月 ----------------

data class CreatePassRequest(
    val userId: Long,
    val storeId: Long,
    val mode: PassMode,
    val fixedSeatId: Long? = null,
    @field:NotNull val validFrom: LocalDate,
    @field:NotNull val validTo: LocalDate,
    @field:Positive val price: BigDecimal
)

data class PassVo(
    val id: Long, val passNo: String, val userId: Long, val storeId: Long,
    val fixedSeatId: Long?, val mode: PassMode,
    val validFrom: LocalDate, val validTo: LocalDate,
    val status: PassStatus, val price: BigDecimal
)

// ---------------- 运营 ----------------

data class StoreOperationReport(
    val storeId: Long, val storeName: String,
    val windowFrom: Instant, val windowTo: Instant,
    val totalSeats: Int,
    val reservationCount: Int,
    val occupancyRate: Double,
    val overtimeRate: Double,
    val complaintRate: Double,
    val deviceFaultCount: Int,
    val disputesByRootCause: Map<RootCause, Int>,
    val disputesFromDeviceFault: Int,
    val disputesFromUserLateOrAbuse: Int,
    val revenue: BigDecimal,
    val refunded: BigDecimal,
    val compensated: BigDecimal
)

data class ReconcileReport(
    val checkedReservations: Int,
    val repairs: List<String>,
    val inconsistent: Boolean
)
