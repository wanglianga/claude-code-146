package com.studyroom.support

/** 业务异常，携带稳定的错误码，由 GlobalExceptionHandler 统一转成 JSON。 */
open class BizException(val code: String, message: String, val status: Int = 400) : RuntimeException(message)

class NotFoundException(message: String) : BizException("NOT_FOUND", message, 404)
class ConflictException(code: String, message: String) : BizException(code, message, 409)
