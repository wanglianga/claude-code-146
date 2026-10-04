package com.studybuddy.domain

/** 物理座位类型 */
enum class SeatType { SINGLE_SEAT, PRIVATE_ROOM, NIGHT_SEAT }

enum class StoreStatus { OPEN, OUTAGE, CLOSED }

enum class DepositStatus { NONE, HELD, RETURNED, DEDUCTED }

/** 座位当前状态（DB 为权威状态，Redis 保存运行时镜像） */
enum class SeatStatus { FREE, OCCUPIED, MAINTENANCE }

/** 预约产品类型 */
enum class ReservationType { SINGLE_SEAT, PRIVATE_ROOM, NIGHT_SEAT, MONTHLY }

/** 预约单状态机 */
enum class ReservationStatus {
    PENDING_PAYMENT,   // 待支付
    BOOKED,            // 已支付已占位
    IN_PROGRESS,       // 已进场
    COMPLETED,         // 已离场结算
    CANCELLED,         // 已取消
    NO_SHOW,           // 超时未到（爽约）
    REFUNDED,          // 全额退费完成
    DISPUTED           // 争议处理中
}

/** 门禁方向 */
enum class GateDirection { IN, OUT }

/** 门禁判定结果 */
enum class GateResult { ALLOW, DENY }

/** 门禁/业务判定原因码 */
enum class GateReason(val note: String) {
    OK("放行"),
    NOT_PAID("订单未支付"),
    REALNAME_MISSING("未完成实名"),
    OUTSIDE_WINDOW("不在入场时间窗内"),
    STORE_OUTAGE("门店停电/停业"),
    SEAT_OCCUPIED("座位被他人占用"),
    GATE_DEVICE_FAULT("门禁设备故障"),
    ALREADY_ENTERED("已进场，请勿重复刷入"),
    NOT_INSIDE("场内无进场记录"),
    RESERVATION_INVALID("预约单无效"),
    SEAT_MAINTENANCE("座位维护中"),
    USER_BLACKLISTED("用户已被列入黑名单")
}

/** 统一时间轴事件类型（保证预约/门禁/座位/计费/工单共用一条时间线） */
enum class TimelineType {
    CREATED, PAID, CANCELLED,
    CHECK_IN, CHECK_OUT, GATE_DENIED,
    LATE_MARKED, POSTPONE_REQUESTED, POSTPONED,
    EXTEND_REQUESTED, EXTENDED, OVERTIME_DETECTED,
    ENCROACHMENT_MARKED, SEAT_ENCROACHED, REASSIGNED,
    COMPLAINT_FILED,
    DEVICE_FAULT_REPORTED, DEVICE_RESOLVED,
    OUTAGE_START, OUTAGE_END,
    TICKET_OPENED, TICKET_HANDLED, TICKET_CLOSED,
    BILLING_OVERTIME, DEPOSIT_HELD, DEPOSIT_RETURNED, DEPOSIT_DEDUCTED,
    REFUND_REQUESTED, REFUNDED, COMPENSATED,
    RECONCILED
}

enum class ActorType { USER, STAFF, SYSTEM, GATE, SCHEDULER }

/** 设备类型：门禁锁/空调/照明/总电源/储物柜/传感器 */
enum class DeviceType { DOOR_LOCK, AC, LIGHT, POWER, LOCKER, SEAT_SENSOR }

enum class DeviceEventType { REPORTED, RESOLVED }

enum class ResponsibleParty { STORE, USER, VENDOR, UNKNOWN }

/** 座位/环境传感器 */
enum class SensorType { OCCUPANCY, NOISE, TEMPERATURE, POWER }

/** 投诉原因 */
enum class ComplaintReason {
    NOISE_NEIGHBOR,   // 邻座噪音
    SEAT_OCCUPIED,    // 座位被占（顺延/延时冲突）
    AC_FAULT,         // 空调故障
    GATE_FAILED,      // 门禁打不开
    POWER_OUTAGE,     // 停电
    OTHER
}

enum class ComplaintStatus { OPEN, ACKNOWLEDGED, RESOLVED, REJECTED }

/** 客服工单分类 */
enum class TicketCategory {
    ACCESS_PROBLEM, SEAT_CONFLICT, EQUIPMENT_FAULT, NOISE_DISPUTE,
    REFUND_REQUEST, BILLING_DISPUTE, MONTHLY_CONFLICT, OUTAGE_CLAIM, OTHER
}

/** 争议根因归类（运营端按门店统计来源） */
enum class RootCause {
    DEVICE_FAULT,    // 设备故障（含停电/空调/门锁）
    USER_LATE,       // 用户迟到引发
    BENEFIT_ABUSE,   // 权益滥用（恶意延时、包月冒占等）
    STORE_FAULT,     // 其他门店责任
    USER_DAMAGE,     // 用户损坏设备
    UNKNOWN
}

enum class TicketStatus { OPEN, IN_PROGRESS, RESOLVED, CLOSED }

/** 账单明细（正数为收费，负数为退款/返还） */
enum class BillingType {
    BOOKING_FEE, OVERTIME_FEE, DEPOSIT_HOLD,
    DEPOSIT_RETURN, DEPOSIT_DEDUCT, REFUND, COMPENSATION
}

/** 离场时设备/储物柜检查结果 */
enum class EquipmentCheck { UNCHECKED, OK, DAMAGED }
enum class LockerCheck { UNCHECKED, CLEAN, STUFF_LEFT }

/** 包月权益模式：固定座 / 浮动座 */
enum class PassMode { FIXED_SEAT, FLOATING }
enum class PassStatus { ACTIVE, EXPIRED, REVOKED }
