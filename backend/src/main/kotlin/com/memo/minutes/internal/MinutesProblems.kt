package com.memo.minutes.internal

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

/** Not there, or not visible to the actor: the same 404 either way (design E06 §4). */
class MinutesNotFoundException(
    what: String,
) : RuntimeException("$what not found")

/** Well-formed input the decision rules refuse (422 `validation`, one entry per field). */
class MinutesRejectedException(
    val problems: List<FieldProblem>,
) : RuntimeException("minutes request rejected: ${problems.joinToString { "${it.field}:${it.code}" }}")

/** Problems of the minutes endpoints. The meeting module's own (a meeting not found, a snapshot refused) are its advice's. */
@RestControllerAdvice(basePackageClasses = [MinutesProblems::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class MinutesProblems {
    @ExceptionHandler(MinutesNotFoundException::class)
    fun notFound(ex: MinutesNotFoundException): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.message ?: "Not found"))

    @ExceptionHandler(MinutesRejectedException::class)
    fun rejected(ex: MinutesRejectedException): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(
            ProblemTypes.validation(ex.problems).apply { status = HttpStatus.UNPROCESSABLE_CONTENT.value() },
        )
}

/** The signed-in user acting, or null for the system. */
internal fun actorOrNull(): UUID? = (ActorContext.current().actor as? Actor.User)?.userId

/** Throws [MinutesRejectedException] when [problems] is not empty. */
internal fun requireNoProblems(problems: List<FieldProblem>) {
    if (problems.isNotEmpty()) throw MinutesRejectedException(problems)
}

/** The request as `Idempotency` compares it: with the actor, so another user's replay of a key is refused. */
internal fun fingerprint(request: String): String = "${actorOrNull()}\n$request"
