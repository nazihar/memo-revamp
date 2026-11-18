package com.memo.meeting.internal

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

/** Not there, or not visible to the actor: the same 404 either way, so ids do not leak whether a meeting exists. */
class MeetingNotFoundException(
    what: String = "meeting",
) : RuntimeException("$what not found")

/** Well-formed input the meeting rules refuse (422 `validation`, one entry per field). */
class MeetingRejectedException(
    val problems: List<FieldProblem>,
) : RuntimeException("meeting request rejected: ${problems.joinToString { "${it.field}:${it.code}" }}")

/** Problems of the meeting endpoints. */
@RestControllerAdvice(basePackageClasses = [MeetingProblems::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class MeetingProblems {
    @ExceptionHandler(MeetingNotFoundException::class)
    fun notFound(ex: MeetingNotFoundException): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.message ?: "Not found"))

    @ExceptionHandler(MeetingRejectedException::class)
    fun rejected(ex: MeetingRejectedException): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(
            ProblemTypes.validation(ex.problems).apply { status = HttpStatus.UNPROCESSABLE_CONTENT.value() },
        )
}

/** The signed-in user acting, or null for the system (listeners, seeding). */
internal fun actorOrNull(): UUID? = (ActorContext.current().actor as? Actor.User)?.userId

/** Throws [MeetingRejectedException] when [problems] is not empty. */
internal fun requireNoProblems(problems: List<FieldProblem>) {
    if (problems.isNotEmpty()) throw MeetingRejectedException(problems)
}

/** The request as [com.memo.system.Idempotency] compares it: with the actor, so another user's replay of a key is refused. */
internal fun fingerprint(request: String): String = "${actorOrNull()}\n$request"
