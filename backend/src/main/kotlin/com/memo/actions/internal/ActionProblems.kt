package com.memo.actions.internal

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.web.FieldProblem
import com.memo.common.web.ProblemTypes
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.util.UUID

/** Not there, or not visible to the actor: the same 404 either way, so an action's existence is never revealed (design E07 §3). */
class ActionNotFoundException(
    what: String,
) : RuntimeException("$what not found")

/** Well-formed input the action rules refuse (422 `validation`, one entry per field). */
class ActionRejectedException(
    val problems: List<FieldProblem>,
) : RuntimeException("action request rejected: ${problems.joinToString { "${it.field}:${it.code}" }}")

/** Problems of the actions endpoints. The meeting module's own (a meeting not found) are its advice's. */
@RestControllerAdvice(basePackageClasses = [ActionProblems::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class ActionProblems {
    @ExceptionHandler(ActionNotFoundException::class)
    fun notFound(ex: ActionNotFoundException): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.message ?: "Not found"))

    @ExceptionHandler(ActionRejectedException::class)
    fun rejected(ex: ActionRejectedException): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(
            ProblemTypes.validation(ex.problems).apply { status = HttpStatus.UNPROCESSABLE_CONTENT.value() },
        )
}

/** The signed-in user acting, or null for the system. */
internal fun actorOrNull(): UUID? = (ActorContext.current().actor as? Actor.User)?.userId

/** The signed-in user acting; the actions' writes are always a person's. */
internal fun actor(): UUID = checkNotNull(actorOrNull()) { "an action is changed by a signed-in user" }

/** Throws [ActionRejectedException] when [problems] is not empty. */
internal fun requireNoProblems(problems: List<FieldProblem>) {
    if (problems.isNotEmpty()) throw ActionRejectedException(problems)
}

/** The request as `Idempotency` compares it: with the actor, so another user's replay of a key is refused. */
internal fun fingerprint(request: String): String = "${actorOrNull()}\n$request"

/** 404 for [what] unless [condition] holds. */
internal fun notFoundUnless(
    condition: Boolean,
    what: String,
) {
    if (!condition) throw ActionNotFoundException(what)
}

/** 403 unless [condition] holds: for lacking [permission], or with a stable [reason] and its [detail]. */
internal fun forbidUnless(
    condition: Boolean,
    permission: com.memo.common.authz.Permission? = null,
    reason: String? = null,
    detail: String? = null,
) {
    if (!condition) {
        throw com.memo.common.authz
            .ForbiddenException(permission, reason, detail)
    }
}

/** 422 with [problem] when there is one. */
internal fun rejectIf(problem: FieldProblem?) {
    if (problem != null) throw ActionRejectedException(listOf(problem))
}
