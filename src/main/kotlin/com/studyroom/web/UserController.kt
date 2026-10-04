package com.studyroom.web

import com.studyroom.domain.UserRole
import com.studyroom.repo.AppUserRepository
import com.studyroom.support.ConflictException
import com.studyroom.support.NotFoundException
import com.studyroom.service.TimelineService
import com.studyroom.support.TimeProvider
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*

@Tag(name = "用户", description = "用户注册/实名")
@RestController
@RequestMapping("/api/users")
class UserController(
    private val userRepo: AppUserRepository,
    private val timeline: TimelineService,
    private val clock: TimeProvider
) {
    @Operation(summary = "注册用户（可同时标记已实名）")
    @PostMapping
    @Transactional
    fun create(@RequestBody @Valid req: CreateUserRequest): UserView {
        if (userRepo.findByPhone(req.phone) != null) throw ConflictException("PHONE_EXISTS", "手机号已注册")
        val u = userRepo.save(
            com.studyroom.domain.AppUser(
                phone = req.phone, name = req.name,
                realNameVerified = req.realNameVerified,
                idCardMasked = if (req.realNameVerified) "ID****${req.phone.takeLast(4)}" else null,
                role = req.role,
                createdAt = clock.now()
            )
        )
        timeline.record("USER_CREATED", null, null, "USER:${u.id}", "${u.name} 实名=${u.realNameVerified}")
        return Views.of(u)
    }

    @Operation(summary = "完成实名认证（门禁放行前置条件）")
    @PostMapping("/{id}/realname")
    @Transactional
    fun verify(@PathVariable id: Long): UserView {
        val u = userRepo.findById(id).orElseThrow { NotFoundException("用户不存在") }
        u.realNameVerified = true
        if (u.idCardMasked == null) u.idCardMasked = "ID****VERIFIED"
        timeline.record("REALNAME_VERIFIED", null, null, "USER:$id", "实名认证完成")
        return Views.of(u)
    }

    @GetMapping
    @Transactional(readOnly = true)
    fun list(): List<UserView> = userRepo.findAll().map { Views.of(it) }
}
