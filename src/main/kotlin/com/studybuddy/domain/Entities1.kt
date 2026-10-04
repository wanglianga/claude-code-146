package com.studybuddy.domain

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

@MappedSuperclass
abstract class BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @Column(nullable = false, updatable = false)
    open var createdAt: Instant = Instant.now()

    @Column(nullable = false)
    open var updatedAt: Instant = Instant.now()

    @PreUpdate
    fun onUpdate() { updatedAt = Instant.now() }
}

@Entity
@Table(name = "stores")
class Store(
    @Column(nullable = false)
    var name: String = "",

    var address: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: StoreStatus = StoreStatus.OPEN,

    /** 是否无人值守门店 */
    var unattended: Boolean = false,

    /** 当前停电开始时间（status=OUTAGE 时有值） */
    var outageSince: Instant? = null
) : BaseEntity()

@Entity
@Table(name = "seats",
    uniqueConstraints = [UniqueConstraint(name = "uk_seat_store_code", columnNames = ["store_id", "seatCode"])])
class Seat(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false)
    var store: Store,

    @Column(nullable = false, length = 32)
    var seatCode: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    var seatType: SeatType,

    /** 区域，如静音区/讨论区 */
    var zone: String = "静音区",

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: SeatStatus = SeatStatus.FREE,

    /** 每小时基础价 */
    @Column(nullable = false, precision = 10, scale = 2)
    var hourlyPrice: BigDecimal = BigDecimal("10.00"),

    /** 每分钟延时价 */
    @Column(nullable = false, precision = 10, scale = 2)
    var overtimePerMinute: BigDecimal = BigDecimal("0.50"),

    var hasLocker: Boolean = false,

    /** 当前占用预约（运行时权威镜像） */
    var occupiedByReservationId: Long? = null
) : BaseEntity()

@Entity
@Table(name = "users")
class AppUser(
    @Column(nullable = false, length = 64)
    var name: String,

    @Column(nullable = false, unique = true, length = 32)
    var phone: String,

    var idVerified: Boolean = false,

    /** 实名证件号（演示环境仅记录脱敏形式） */
    var idCardMasked: String? = null,

    var blacklisted: Boolean = false
) : BaseEntity()

/**
 * 预约单：单座/包间/夜间座按时段计费；包月为权益 MONTHLY_PASS（seat 可为权益绑定固定座）。
 * 时间字段使用 epoch 毫秒，统一时区无关；金额单位元。
 */
@Entity
@Table(name = "reservations", indexes = [
    Index(name = "idx_res_store_time", columnList = "store_id,startTime,endTime"),
    Index(name = "idx_res_user", columnList = "user_id"),
    Index(name = "idx_res_seat", columnList = "seat_id")
])
class Reservation(
    @Column(nullable = false, unique = true, length = 40)
    var orderNo: String,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    var user: AppUser,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false)
    var store: Store,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seat_id")
    var seat: Seat? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    var reservationType: ReservationType,

    @Column(nullable = false)
    var startTime: Long,

    @Column(nullable = false)
    var endTime: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    var status: ReservationStatus = ReservationStatus.PENDING_PAYMENT,

    @Column(nullable = false, precision = 10, scale = 2)
    var price: BigDecimal = BigDecimal.ZERO,

    @Column(nullable = false, precision = 10, scale = 2)
    var deposit: BigDecimal = BigDecimal.ZERO,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var depositStatus: DepositStatus = DepositStatus.NONE,

    var needLocker: Boolean = false,

    /** 静音规则确认 */
    var silentRuleAccepted: Boolean = false,

    var paid: Boolean = false,
    var paidAt: Instant? = null,

    /** 实际进场/离场 */
    var checkInAt: Instant? = null,
    var checkOutAt: Instant? = null,

    /** 迟到判定时间点（调度器标记） */
    var lateMarkedAt: Instant? = null,

    /** 顺延/延时后最新的计划结束时间 */
    var scheduledEndTime: Long = 0,

    /** 延时分钟数（累计） */
    var overtimeMinutes: Int = 0,

    /** 延时累计费用 */
    @Column(nullable = false, precision = 10, scale = 2)
    var overtimeFee: BigDecimal = BigDecimal.ZERO,

    /** 顺延次数（无人值守场景） */
    var postponeCount: Int = 0,

    /** 被谁的预约挤占（本座位下一位用户） */
    var encroachedByReservationId: Long? = null,

    /** 换座记录：原座位 */
    var movedFromSeatId: Long? = null,

    /** 离场设备/储物柜状态 */
    @Enumerated(EnumType.STRING) @Column(length = 16)
    var equipmentCheck: EquipmentCheck = EquipmentCheck.UNCHECKED,

    @Enumerated(EnumType.STRING) @Column(length = 16)
    var lockerCheck: LockerCheck = LockerCheck.UNCHECKED,

    /** 设备损坏赔偿 */
    @Column(nullable = false, precision = 10, scale = 2)
    var damageCharge: BigDecimal = BigDecimal.ZERO,

    /** 已退费总额 */
    @Column(nullable = false, precision = 10, scale = 2)
    var refundedAmount: BigDecimal = BigDecimal.ZERO,

    /** 门店补偿总额 */
    @Column(nullable = false, precision = 10, scale = 2)
    var compensatedAmount: BigDecimal = BigDecimal.ZERO,

    /** 幂等键（创建/支付） */
    var idempotencyKey: String? = null,

    /** 关联争议工单 */
    var ticketId: Long? = null,

    var remark: String? = null
) : BaseEntity() {
    val isMonthly: Boolean get() = reservationType == ReservationType.MONTHLY
}
