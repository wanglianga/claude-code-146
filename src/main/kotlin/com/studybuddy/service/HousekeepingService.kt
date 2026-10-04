package com.studybuddy.service

import com.studybuddy.domain.*
import com.studybuddy.infra.GatePassService
import com.studybuddy.infra.TimeService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 无人值守自动化业务逻辑（与定时触发器分离，便于测试直接调用）：
 * - 已过开始时间的 BOOKED 订单：超宽限标记迟到，超 noShow 时限判爽约并释放占位
 * - 在场超时订单：整 15 分钟记录一次提示（实际延时费离场时收取）
 * - 包月权益到期处理
 */
@Service
class HousekeepingService(
    private val reservationRepo: ReservationRepository,
    private val passService: MonthlyPassService,
    private val timeline: TimelineService,
    private val gatePass: GatePassService,
    private val time: TimeService,
    private val props: StudyroomProps
) {
    @Transactional
    fun scanLateAndNoShow() {
        val now = time.now()
        val graceMs = props.gate.lateGraceMinutes * 60_000L
        val noShowMs = props.gate.noShowMinutes * 60_000L

        reservationRepo.findStartedButNotCheckedIn(
            listOf(ReservationStatus.BOOKED), now.toEpochMilli()
        ).forEach { r ->
            val overdue = now.toEpochMilli() - r.startTime
            when {
                overdue > noShowMs && r.lateMarkedAt != null -> {
                    r.status = ReservationStatus.NO_SHOW
                    gatePass.revoke(r.id!!)
                    timeline.add(TimelineType.LATE_MARKED, r,
                        detail = "超过 ${props.gate.noShowMinutes} 分钟未到，判爽约并释放占位")
                }
                overdue > graceMs && r.lateMarkedAt == null -> {
                    r.lateMarkedAt = now
                    timeline.add(TimelineType.LATE_MARKED, r,
                        detail = "已迟到 ${overdue / 60_000} 分钟，用户可申请顺延；超时未处理将判爽约")
                }
            }
        }
    }

    @Transactional
    fun scanOvertime() {
        val now = time.now()
        reservationRepo.findOvertime(ReservationStatus.IN_PROGRESS, now.toEpochMilli()).forEach { r ->
            val mins = (now.toEpochMilli() - r.scheduledEndTime) / 60_000
            if (mins > 0 && mins % 15 == 0L && timeline.findRecentOvertime(r.id!!) == null) {
                timeline.add(TimelineType.OVERTIME_DETECTED, r, actorType = ActorType.SCHEDULER,
                    detail = "已超时 $mins 分钟未离场，将按分钟计费，可申请临时延时或尽快离场")
            }
        }
    }

    @Transactional
    fun expirePasses(): Int = passService.expireOutdated()
}
