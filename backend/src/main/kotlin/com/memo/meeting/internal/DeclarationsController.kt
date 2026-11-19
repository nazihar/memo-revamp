package com.memo.meeting.internal

import com.memo.common.authz.SignedIn
import com.memo.common.ratelimit.RateLimit
import com.memo.common.ratelimit.RateLimiter
import com.memo.common.tenant.TenantContext
import com.memo.common.web.etagOf
import com.memo.common.web.ifMatchVersion
import com.memo.meeting.DeclarationHow
import com.memo.meeting.DeclarationIntent
import com.memo.meeting.RulingOutcome
import com.memo.system.Idempotency
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

data class DeclarationRequest(
    /** What the interest is, 1 to 1,000 characters. */
    val nature: String? = null,
    val intent: DeclarationIntent = DeclarationIntent.LEAVE,
    /** An officer records the declaration for this person (someone the meeting is for, or an officer). */
    val onBehalfOf: UUID? = null,
    /** Required on someone's behalf: AT_MEETING, IN_PERSON, EMAIL or PHONE. */
    val how: DeclarationHow? = null,
    /** On someone's behalf, optional: "email of 4 Dec", up to 200 characters. */
    val howNote: String? = null,
    /** Required for a secretary's correction once the meeting is held. */
    val reason: String? = null,
)

data class IntentRequest(
    val intent: DeclarationIntent,
)

data class RulingRequest(
    val outcome: RulingOutcome,
    /** Up to 500 characters. */
    val note: String? = null,
    /** A secretary records the chair's ruling given outside the app. */
    val onBehalf: Boolean = false,
    /** Required on the chair's behalf: AT_MEETING, IN_PERSON, EMAIL or PHONE. */
    val how: DeclarationHow? = null,
)

data class WithdrawDeclarationRequest(
    /** Why, 1 to 500 characters. */
    val reason: String? = null,
)

data class DeclarationReviewRequest(
    /** The latest published revision of the agenda. */
    val revision: Int,
)

/**
 * Declarations of interest (ADR-0062, design E04 §3): declaring on an item (for oneself, or recorded for someone by an
 * officer), changing one's intent, the chair's ruling, withdrawing with a reason, and the review of the agenda. Creates
 * need an `Idempotency-Key`; changes carry the declaration's version as `If-Match` and answer it with its new version as
 * the ETag. Writes share the agenda's per-person rate limit.
 */
@RestController
@Tag(name = "meeting")
class DeclarationsController(
    private val declarations: Declarations,
    private val rulings: DeclarationRulings,
    private val reviews: DeclarationReviews,
    private val views: DeclarationViews,
    private val calls: MeetingCalls,
    private val limiter: RateLimiter,
    private val idempotency: Idempotency,
    private val json: JsonMapper,
) {
    @GetMapping("/api/v1/meetings/{meetingId}/declarations/mine")
    @SignedIn
    @Operation(operationId = "getMyDeclarations", summary = "The actor's declarations of interest in a meeting, and the items to review")
    fun mine(
        @PathVariable meetingId: UUID,
    ): ResponseEntity<MyDeclarationsResponse> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(calls.read { reviews.mine(meetingId) })

    @PostMapping("/api/v1/meetings/{meetingId}/items/{itemId}/declarations")
    @SignedIn
    @Operation(operationId = "declareInterest", summary = "Declare an interest in an agenda item, or record one for someone")
    fun declare(
        @PathVariable meetingId: UUID,
        @PathVariable itemId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: DeclarationRequest,
    ): ResponseEntity<DeclarationResponse> {
        limit()
        val declared =
            calls.write {
                val id =
                    idempotency.once(
                        "meeting.declaration.declare",
                        key,
                        fingerprint("$meetingId\n$itemId\n${json.writeValueAsString(body)}"),
                    ) {
                        val input = DeclareInput(body.nature, body.intent, body.onBehalfOf, body.how, body.howNote, body.reason)
                        declarations.declare(meetingId, itemId, input).id.toString()
                    }
                current(meetingId, UUID.fromString(id))
            }
        return ResponseEntity.status(HttpStatus.CREATED).eTag(etagOf(declared.version)).body(declared)
    }

    @PostMapping("/api/v1/meetings/{meetingId}/declarations/{declarationId}/intent")
    @SignedIn
    @Operation(operationId = "changeDeclarationIntent", summary = "The declarant chooses to leave the room, or asks the chair to decide")
    fun intent(
        @PathVariable meetingId: UUID,
        @PathVariable declarationId: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: IntentRequest,
    ): ResponseEntity<DeclarationResponse> {
        val version = ifMatchVersion(ifMatch)
        return changed(meetingId) { declarations.changeIntent(meetingId, declarationId, version, body.intent) }
    }

    @PostMapping("/api/v1/meetings/{meetingId}/declarations/{declarationId}/ruling")
    @SignedIn
    @Operation(operationId = "ruleOnDeclaration", summary = "The chair rules on a declaration, or a secretary records the chair's ruling")
    fun ruling(
        @PathVariable meetingId: UUID,
        @PathVariable declarationId: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: RulingRequest,
    ): ResponseEntity<DeclarationResponse> {
        val version = ifMatchVersion(ifMatch)
        val input = RulingInput(body.outcome, body.note, body.onBehalf, body.how)
        return changed(meetingId) { rulings.rule(meetingId, declarationId, version, input) }
    }

    @PostMapping("/api/v1/meetings/{meetingId}/declarations/{declarationId}/withdraw")
    @SignedIn
    @Operation(operationId = "withdrawDeclaration", summary = "Withdraw a declaration of interest, with a reason")
    fun withdraw(
        @PathVariable meetingId: UUID,
        @PathVariable declarationId: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: WithdrawDeclarationRequest,
    ): ResponseEntity<DeclarationResponse> {
        val version = ifMatchVersion(ifMatch)
        return changed(meetingId) { declarations.withdraw(meetingId, declarationId, version, body.reason) }
    }

    @PostMapping("/api/v1/meetings/{meetingId}/declarations/review")
    @SignedIn
    @Operation(operationId = "confirmDeclarationReview", summary = "Confirm \"I have declared my interests\" for the latest revision")
    fun review(
        @PathVariable meetingId: UUID,
        @RequestBody body: DeclarationReviewRequest,
    ): ResponseEntity<MyDeclarationsResponse> {
        limit()
        return ResponseEntity.ok().body(calls.write { reviews.review(meetingId, body.revision) })
    }

    private fun changed(
        meetingId: UUID,
        change: () -> DeclarationRow,
    ): ResponseEntity<DeclarationResponse> {
        limit()
        val declaration = calls.write { current(meetingId, change().id) }
        return ResponseEntity.ok().eTag(etagOf(declaration.version)).body(declaration)
    }

    /** The declaration as the actor now sees it. */
    private fun current(
        meetingId: UUID,
        declarationId: UUID,
    ): DeclarationResponse {
        val scene = declarations.scene(meetingId)
        return views.response(declarations.declaration(scene, declarationId), scene)
    }

    private fun limit() {
        actorOrNull()?.let { limiter.consume(RateLimit.AGENDA_WRITE_PER_USER, "${TenantContext.current()}:$it") }
    }
}
