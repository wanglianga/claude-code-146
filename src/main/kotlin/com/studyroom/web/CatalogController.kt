package com.studyroom.web

import com.studyroom.repo.*
import com.studyroom.support.NotFoundException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.*

@Tag(name = "门店目录", description = "门店、座位、包月套餐查询")
@RestController
@RequestMapping("/api")
@org.springframework.transaction.annotation.Transactional(readOnly = true)
class CatalogController(
    private val storeRepo: StoreRepository,
    private val seatRepo: SeatRepository,
    private val planRepo: MembershipPlanRepository,
    private val sensorRepo: SeatSensorRepository
) {
    @Operation(summary = "门店列表")
    @GetMapping("/stores")
    fun stores(): List<StoreView> = storeRepo.findAll().map { Views.of(it) }

    @Operation(summary = "门店详情")
    @GetMapping("/stores/{id}")
    fun store(@PathVariable id: Long): StoreView =
        Views.of(storeRepo.findById(id).orElseThrow { NotFoundException("门店不存在") })

    @Operation(summary = "门店座位（含实时传感器占用状态）")
    @GetMapping("/stores/{storeId}/seats")
    fun seats(@PathVariable storeId: Long): List<SeatView> =
        seatRepo.findByStoreIdAndActiveTrue(storeId).map { seat ->
            Views.of(seat, sensorRepo.findBySeatId(seat.id!!))
        }

    @Operation(summary = "门店包月套餐")
    @GetMapping("/stores/{storeId}/plans")
    fun plans(@PathVariable storeId: Long): List<PlanView> =
        planRepo.findByStoreIdAndActiveTrue(storeId).map { Views.of(it) }
}
