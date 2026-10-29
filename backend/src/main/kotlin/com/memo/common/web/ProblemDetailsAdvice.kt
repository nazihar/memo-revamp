package com.memo.common.web

import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.ratelimit.RateLimitedException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

/**
 * RFC 9457 Problem Details for every error. Spring's [ResponseEntityExceptionHandler] covers framework exceptions
 * (404, 405, 400 …); anything unexpected becomes a generic 500 that never carries the exception message or stack trace.
 */
@RestControllerAdvice
class ProblemDetailsAdvice : ResponseEntityExceptionHandler() {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 429 with `Retry-After` in whole seconds (ADR-0025). */
    @ExceptionHandler(RateLimitedException::class)
    fun rateLimited(ex: RateLimitedException): ResponseEntity<ProblemDetail> {
        val seconds = maxOf(1, (ex.retryAfter.toMillis() + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND)
        return ResponseEntity
            .status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, seconds.toString())
            .body(ProblemTypes.of(HttpStatus.TOO_MANY_REQUESTS, ProblemTypes.RATE_LIMITED, "Too many attempts. Try again later."))
    }

    @ExceptionHandler(StepUpRequiredException::class)
    fun stepUpRequired(ex: StepUpRequiredException): ResponseEntity<ProblemDetail> {
        val problem =
            ProblemTypes.of(HttpStatus.UNAUTHORIZED, ProblemTypes.STEP_UP_REQUIRED, "Confirm it is you to continue.").apply {
                setProperty("stepUpWindowSeconds", ex.window.toSeconds())
            }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem)
    }

    /** A guarded write lost to a concurrent change: the client reloads and decides again (AGENTS.md §4). */
    @ExceptionHandler(ConcurrentModificationException::class)
    fun conflict(): ResponseEntity<ProblemDetail> =
        ResponseEntity
            .status(HttpStatus.PRECONDITION_FAILED)
            .body(
                ProblemTypes.of(
                    HttpStatus.PRECONDITION_FAILED,
                    ProblemTypes.CONFLICT,
                    "Someone else changed this in the meantime. Reload and try again.",
                ),
            )

    @ExceptionHandler(PreconditionRequiredException::class)
    fun preconditionRequired(): ResponseEntity<ProblemDetail> =
        ResponseEntity
            .status(HttpStatus.PRECONDITION_REQUIRED)
            .body(ProblemTypes.of(HttpStatus.PRECONDITION_REQUIRED, ProblemTypes.CONFLICT, "Send If-Match with the version you last saw."))

    @ExceptionHandler(IdempotencyKeyReusedException::class)
    fun idempotencyKeyReused(): ResponseEntity<ProblemDetail> =
        ResponseEntity
            .status(HttpStatus.UNPROCESSABLE_CONTENT)
            .body(
                ProblemTypes.of(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    ProblemTypes.CONFLICT,
                    "This Idempotency-Key was used for a different request.",
                ),
            )

    @ExceptionHandler(Exception::class)
    fun unexpected(ex: Exception): ProblemDetail {
        log.error("Unhandled exception", ex)
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.")
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
    }
}
