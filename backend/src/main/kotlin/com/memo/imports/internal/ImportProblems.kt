package com.memo.imports.internal

import com.memo.common.web.FieldProblem
import com.memo.common.web.ProblemTypes
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** No such batch in the tenant (or an intake entry of another batch): 404. */
class ImportBatchNotFoundException : RuntimeException("import batch not found")

/** Well-formed input the import's rules refuse (422 `validation`, one entry per field, design E06 §10). */
class ImportRejectedException(
    val problems: List<FieldProblem>,
) : RuntimeException("import request rejected: ${problems.joinToString { "${it.field}:${it.code}" }}")

/** Problems of the import endpoints. */
@RestControllerAdvice(basePackageClasses = [ImportProblems::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class ImportProblems {
    @ExceptionHandler(ImportBatchNotFoundException::class)
    fun notFound(ex: ImportBatchNotFoundException): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.message ?: "Not found"))

    @ExceptionHandler(ImportRejectedException::class)
    fun rejected(ex: ImportRejectedException): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(
            ProblemTypes.validation(ex.problems).apply { status = HttpStatus.UNPROCESSABLE_CONTENT.value() },
        )

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(ex: IllegalArgumentException): ResponseEntity<ProblemDetail> =
        ResponseEntity.badRequest().body(ProblemTypes.of(HttpStatus.BAD_REQUEST, ProblemTypes.VALIDATION, ex.message ?: "Invalid request."))
}
