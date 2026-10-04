package com.studybuddy.domain

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** 统一时间轴：预约、门禁、座位、投诉、设备、计费、退款、门店责任共用一条线，保证跨模块一致。 */
@Entity
@Table(name = "timeline_events", indexes = [
    Index(name = "idx_tl_res", columnList = "reservation_id"),
    Index(name = "idx_tl_store", columnList = "store_id"),
    Index(name = "idx_tl_seat", columnList = "seat_id"),
    Index(name = "idx_tl_ticket", columnList = "ticket_id")
])
class TimelineEvent(
    @Column(nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    var type: TimelineType,

    var at: Instant,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reservation_id")
    var reservation: Reservation? = null,

    var storeId: Long? = null,
    var seatId: Long? = null,
    var ticketId: Long? = null,

    @Enumerated(EnumType.STRING) @Column(length = 16)
    var actorType: ActorType? = null,
    var actorId: String? = null,

    /** 事件载荷（判定原因、费用、监控事件编号等） */
    @Lob
    var detail: String? = null
) : BaseEntity()

/** 门禁日志（物理门禁每次尝试都记录，放行或拒绝都不可少） */
@Entity
@Table(name = "gate_logs", indexes = [
    Index(name = "idx_gate_res", columnList = "reservation_id"),
    Index(name = "idx_gate_store", columnList = "store_id,createdAt")
])
class GateLog(
    @JoinColumn(name = "reservation_id")
    @ManyToOne(fetch = FetchType.LAZY)
    var reservation: Reservation? = null,

    @Column(nullable = false)
    var storeId: Long,

    var seatId: Long? = null,
    var userId: Long? = null,

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 8)
    var direction: GateDirection,

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 8)
    var result: GateResult,

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32)
    var reason: GateReason,

    var occurredAt: Instant,

    /** 门禁设备编号、固件版本等 */
    var deviceSn: String? = null,

    /** 监控事件编号（可与门店摄像头平台对齐） */
    var cctvEventId: String? = null
) : BaseEntity()

/** 设备故障/恢复事件（门锁、空调、照明、总电源、储物柜、座位传感器） */
@Entity
@Table(name = "device_events", indexes = [
    Index(name = "idx_dev_store", columnList = "store_id,createdAt"),
    Index(name = "idx_dev_seat", columnList = "seat_id")
])
class DeviceEvent(
    @Column(nullable = false)
    var storeId: Long,

    var seatId: Long? = null,

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    var deviceType: DeviceType,

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    var eventType: DeviceEventType,

    var at: Instant,

    /** 影响在店用户的预约：停电等批量场景填充 */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "device_event_affected", joinColumns = [JoinColumn(name = "device_event_id")])
    @Column(name = "reservation_id")
    var affectedReservationIds: MutableList<Long> = mutableListOf(),

    /** 责任方（门店/用户/供应商） */
    @Enumerated(EnumType.STRING) @Column(length = 16)
    var responsible: ResponsibleParty = ResponsibleParty.UNKNOWN,

    var note: String? = null,
    var ticketId: Long? = null,

    /** 监控事件编号 */
    var cctvEventId: String? = null
) : BaseEntity()
@Entity
@Table(name = "sensor_readings", indexes = [
    Index(name = "idx_sensor_seat_at", columnList = "seat_id,createdAt")
])
class SensorReading(
    @Column(nullable = false)
    var seatId: Long,

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    var sensorType: SensorType,

    /** OCCUPANCY: 1/0；NOISE: dB；TEMPERATURE: ℃；POWER: 1/0（value 为 H2 保留字，列名转义） */
    @Column(name = "reading_value", nullable = false)
    var value: Double,

    var reportedAt: Instant
) : BaseEntity()

/** 用户申诉（随工单提交，客服可查） */
@Entity
@Table(name = "appeals")
class Appeal(
    var ticketId: Long? = null,

    @Column(nullable = false)
    var userId: Long,

    var reservationId: Long? = null,
    var storeId: Long? = null,

    @Lob @Column(nullable = false)
    var content: String
) : BaseEntity()

