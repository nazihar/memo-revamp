package com.memo.notification.internal

import com.memo.common.web.ProblemTypes
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** Problems of the notification endpoints: unknown items 404, mandatory categories 409, invalid input 400. */
@RestControllerAdvice(basePackageClasses = [NotificationProblems::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
internal class NotificationProblems {
    @ExceptionHandler(NotificationNotFoundException::class)
    fun notFound(): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Notification not found"))

    @ExceptionHandler(MandatoryCategoryException::class)
    fun mandatory(): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(
            ProblemTypes.of(HttpStatus.CONFLICT, ProblemTypes.CONFLICT, "Security notices cannot be turned off.").apply {
                setProperty("reason", "MANDATORY")
            },
        )

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(ex: IllegalArgumentException): ResponseEntity<ProblemDetail> =
        ResponseEntity.badRequest().body(ProblemTypes.of(HttpStatus.BAD_REQUEST, ProblemTypes.VALIDATION, ex.message ?: "Invalid request."))
}
