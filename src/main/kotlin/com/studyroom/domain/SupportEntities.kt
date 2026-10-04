package com.studyroom.domain

import jakarta.persistence.*
import java.time.Instant

/** 门禁进/出日志：客服争议处理的核心证据之一 */
@Entity
@Table(
    name = "access_log",
    indexes = [Index(name = "idx_access_store_time", columnList = "store_id,createdAt")]
)
class AccessLog(
    /** 拒绝（无有效预约）时为空 */
    var bookingNo: String? = null,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    var user: AppUser,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id")
    var store: Store,
    var doorId: String? = null,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var direction: AccessDirection,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var result: AccessResult,
    @Column(length = 500) var detail: String? = null,
    /** 监控事件编号，可与监控录像互相印证 */
    var monitorEventNo: String? = null,
    @Column(nullable = false) var createdAt: Instant = Instant.now(),
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null
)

/**
 * 统一时间轴：预约、门禁、座位、投诉、设备、计费、退款、工单的每一次状态变化
 * 都追加一条不可变事件。排障与对账时以时间轴为准。
 */
@Entity
@Table(
    name = "timeline_event",
    indexes = [
        Index(name = "idx_tl_booking", columnList = "bookingNo"),
        Index(name = "idx_tl_store_time", columnList = "storeId,occurredAt")
    ]
)
class TimelineEvent(
    @Column(nullable = false) var eventType: String,
    @Column(nullable = false) var occurredAt: Instant,
    var storeId: Long? = null,
    var bookingNo: String? = null,
    var actor: String? = null,
    @Column(length = 1000) var detail: String? = null,
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null
)

@Entity
@Table(name = "incident")
class Incident(
    @Column(nullable = false, unique = true) var incidentNo: String,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id")
    var store: Store,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seat_id")
    var seat: Seat? = null,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var type: IncidentType,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var status: IncidentStatus = IncidentStatus.OPEN,
    @Column(nullable = false) var description: String,
    var monitorEventNo: String? = null,
    @Column(nullable = false) var openedAt: Instant = Instant.now(),
    var resolvedAt: Instant? = null,
    var resolution: String? = null,
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null
)

@Entity
@Table(name = "complaint")
class Complaint(
    @Column(nullable = false, unique = true) var complaintNo: String,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id")
    var store: Store,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "complainant_id")
    var complainant: AppUser,
    /** 投诉来源预约 */
    @Column(nullable = false) var bookingNo: String,
    /** 被投诉的预约号（如邻座噪音），可空 */
    var againstBookingNo: String? = null,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var category: ComplaintCategory,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var status: ComplaintStatus = ComplaintStatus.OPEN,
    @Column(nullable = false) var description: String,
    var relatedTicketNo: String? = null,
    var relatedIncidentNo: String? = null,
    var monitorEventNo: String? = null,
    @Column(nullable = false) var createdAt: Instant = Instant.now(),
    var resolvedAt: Instant? = null,
    var resolution: String? = null,
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null
)

@Entity
@Table(name = "support_ticket")
class SupportTicket(
    @Column(nullable = false, unique = true) var ticketNo: String,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id")
    var store: Store,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    var user: AppUser,
    @Column(nullable = false) var bookingNo: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var cause: DisputeCause,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var status: TicketStatus = TicketStatus.OPEN,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var responsibility: Responsibility = Responsibility.NONE,
    @Column(nullable = false) var title: String,
    @Column(nullable = false, length = 1000) var description: String,
    /** 用户申诉内容 */
    @Column(length = 1000) var userAppeal: String? = null,
    /** 店员处理记录 */
    @Column(length = 1000) var staffNote: String? = null,
    var monitorEventNos: String? = null,
    var incidentNo: String? = null,
    var complaintNo: String? = null,
    @Column(nullable = false) var createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
    var resolvedAt: Instant? = null,
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null
)
