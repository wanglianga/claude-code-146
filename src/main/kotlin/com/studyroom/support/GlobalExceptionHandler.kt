package com.studyroom.support

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

data class ErrorResponse(val code: String, val message: String, val path: String, val timestamp: String)

@RestControllerAdvice
class GlobalExceptionHandler(private val timeProvider: TimeProvider) {

    @ExceptionHandler(BizException::class)
    fun handleBiz(ex: BizException, req: HttpServletRequest): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(ex.status).body(
            ErrorResponse(ex.code, ex.message ?: "业务异常", req.requestURI, timeProvider.now().toString())
        )

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException, req: HttpServletRequest): ResponseEntity<ErrorResponse> {
        val msg = ex.bindingResult.fieldErrors.joinToString("; ") { "${it.field}: ${it.defaultMessage}" }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse("VALIDATION_FAILED", msg, req.requestURI, timeProvider.now().toString()))
    }

    @ExceptionHandler(Exception::class)
    fun handleOther(ex: Exception, req: HttpServletRequest): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            ErrorResponse("INTERNAL_ERROR", ex.message ?: "系统错误", req.requestURI, timeProvider.now().toString())
        )
}
