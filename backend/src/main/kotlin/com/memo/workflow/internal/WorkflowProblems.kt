package com.memo.workflow.internal

import com.memo.common.web.ProblemTypes
import com.memo.workflow.WorkflowRefusedException
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * The engine's refusals as Problem Details (design E01 §4), for every controller that calls the workflow: who may not act
 * is 403, unknown tasks 404, bad input 422, a wrong state 409; `reason` carries the engine's code.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class WorkflowProblems {
    @ExceptionHandler(WorkflowRefusedException::class)
    fun refused(ex: WorkflowRefusedException): ResponseEntity<ProblemDetail> {
        val (status, type) =
            when (ex.reason) {
                in FORBIDDEN -> HttpStatus.FORBIDDEN to ProblemTypes.FORBIDDEN
                in NOT_FOUND -> HttpStatus.NOT_FOUND to null
                in INVALID -> HttpStatus.UNPROCESSABLE_CONTENT to ProblemTypes.VALIDATION
                else -> HttpStatus.CONFLICT to ProblemTypes.CONFLICT
            }
        return ResponseEntity.status(status).body(
            (
                type?.let { ProblemTypes.of(status, it, "The action is not possible.") }
                    ?: ProblemDetail.forStatusAndDetail(status, "Not found")
            ).apply { setProperty("reason", ex.reason) },
        )
    }

    @ExceptionHandler(DefinitionNotFoundException::class, QueueNotFoundException::class)
    fun definitionNotFound(): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Not found"))

    /** A definition that does not validate (design E03 §5): one entry per problem, `field` being its JSON pointer. */
    @ExceptionHandler(DefinitionInvalidException::class)
    fun definitionInvalid(ex: DefinitionInvalidException): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(
            ProblemTypes.validation(ex.problems).apply {
                status = HttpStatus.UNPROCESSABLE_CONTENT.value()
                setProperty("reason", "DEFINITION_INVALID")
            },
        )

    private companion object {
        val FORBIDDEN = setOf("NOT_INITIATOR", "NOT_ASSIGNEE")
        val NOT_FOUND = setOf(WorkflowRunner.TASK_NOT_FOUND, WorkflowRunner.INSTANCE_NOT_FOUND)
        val INVALID =
            setOf(
                "REMARK_REQUIRED",
                "REMARK_TOO_LONG",
                "ROUTE_EMPTY",
                "ROUTE_TOO_LONG",
                "ROUTE_DUPLICATE",
                "INITIATOR_AS_APPROVER",
                "SAME_ASSIGNEE",
                "ASSIGNEE_IN_ROUND",
                "ASSIGNEE_NOT_ELIGIBLE",
            )
    }
}

/** Invalid input to the workflow's own endpoints (a malformed cursor or filter): 400 `validation`. */
@RestControllerAdvice(basePackageClasses = [WorkflowAdminController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class WorkflowInputProblems {
    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(ex: IllegalArgumentException): ResponseEntity<ProblemDetail> =
        ResponseEntity.badRequest().body(ProblemTypes.of(HttpStatus.BAD_REQUEST, ProblemTypes.VALIDATION, ex.message ?: "Invalid request."))
}
