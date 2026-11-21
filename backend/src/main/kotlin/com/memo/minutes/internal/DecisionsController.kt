package com.memo.minutes.internal

import com.memo.common.authz.SignedIn
import com.memo.common.ratelimit.RateLimit
import com.memo.common.ratelimit.RateLimiter
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.etagOf
import com.memo.common.web.ifMatchVersion
import com.memo.forum.OutcomeKind
import com.memo.system.Idempotency
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** E05's override of a missing quorum: the reason, 1 to 500 characters (the committee type must allow it). */
data class DecisionOverrideRequest(
    val reason: String? = null,
)

/** A decision as the secretary records or corrects it (design E06 §1.1, §3). */
data class DecisionRequest(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val outcome: OutcomeKind,
    /** The resolution as it will be minuted, up to 4,000 characters; required for an outcome marked as a resolution. */
    val text: String? = null,
    /** 0 to 20 lines of 1 to 1,000 characters; at least one for APPROVED_WITH_CONDITIONS. */
    val conditions: List<String> = emptyList(),
    val validUntil: LocalDate? = null,
    /** DEFERRED: to a later meeting of the committee, a date, or the next meeting. */
    val deferTo: DeferTarget? = null,
    /** REFERRED: the committee, and why. */
    val referToForumId: UUID? = null,
    val referReason: String? = null,
    val voteMode: VoteMode = VoteMode.CONSENSUS,
    /** With a vote only, each optional and not below zero; together not more than the voting members present. */
    val votesFor: Int? = null,
    val votesAgainst: Int? = null,
    val votesAbstain: Int? = null,
    /** People present who may vote and asked that their dissent be recorded, each with an optional reason. */
    val dissent: List<Dissent> = emptyList(),
    val abstentions: List<UUID> = emptyList(),
    /** When the item was taken; default its last start ("Now: item n"), else now while the meeting is in progress. */
    val decidedAt: Instant? = null,
    /** A decision taken without a quorum that this (quorate) one ratifies. */
    val ratifiesDecisionId: UUID? = null,
    val override: DecisionOverrideRequest? = null,
    /** Required for a correction: why, 1 to 500 characters. */
    val reason: String? = null,
    /** A correction takes a new quorum snapshot. */
    val recheckQuorum: Boolean = false,
    /** Confirms that the change sends minutes out of the draft back to it. */
    val returnMinutesToDraft: Boolean = false,
) {
    fun input() =
        DecisionInput(
            outcome = outcome,
            text = text,
            conditions = conditions,
            validUntil = validUntil,
            deferTo = deferTo,
            referToForumId = referToForumId,
            referReason = referReason,
            voteMode = voteMode,
            votesFor = votesFor,
            votesAgainst = votesAgainst,
            votesAbstain = votesAbstain,
            dissent = dissent,
            abstentions = abstentions,
            decidedAt = decidedAt,
            ratifiesDecisionId = ratifiesDecisionId,
            overrideReason = override?.reason,
            reason = reason,
            recheckQuorum = recheckQuorum,
            returnMinutesToDraft = returnMinutesToDraft,
        )
}

/** Deciding a consent section in one action. */
data class ConsentDecisionRequest(
    /** When the section was taken; default each item's last start, else now while the meeting is in progress. */
    val decidedAt: Instant? = null,
    val returnMinutesToDraft: Boolean = false,
)

/** The notes on an item, as the screen autosaves them: plain text, up to 10,000 characters. */
data class ItemNoteRequest(
    val text: String? = null,
)

/**
 * The decisions of a meeting (ADR-0065, design E06 §1.1, §1.2, §3): the decisions list as each person may see it, recording
 * and correcting an item's decision (If-Match once it exists, the decision's version as the ETag), its history, a consent
 * section in one action, the secretary's notes per item (If-Match on the version read), and the committee's decisions to be
 * ratified. Writes need an `Idempotency-Key` and share the agenda's per-person rate limit.
 */
