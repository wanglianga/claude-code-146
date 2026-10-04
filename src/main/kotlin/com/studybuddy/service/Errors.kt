package com.studybuddy.service

import com.studybuddy.domain.GateReason
import org.springframework.http.HttpStatus

/** 业务异常：携带稳定错误码与 HTTP 状态，便于前端/闸机处理 */
open class BusinessException(
    val code: String,
    message: String,
    val httpStatus: HttpStatus = HttpStatus.BAD_REQUEST,
    val gateReason: GateReason? = null
) : RuntimeException(message)

class NotFoundException(resource: String, id: Any) :
    BusinessException("NOT_FOUND", "$resource 不存在: $id", HttpStatus.NOT_FOUND)

class ConflictException(message: String) :
    BusinessException("CONFLICT", message, HttpStatus.CONFLICT)

class RuleRejectException(message: String, gateReason: GateReason? = null) :
    BusinessException("RULE_REJECTED", message, HttpStatus.UNPROCESSABLE_ENTITY, gateReason)
