package com.studybuddy.web

import com.studybuddy.api.*
import com.studybuddy.domain.ReservationRepository
import com.studybuddy.domain.TimelineEventRepository
import com.studybuddy.service.BillingService
import com.studybuddy.service.ReservationService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*

@Tag(name = "预约")
@RestController
@RequestMapping("/api/reservations")
class ReservationController(
    private val reservations: ReservationService,
    private val reservationRepo: ReservationRepository,
    private val billing: BillingService,
    private val timelineRepo: TimelineEventRepository
) {

    @Operation(summary = "创建预约（单座/包间/夜间座；包月传 passId；支持幂等键）")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody @Valid req: CreateReservationRequest): ReservationVo =
        Mappers.reservation(reservations.create(req))

    @Operation(summary = "预约详情")
    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    fun get(@PathVariable id: Long): ReservationVo = Mappers.reservation(reservations.getOrThrow(id))

    @Operation(summary = "订单号查询")
    @GetMapping("/by-order/{orderNo}")
    @Transactional(readOnly = true)
    fun byOrderNo(@PathVariable orderNo: String): ReservationVo =
        Mappers.reservation(reservations.getByOrderNoOrThrow(orderNo))

    @Operation(summary = "用户预约列表")
    @GetMapping("/by-user/{userId}")
    @Transactional(readOnly = true)
    fun byUser(@PathVariable userId: Long): List<ReservationVo> =
        reservationRepo.findByUserId(userId).map(Mappers::reservation)

    @Operation(summary = "支付（模拟支付通道；成功后签发门禁凭证）")
    @PostMapping("/{id}/pay")
    fun pay(@PathVariable id: Long, @RequestBody(required = false) req: PayRequest?): ReservationVo =
        Mappers.reservation(reservations.pay(id, req?.idempotencyKey))

    @Operation(summary = "开始前取消（全额退费，凭证失效）")
    @PostMapping("/{id}/cancel")
    fun cancel(@PathVariable id: Long, @RequestParam(required = false) reason: String?): ReservationVo =
        Mappers.reservation(reservations.cancel(id, reason))

    @Operation(summary = "迟到顺延（后移时段；侵占下一位时自动打标记）")
    @PostMapping("/{id}/postpone")
    fun postpone(@PathVariable id: Long, @RequestBody @Valid req: PostponeRequest): ReservationVo =
        Mappers.reservation(reservations.postpone(id, req.delayMinutes))

    @Operation(summary = "临时延时（离场统一计费；侵占下一位时拒绝）")
    @PostMapping("/{id}/extend")
    fun extend(@PathVariable id: Long, @RequestBody @Valid req: ExtendRequest): ReservationVo =
        Mappers.reservation(reservations.extend(id, req.minutes))

    @Operation(summary = "换座（顺延冲突/设备故障时给下一位用户换座）")
    @PostMapping("/{id}/reassign")
    fun reassign(@PathVariable id: Long, @RequestBody @Valid req: ReassignSeatRequest): ReservationVo =
        Mappers.reservation(reservations.reassign(id, req.targetSeatId, req.reason))

    @Operation(summary = "离场检查（设备/储物柜状态与赔偿申报）")
    @PostMapping("/{id}/departure-check")
    fun departureCheck(@PathVariable id: Long, @RequestBody @Valid req: DepartureCheckRequest): ReservationVo =
        Mappers.reservation(reservations.submitDepartureCheck(id, req))

    @Operation(summary = "用户提前离场并申请退费（结算+释放座位+按未使用时长退费）")
    @PostMapping("/{id}/early-leave-refund")
    fun earlyLeaveRefund(@PathVariable id: Long, @RequestBody(required = false) req: RefundRequest?): SettlementVo =
        Mappers.settlement(reservations.earlyLeaveRefund(id, req?.reason))

    @Operation(summary = "账单流水（正数收费/负数退款返还补偿）")
    @GetMapping("/{id}/billing")
    @Transactional(readOnly = true)
    fun billingEntries(@PathVariable id: Long): List<Map<String, Any?>> =
        billing.list(id).map {
            mapOf(
                "id" to it.id, "type" to it.type, "amount" to it.amount,
                "at" to it.at, "note" to it.note, "ticketId" to it.ticketId
            )
        }

    @Operation(summary = "订单统一时间轴（预约/门禁/座位/投诉/设备/计费/退款）")
    @GetMapping("/{id}/timeline")
    @Transactional(readOnly = true)
    fun timeline(@PathVariable id: Long): List<TimelineEventVo> =
        timelineRepo.findByReservationIdOrderByAtAsc(id).map { e ->
            TimelineEventVo(e.id!!, e.type, e.at, e.reservation?.id, e.storeId, e.seatId,
                e.actorType, e.actorId, e.detail)
        }
}

@Tag(name = "门禁")
@RestController
@RequestMapping("/api/gate")
class GateController(private val gateService: com.studybuddy.service.GateService) {

    @Operation(summary = "门禁刷卡/刷脸尝试（IN/OUT；返回放行结果与监控事件编号，每次尝试留痕）")
    @PostMapping("/attempt")
    fun attempt(@RequestBody @Valid req: GateAttemptRequest): GateAttemptResponse =
        gateService.attempt(req)
}
