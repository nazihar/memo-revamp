package com.memo.forum.internal

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

/** Not there, or not visible to the actor: the same 404 either way, so ids do not leak whether a forum exists. */
class ForumNotFoundException(
    what: String = "forum",
) : RuntimeException("$what not found")

/** Well-formed input the forum rules refuse (422 `validation`, one entry per field). */
class ForumRejectedException(
    val problems: List<FieldProblem>,
) : RuntimeException("forum request rejected: ${problems.joinToString { "${it.field}:${it.code}" }}")

/** Problems of the forum endpoints. */
@RestControllerAdvice(basePackageClasses = [ForumProblems::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class ForumProblems {
    @ExceptionHandler(ForumNotFoundException::class)
    fun notFound(ex: ForumNotFoundException): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.message ?: "Not found"))

    @ExceptionHandler(ForumRejectedException::class)
    fun rejected(ex: ForumRejectedException): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(
            ProblemTypes.validation(ex.problems).apply { status = HttpStatus.UNPROCESSABLE_CONTENT.value() },
        )
}

/** The signed-in user acting, or null for the system (seeding). */
internal fun actorOrNull(): UUID? = (ActorContext.current().actor as? Actor.User)?.userId

internal fun actor(): UUID = checkNotNull(actorOrNull()) { "forums are changed by signed-in users" }

/** Throws [ForumRejectedException] when [problems] is not empty. */
internal fun requireNoProblems(problems: List<FieldProblem>) {
    if (problems.isNotEmpty()) throw ForumRejectedException(problems)
}

/** The request as [com.memo.system.Idempotency] compares it: with the actor, so another user's replay of a key is refused. */
internal fun fingerprint(request: String): String = "${actorOrNull()}\n$request"
