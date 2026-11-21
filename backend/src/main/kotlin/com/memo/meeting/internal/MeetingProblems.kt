package com.memo.meeting.internal

import com.memo.common.authz.ForbiddenException
import com.memo.common.authz.Permission
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

/** Publishing refused: these papers have no readable PDF (their rendition failed or is not possible), 409 `PAPER_NOT_READABLE`. */
class PaperNotReadableException(
    val paperIds: List<UUID>,
) : RuntimeException("papers not readable: $paperIds")

/** The actor is recused from the item they act on (ADR-0062, design E04 §3): 403 `RECUSED`. */
class RecusedException : RuntimeException("recused from the item")

/** The person already holds an open declaration on the item (design E04 §1.1): 409 `ONE_OPEN`, naming it. */
class OneOpenException(
    val declarationId: UUID?,
) : RuntimeException("an open declaration exists")

/**
 * Problems of the meeting endpoints, and of the meeting module's published API wherever it is called from (E06's
 * decisions take quorum snapshots and read meetings through it): the exceptions handled are the meeting module's own.
 */
@RestControllerAdvice
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

    @ExceptionHandler(PaperNotReadableException::class)
    fun notReadable(ex: PaperNotReadableException): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(
            ProblemTypes
                .of(HttpStatus.CONFLICT, ProblemTypes.CONFLICT, "Some papers cannot be read as PDF: replace or remove them first.")
                .apply {
                    setProperty("reason", "PAPER_NOT_READABLE")
                    setProperty("paperIds", ex.paperIds.map { it.toString() })
                },
        )

    @ExceptionHandler(RecusedException::class)
    fun recused(): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(HttpStatus.FORBIDDEN).body(
            ProblemTypes
                .of(HttpStatus.FORBIDDEN, ProblemTypes.FORBIDDEN, "You declared an interest in this item: another officer handles it.")
                .apply { setProperty("reason", "RECUSED") },
        )

    @ExceptionHandler(OneOpenException::class)
    fun oneOpen(ex: OneOpenException): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(
            ProblemTypes
                .of(HttpStatus.CONFLICT, ProblemTypes.CONFLICT, "There is already an open declaration for this person on this item.")
                .apply {
                    setProperty("reason", "ONE_OPEN")
                    ex.declarationId?.let { setProperty("declarationId", it.toString()) }
                },
        )
}

/** Throws the deny-by-default 403 when [condition] holds. */
internal fun forbidIf(condition: Boolean) {
    if (condition) throw ForbiddenException(Permission.FORUMS_MANAGE)
}

/** The signed-in user acting, or null for the system (listeners, seeding). */
internal fun actorOrNull(): UUID? = (ActorContext.current().actor as? Actor.User)?.userId

/** Throws [MeetingRejectedException] when [problems] is not empty. */
internal fun requireNoProblems(problems: List<FieldProblem>) {
    if (problems.isNotEmpty()) throw MeetingRejectedException(problems)
}

/** The request as [com.memo.system.Idempotency] compares it: with the actor, so another user's replay of a key is refused. */
internal fun fingerprint(request: String): String = "${actorOrNull()}\n$request"
