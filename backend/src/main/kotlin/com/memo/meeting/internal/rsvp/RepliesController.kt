package com.memo.meeting.internal.rsvp

import com.memo.common.authz.SignedIn
import com.memo.common.ratelimit.RateLimit
import com.memo.common.ratelimit.RateLimiter
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.etagOf
import com.memo.common.web.ifMatchVersion
import com.memo.meeting.internal.actorOrNull
import com.memo.meeting.internal.fingerprint
import com.memo.system.Idempotency
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

data class MyRsvpRequest(
    val answer: RsvpAnswer,
    /** With APOLOGIES, for a member whose seat has an alternate: the alternate attends in their place. */
    val alternateAttends: Boolean = false,
    /** To the secretaries, up to 300 characters ("I will join after 11:00"). */
    val note: String? = null,
)

data class RecordRsvpRequest(
    val answer: RsvpAnswer,
    val alternateAttends: Boolean = false,
    /** The person's note, up to 300 characters. */
    val note: String? = null,
    /** How they told you: EMAIL, PHONE, IN_PERSON or OTHER. */
    val how: RsvpHow? = null,
    /** "called the office", up to 200 characters. */
    val howNote: String? = null,
)

/**
 * Replies to meeting invitations (ADR-0063, design E05 §3): the actor's own answer, an answer recorded on someone's behalf
 * by a secretary, and the replies with their counts. Answers need an `Idempotency-Key`, and once one exists its version
 * as `If-Match`; they are answered with the new version as the ETag. Writes share the agenda's per-person rate limit.
 */
@RestController
@Tag(name = "meeting")
class RepliesController(
    private val replies: Replies,
    private val transactions: TenantTransactions,
    private val idempotency: Idempotency,
    private val limiter: RateLimiter,
    private val json: JsonMapper,
) {
    @GetMapping("/api/v1/meetings/{meetingId}/rsvp/mine")
    @SignedIn
    @Operation(operationId = "getMyMeetingRsvp", summary = "The actor's answer to \"Will you attend?\" and what they may answer")
    fun mine(
        @PathVariable meetingId: UUID,
    ): ResponseEntity<MyRsvpResponse> {
        val mine = transactions.readInTenant(TenantContext.current()) { replies.mine(meetingId) }
        return ok(mine.version, mine)
    }

    @PutMapping("/api/v1/meetings/{meetingId}/rsvp/mine")
    @SignedIn
    @Operation(operationId = "answerMeetingRsvp", summary = "Answer \"Will you attend?\" for oneself (If-Match once answered)")
    fun answer(
        @PathVariable meetingId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: MyRsvpRequest,
    ): ResponseEntity<MyRsvpResponse> {
        limit()
        val input = RsvpInput(body.answer, body.alternateAttends, body.note)
        val mine =
            transactions.inTenant(TenantContext.current()) {
                idempotency.once("meeting.rsvp.answer", key, fingerprint("$meetingId\n$ifMatch\n${json.writeValueAsString(body)}")) {
                    replies.answer(meetingId, ifMatch?.let(::ifMatchVersion), input)
                    "ok"
                }
                replies.mine(meetingId)
            }
        return ok(mine.version, mine)
    }

    @PutMapping("/api/v1/meetings/{meetingId}/rsvps/{userId}")
    @SignedIn
    @Operation(operationId = "recordMeetingRsvp", summary = "Record an answer someone gave by email, phone or in person (secretaries)")
    fun record(
        @PathVariable meetingId: UUID,
        @PathVariable userId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: RecordRsvpRequest,
    ): ResponseEntity<ReplyResponse> {
        limit()
        val input = RsvpInput(body.answer, body.alternateAttends, body.note, body.how, body.howNote)
        val reply =
            transactions.inTenant(TenantContext.current()) {
                idempotency.once(
                    "meeting.rsvp.record",
                    key,
                    fingerprint("$meetingId\n$userId\n$ifMatch\n${json.writeValueAsString(body)}"),
                ) {
                    replies.record(meetingId, userId, ifMatch?.let(::ifMatchVersion), input)
                    "ok"
                }
                checkNotNull(replies.list(meetingId).people).single { it.userId == userId }
            }
        return ok(reply.version, reply)
    }

    @GetMapping("/api/v1/meetings/{meetingId}/rsvps")
    @SignedIn
    @Operation(operationId = "getMeetingRsvps", summary = "A meeting's replies: every answer for officers, the counts for the audience")
    fun list(
        @PathVariable meetingId: UUID,
    ): ResponseEntity<RepliesResponse> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(transactions.readInTenant(TenantContext.current()) { replies.list(meetingId) })

    private fun <T : Any> ok(
        version: Long?,
        body: T,
    ): ResponseEntity<T> {
        val response = ResponseEntity.ok().cacheControl(CacheControl.noStore())
        return (version?.let { response.eTag(etagOf(it)) } ?: response).body(body)
    }

    private fun limit() {
        actorOrNull()?.let { limiter.consume(RateLimit.AGENDA_WRITE_PER_USER, "${TenantContext.current()}:$it") }
    }
}
