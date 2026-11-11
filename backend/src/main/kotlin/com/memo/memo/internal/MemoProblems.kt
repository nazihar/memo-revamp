package com.memo.memo.internal

import com.memo.common.web.FieldProblem
import com.memo.common.web.ProblemTypes
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** Not there, or not visible to the actor: the same 404 either way, so ids do not leak whether a memo exists. */
class MemoNotFoundException(
    what: String = "memo",
) : RuntimeException("$what not found")

/** Well-formed input the memo rules refuse (422 `validation`, one entry per field, design E01 §5). */
class MemoRejectedException(
    val problems: List<FieldProblem>,
) : RuntimeException("memo request rejected: ${problems.joinToString { "${it.field}:${it.code}" }}")

/** Problems of the memo endpoints. */
@RestControllerAdvice(basePackageClasses = [MemoProblems::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class MemoProblems {
    @ExceptionHandler(MemoNotFoundException::class)
    fun notFound(ex: MemoNotFoundException): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.message ?: "Not found"))

    @ExceptionHandler(MemoRejectedException::class)
    fun rejected(ex: MemoRejectedException): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(
            ProblemTypes.validation(ex.problems).apply {
                status = HttpStatus.UNPROCESSABLE_CONTENT.value()
            },
        )

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(ex: IllegalArgumentException): ResponseEntity<ProblemDetail> =
        ResponseEntity.badRequest().body(ProblemTypes.of(HttpStatus.BAD_REQUEST, ProblemTypes.VALIDATION, ex.message ?: "Invalid request."))
}