@RestController
@Tag(name = "minutes")
class DecisionsController(
    private val recorder: DecisionRecorder,
    private val consent: ConsentDecisions,
    private val notes: ItemNotes,
    private val access: MinutesAccess,
    private val transactions: TenantTransactions,
    private val idempotency: Idempotency,
    private val limiter: RateLimiter,
    private val json: JsonMapper,
) {
    @GetMapping("/api/v1/meetings/{meetingId}/decisions")
    @SignedIn
    @Operation(operationId = "getMeetingDecisions", summary = "A meeting's decisions as the viewer may see them")
    fun decisions(
        @PathVariable meetingId: UUID,
    ): ResponseEntity<MeetingDecisionsResponse> = noStore(read { access.decisions(meetingId) })

    @PutMapping("/api/v1/meetings/{meetingId}/items/{itemId}/decision")
    @SignedIn
    @Operation(operationId = "recordItemDecision", summary = "Record or correct an item's decision (secretaries)")
    fun record(
        @PathVariable meetingId: UUID,
        @PathVariable itemId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: DecisionRequest,
    ): ResponseEntity<DecisionResponse> {
        limit()
        val request = "$meetingId\n$itemId\n$ifMatch\n${json.writeValueAsString(body)}"
        val decision =
            transactions.inTenant(TenantContext.current()) {
                idempotency.once("minutes.decision.record", key, fingerprint(request)) {
                    recorder
                        .record(meetingId, itemId, body.input(), ifMatch?.let(::ifMatchVersion))
                        .decision.id
                        .toString()
                }
                access.decision(meetingId, itemId)
            }
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .eTag(etagOf(decision.version))
            .body(decision)
    }

    @GetMapping("/api/v1/meetings/{meetingId}/items/{itemId}/decision/entries")
    @SignedIn
    @Operation(operationId = "getItemDecisionHistory", summary = "An item's decision with its corrections (officers, forum.read)")
    fun history(
        @PathVariable meetingId: UUID,
        @PathVariable itemId: UUID,
    ): ResponseEntity<DecisionHistoryResponse> = noStore(read { access.history(meetingId, itemId) })

    @PostMapping("/api/v1/meetings/{meetingId}/sections/{sectionId}/decision")
    @SignedIn
    @Operation(operationId = "decideConsentSection", summary = "Decide a consent section's items in one action (secretaries)")
    fun section(
        @PathVariable meetingId: UUID,
        @PathVariable sectionId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: ConsentDecisionRequest,
    ): ResponseEntity<MeetingDecisionsResponse> {
        limit()
        val request = "$meetingId\n$sectionId\n${json.writeValueAsString(body)}"
        val decided =
            transactions.inTenant(TenantContext.current()) {
                val ids =
                    idempotency.once("minutes.consent.decide", key, fingerprint(request)) {
                        consent
                            .decide(
                                meetingId,
                                sectionId,
                                body.decidedAt,
                                body.returnMinutesToDraft,
                            ).joinToString(",") { it.decision.itemId.toString() }
                    }
                val items =
                    ids
                        .split(',')
                        .filter { it.isNotEmpty() }
                        .map(UUID::fromString)
                        .toSet()
                access.decisions(meetingId).let { all -> all.copy(decisions = all.decisions.filter { it.itemId in items }) }
            }
        return noStore(decided)
    }

    @GetMapping("/api/v1/meetings/{meetingId}/items/{itemId}/notes")
    @SignedIn
    @Operation(operationId = "getItemNotes", summary = "The secretary's notes on an item (secretaries not recused)")
    fun notes(
        @PathVariable meetingId: UUID,
        @PathVariable itemId: UUID,
    ): ResponseEntity<ItemNoteResponse> {
        val note = read { access.note(itemId, notes.read(meetingId, itemId)) }
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .eTag(etagOf(note.seq.toLong()))
            .body(note)
    }

    @PutMapping("/api/v1/meetings/{meetingId}/items/{itemId}/notes")
    @SignedIn
    @Operation(operationId = "saveItemNotes", summary = "Save the notes on an item as a new version (secretaries not recused)")
    fun saveNotes(
        @PathVariable meetingId: UUID,
        @PathVariable itemId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: ItemNoteRequest,
    ): ResponseEntity<ItemNoteResponse> {
        limit()
        val seq = ifMatchVersion(ifMatch).toInt()
        val request = "$meetingId\n$itemId\n$seq\n${json.writeValueAsString(body)}"
        val note =
            transactions.inTenant(TenantContext.current()) {
                idempotency.once("minutes.notes.save", key, fingerprint(request)) {
                    notes.save(meetingId, itemId, seq, body.text).seq.toString()
                }
                access.note(itemId, notes.read(meetingId, itemId))
            }
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .eTag(etagOf(note.seq.toLong()))
            .body(note)
    }

    @GetMapping("/api/v1/forums/{forumId}/decisions/to-be-ratified")
    @SignedIn
    @Operation(operationId = "getDecisionsToBeRatified", summary = "A committee's decisions taken without a quorum, to be ratified")
    fun toBeRatified(
        @PathVariable forumId: UUID,
    ): ResponseEntity<List<ToBeRatifiedResponse>> = noStore(read { access.toBeRatified(forumId) })

    private fun <T : Any> read(block: () -> T): T = transactions.readInTenant(TenantContext.current(), block)

    private fun <T : Any> noStore(body: T): ResponseEntity<T> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)

    private fun limit() {
        actorOrNull()?.let { limiter.consume(RateLimit.AGENDA_WRITE_PER_USER, "${TenantContext.current()}:$it") }
    }
}