/** 投诉（邻座噪音、座位被占、空调故障、门禁打不开、停电…） */
@Entity
@Table(name = "complaints", indexes = [
    Index(name = "idx_comp_res", columnList = "reservation_id"),
    Index(name = "idx_comp_store", columnList = "store_id,createdAt")
])
class Complaint(
    @Column(nullable = false)
    var storeId: Long,

    var reservationId: Long? = null,
    var userId: Long? = null,
    var seatId: Long? = null,

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24)
    var reason: ComplaintReason,

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    var status: ComplaintStatus = ComplaintStatus.OPEN,

    @Lob
    var detail: String? = null,

    /** 被投诉的用户（如邻座噪音） */
    var againstUserId: Long? = null,

    /** 关联设备事件（空调/门禁/停电） */
    var deviceEventId: Long? = null,

    /** 监控事件编号 */
    var cctvEventId: String? = null,

    var handledByStaffId: String? = null,
    var handledAt: Instant? = null,

    @Lob
    var handleNote: String? = null,
    var ticketId: Long? = null
) : BaseEntity()

/** 客服工单（争议主线） */
@Entity
@Table(name = "support_tickets", indexes = [
    Index(name = "idx_ticket_store", columnList = "store_id"),
    Index(name = "idx_ticket_root", columnList = "root_cause")
])
class SupportTicket(
    @Column(nullable = false, length = 40, unique = true)
    var ticketNo: String,

    @Column(nullable = false)
    var storeId: Long,

    var reservationId: Long? = null,
    var userId: Long? = null,

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24)
    var category: TicketCategory,

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24, name = "root_cause")
    var rootCause: RootCause,

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    var status: TicketStatus = TicketStatus.OPEN,

    @Lob
    var summary: String? = null,

    var staffId: String? = null,

    @Lob
    var staffNote: String? = null,

    /** 客服核定退款/补偿（元，正数） */
    @Column(nullable = false, precision = 10, scale = 2)
    var resolvedRefund: BigDecimal = BigDecimal.ZERO,

    @Column(nullable = false, precision = 10, scale = 2)
    var resolvedCompensation: BigDecimal = BigDecimal.ZERO,

    var openedAt: Instant = Instant.now(),
    var resolvedAt: Instant? = null,
    var closedAt: Instant? = null
) : BaseEntity()

/** 账单流水（收费为正、退款/返还/补偿为负） */
@Entity
@Table(name = "billing_entries", indexes = [
    Index(name = "idx_bill_res", columnList = "reservation_id")
])
class BillingEntry(
    @JoinColumn(name = "reservation_id", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    var reservation: Reservation,

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    var type: BillingType,

    @Column(nullable = false, precision = 10, scale = 2)
    var amount: BigDecimal,

    var at: Instant,
    var note: String? = null,
    var ticketId: Long? = null
) : BaseEntity()

/** 包月权益 */
@Entity
@Table(name = "monthly_passes", indexes = [
    Index(name = "idx_pass_user", columnList = "user_id"),
    Index(name = "idx_pass_seat", columnList = "seat_id")
])
class MonthlyPass(
    @Column(nullable = false, unique = true, length = 40)
    var passNo: String,

    @JoinColumn(name = "user_id", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    var user: AppUser,

    @Column(nullable = false)
    var storeId: Long,

    @JoinColumn(name = "seat_id")
    @ManyToOne(fetch = FetchType.LAZY)
    var fixedSeat: Seat? = null,

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    var mode: PassMode,

    @Column(nullable = false)
    var validFrom: LocalDate,

    @Column(nullable = false)
    var validTo: LocalDate,

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    var status: PassStatus = PassStatus.ACTIVE,

    @Column(nullable = false, precision = 10, scale = 2)
    var price: BigDecimal
) : BaseEntity()
