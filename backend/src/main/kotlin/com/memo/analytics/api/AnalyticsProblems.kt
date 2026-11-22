package com.memo.analytics.api

import com.memo.analytics.measures.AnalyticsRejectedException
import com.memo.common.web.ProblemTypes
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** A committee outside the viewer's analytics, or none at all by that id: the same 404 either way (design E08 §3). */
class AnalyticsNotFoundException : RuntimeException("committee not found")

/** Problems of the analytics endpoints: 404 for a committee outside scope, 422 `validation` for a refused input. */
@RestControllerAdvice(basePackageClasses = [AnalyticsProblems::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class AnalyticsProblems {
    @ExceptionHandler(AnalyticsNotFoundException::class)
    fun notFound(ex: AnalyticsNotFoundException): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.message ?: "Not found"))

    @ExceptionHandler(AnalyticsRejectedException::class)
    fun rejected(ex: AnalyticsRejectedException): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(
            ProblemTypes.validation(ex.problems).apply { status = HttpStatus.UNPROCESSABLE_CONTENT.value() },
        )
}
