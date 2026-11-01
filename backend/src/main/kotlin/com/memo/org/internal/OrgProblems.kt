package com.memo.org.internal

import com.memo.common.web.ProblemTypes
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** Problems of the org endpoints: unknown ids are 404, invalid input 400 `validation`. */
@RestControllerAdvice(basePackageClasses = [OrgProblems::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class OrgProblems {
    @ExceptionHandler(OrgNotFoundException::class)
    fun notFound(ex: OrgNotFoundException): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.message ?: "Not found"))

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(ex: IllegalArgumentException): ResponseEntity<ProblemDetail> =
        ResponseEntity.badRequest().body(ProblemTypes.of(HttpStatus.BAD_REQUEST, ProblemTypes.VALIDATION, ex.message ?: "Invalid request."))
}

/** A required name or title, trimmed. */
fun requiredText(value: String): String = value.trim().also { require(it.isNotEmpty()) { "A name is required." } }

/** An optional text, trimmed; blank means absent. */
fun optionalText(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }
