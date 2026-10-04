package com.studybuddy.web

import com.studybuddy.api.*
import com.studybuddy.domain.SeatStatus
import com.studybuddy.domain.StoreRepository
import com.studybuddy.domain.SeatRepository
import com.studybuddy.domain.Store
import com.studybuddy.service.NotFoundException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*

@Tag(name = "门店")
@RestController
@RequestMapping("/api/stores")
class StoreController(private val storeRepo: StoreRepository) {

    @Operation(summary = "创建门店")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody @Valid req: CreateStoreRequest): StoreVo {
        val s = storeRepo.save(Store(name = req.name, address = req.address, unattended = req.unattended))
        return Mappers.store(s)
    }

    @Operation(summary = "门店列表")
    @GetMapping
    fun list(): List<StoreVo> = storeRepo.findAll().map(Mappers::store)

    @Operation(summary = "门店详情（含停电状态）")
    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): StoreVo =
        storeRepo.findById(id).map(Mappers::store).orElseThrow { NotFoundException("门店", id) }
}

@Tag(name = "座位")
@RestController
@RequestMapping("/api/stores/{storeId}/seats")
class SeatController(
    private val seatRepo: SeatRepository,
    private val storeRepo: StoreRepository
) {

    @Operation(summary = "门店下新增座位（单座/包间/夜间座，可带储物柜）")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    fun create(@PathVariable storeId: Long, @RequestBody @Valid req: CreateSeatRequest): SeatVo {
        val store = storeRepo_Seat(storeId)
        val seat = seatRepo.save(
            com.studybuddy.domain.Seat(
                store = store, seatCode = req.seatCode, seatType = req.seatType, zone = req.zone,
                hourlyPrice = req.hourlyPrice, overtimePerMinute = req.overtimePerMinute,
                hasLocker = req.hasLocker
            )
        )
        return Mappers.seat(seat)
    }

    @Operation(summary = "门店座位列表")
    @GetMapping
    @Transactional(readOnly = true)
    fun list(@PathVariable storeId: Long): List<SeatVo> =
        seatRepo.findByStoreId(storeId).map(Mappers::seat)

    @Operation(summary = "运营调价（按上座率/故障表现调整小时价与延时单价）")
    @PostMapping("/{seatId}/price")
    @Transactional
    fun adjustPrice(@PathVariable storeId: Long, @PathVariable seatId: Long,
                    @RequestBody @Valid req: AdjustSeatPriceRequest): SeatVo {
        val seat = seatRepo.findById(seatId).orElseThrow { NotFoundException("座位", seatId) }
        seat.hourlyPrice = req.hourlyPrice
        seat.overtimePerMinute = req.overtimePerMinute
        return Mappers.seat(seatRepo.save(seat))
    }

    @Operation(summary = "设置/解除座位维护（维护期间门禁拒绝放行）")
    @PostMapping("/{seatId}/maintenance")
    @Transactional
    fun maintenance(@PathVariable storeId: Long, @PathVariable seatId: Long,
                    @RequestParam(required = true) maintenance: Boolean): SeatVo {
        val seat = seatRepo.findById(seatId).orElseThrow { NotFoundException("座位", seatId) }
        if (maintenance) {
            seat.status = SeatStatus.MAINTENANCE
            seat.occupiedByReservationId = null
        } else if (seat.status == SeatStatus.MAINTENANCE) {
            seat.status = SeatStatus.FREE
        }
        return Mappers.seat(seatRepo.save(seat))
    }

    private fun storeRepo_Seat(storeId: Long): com.studybuddy.domain.Store =
        storeRepo.findById(storeId).orElseThrow { NotFoundException("门店", storeId) }
}
