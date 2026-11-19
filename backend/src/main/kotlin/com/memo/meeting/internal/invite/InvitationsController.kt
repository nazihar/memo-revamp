package com.memo.meeting.internal.invite

import com.memo.common.authz.SignedIn
import com.memo.common.ratelimit.RateLimit
import com.memo.common.ratelimit.RateLimiter
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.meeting.internal.fingerprint
import com.memo.system.Idempotency
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class ResendInvitationsRequest(
    /** The people to resend to; null: everyone who holds the invitation. */
    val userIds: List<UUID>? = null,
)

/**
 * Meeting invitations (ADR-0063, design E05 §3): the officers' panel, the secretaries' resend (an `Idempotency-Key`; at
 * most once per person per 10 minutes, and a few times an hour per meeting), and "Add to my calendar". An invisible
 * meeting is 404; one the actor sees but may not act on is 403.
 */
@RestController
@Tag(name = "meeting")
class InvitationsController(
    private val panel: InvitationPanel,
    private val transactions: TenantTransactions,
    private val idempotency: Idempotency,
    private val limiter: RateLimiter,
) {
    @GetMapping("/api/v1/meetings/{meetingId}/invitations")
    @SignedIn
    @Operation(operationId = "getMeetingInvitations", summary = "Who was invited to a meeting, with what and when (officers)")
    fun invitations(
        @PathVariable meetingId: UUID,
    ): ResponseEntity<InvitationsResponse> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(transactions.readInTenant(TenantContext.current()) { panel.panel(meetingId) })

    @PostMapping("/api/v1/meetings/{meetingId}/invitations/resend")
    @SignedIn
    @Operation(operationId = "resendMeetingInvitations", summary = "Send the current invitation again (secretaries)")
    fun resend(
        @PathVariable meetingId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody(required = false) body: ResendInvitationsRequest?,
    ): ResponseEntity<InvitationsResponse> {
        limiter.consume(RateLimit.MEETING_RESEND_PER_MEETING, "${TenantContext.current()}:$meetingId")
        val users = body?.userIds?.toSet()
        val result =
            transactions.inTenant(TenantContext.current()) {
                idempotency.once("meeting.invitations.resend", key, fingerprint("$meetingId\n${users?.sorted()}")) {
                    panel.resend(meetingId, users)
                    "ok"
                }
                panel.panel(meetingId)
            }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result)
    }

    @GetMapping("/api/v1/meetings/{meetingId}/calendar.ics", produces = [CALENDAR])
    @SignedIn
    @Operation(operationId = "getMeetingCalendarFile", summary = "The meeting as a calendar file, for \"Add to my calendar\"")
    fun calendar(
        @PathVariable meetingId: UUID,
    ): ResponseEntity<ByteArray> {
        val file = transactions.readInTenant(TenantContext.current()) { panel.calendar(meetingId) }
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .contentType(MediaType.parseMediaType("$CALENDAR; charset=UTF-8"))
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition
                    .attachment()
                    .filename(FILE_NAME)
                    .build()
                    .toString(),
            ).header("X-Content-Type-Options", "nosniff")
            .body(file)
    }

    private companion object {
        const val CALENDAR = "text/calendar"
        const val FILE_NAME = "meeting.ics"
    }
}
