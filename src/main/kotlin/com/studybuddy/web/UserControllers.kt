package com.studybuddy.web

import com.studybuddy.api.*
import com.studybuddy.domain.AppUser
import com.studybuddy.domain.AppUserRepository
import com.studybuddy.domain.PassStatus
import com.studybuddy.service.MonthlyPassService
import com.studybuddy.service.NotFoundException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@Tag(name = "用户")
@RestController
@RequestMapping("/api/users")
class UserController(private val userRepo: AppUserRepository) {

    @Operation(summary = "注册用户")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody @Valid req: CreateUserRequest): UserVo {
        if (userRepo.findByPhone(req.phone) != null) {
            throw com.studybuddy.service.ConflictException("手机号已注册")
        }
        val u = userRepo.save(
            AppUser(name = req.name, phone = req.phone,
                idVerified = !req.idCardMasked.isNullOrBlank(),
                idCardMasked = req.idCardMasked)
        )
        return Mappers.user(u)
    }

    @Operation(summary = "用户详情")
    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): UserVo =
        userRepo.findById(id).map(Mappers::user).orElseThrow { NotFoundException("用户", id) }

    @Operation(summary = "实名认证（门禁放行前置条件）")
    @PostMapping("/{id}/realname")
    fun realName(@PathVariable id: Long, @RequestBody @Valid req: RealNameRequest): UserVo {
        val u = userRepo.findById(id).orElseThrow { NotFoundException("用户", id) }
        u.idVerified = true
        u.idCardMasked = req.idCardMasked
        return Mappers.user(userRepo.save(u))
    }
}

@Tag(name = "包月权益")
@RestController
@RequestMapping("/api/passes")
class MonthlyPassController(private val passService: MonthlyPassService) {

    @Operation(summary = "开通包月权益（固定座/浮动座）")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody @Valid req: CreatePassRequest): PassVo {
        val p = passService.create(req)
        return PassVo(p.id!!, p.passNo, p.user.id!!, p.storeId, p.fixedSeat?.id, p.mode,
            p.validFrom, p.validTo, p.status, p.price)
    }

    @Operation(summary = "门店包月权益列表")
    @GetMapping("/by-store/{storeId}")
    fun byStore(@PathVariable storeId: Long): List<PassVo> = passService.listByStore(storeId)

    @Operation(summary = "用户包月权益列表")
    @GetMapping("/by-user/{userId}")
    fun byUser(@PathVariable userId: Long): List<PassVo> = passService.listByUser(userId)

    @Operation(summary = "吊销包月权益（权益滥用时）")
    @PostMapping("/{id}/revoke")
    fun revoke(@PathVariable id: Long): PassVo {
        val p = passService.revoke(id)
        return PassVo(p.id!!, p.passNo, p.user.id!!, p.storeId, p.fixedSeat?.id, p.mode,
            p.validFrom, p.validTo, PassStatus.REVOKED, p.price)
    }
}
