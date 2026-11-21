package com.memo.meeting.internal

import com.memo.common.authz.SignedIn
import com.memo.common.web.etagOf
import com.memo.common.web.ifMatchVersion
import com.memo.meeting.MeetingStatus
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class ChairAgreementRequest(
    /** A secretary records the chair's agreement given outside the app. */
    val onBehalf: Boolean = false,
    /** How it was given ("by email on 4 Dec"); required on the chair's behalf. */
    val note: String? = null,
)

data class ChairCommentRequest(
    val text: String? = null,
)

data class LateDecisionRequest(
    val agree: Boolean,
    val onBehalf: Boolean = false,
    val note: String? = null,
)

/** The decider's own answer on a late item, or on a late paper of it ([paperId]). */
data class LateRequestDecisionRequest(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val itemId: UUID,
    val paperId: UUID? = null,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val agree: Boolean,
)

data class PublishAgendaRequest(
    /** What changed, in one line: required from revision 2. */
    val note: String? = null,
    /** Tell the audience; off for a minor correction. */
    val notify: Boolean = true,
)

/** An item or paper waiting for the chair: left out of the revision. */
data class AwaitingChairResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val itemId: UUID,
    /** Set for a late paper on an item already on the agenda. */
    val paperId: UUID?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val title: String,
)

/** An item the revision would change, or take off ([removed], with its old number). */
data class ItemChangeResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val itemId: UUID,
    val number: String?,
    /** Its number in the revision before. */
    val previousNumber: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val title: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val removed: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val flags: List<ItemFlag>,
)

/** What publishing now would do (design E03 §1.3), for the publish dialog: the change summary and what refuses it. */
data class PublishPreviewResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val meetingId: UUID,
    /** Whether the agenda has been started. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val started: Boolean,
    /** The number the next revision would take. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val revision: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val noteRequired: Boolean,
    /** The meeting is still a draft: schedule it first. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val meetingNotScheduled: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val chairAgreementMissing: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val nothingToPublish: Boolean,
    /** Papers without a readable PDF: publishing is refused until they are replaced or removed. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val unreadablePapers: List<UUID>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val awaitingChair: List<AwaitingChairResponse>,
    /** Changes against the last revision (none before the first). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val changes: List<ItemChangeResponse>,
    /** The agenda's version, to publish with. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val version: Long,
)

/**
 * The chair's part and publishing (ADR-0060, design E03 §1.3, §1.4, §3): the chair agrees the agenda (or a secretary
 * records it on the chair's behalf), comments to the secretaries and decides late papers; the secretaries preview and
 * publish revisions. Changes carry the agenda's version as `If-Match`; publishing and comments need an `Idempotency-Key`.
 * Each answers the whole draft with its new version as the ETag.
 */
@RestController
@Tag(name = "meeting")
class AgendaPublishController(
    private val chair: AgendaChair,
    private val publisher: AgendaPublisher,
    private val calls: AgendaCalls,
    private val lateRequests: LateRequests,
) {
    @PostMapping("/api/v1/meetings/{meetingId}/agenda/chair-agreement")
    @SignedIn
    @Operation(operationId = "agreeAgenda", summary = "The chair agrees the agenda's first publication, or a secretary records it")
    fun agree(
        @PathVariable meetingId: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: ChairAgreementRequest,
    ): ResponseEntity<AgendaDraftResponse> {
        val version = ifMatchVersion(ifMatch)
        return calls.changed(meetingId) { chair.agree(meetingId, version, body.onBehalf, body.note) }
    }

    @PostMapping("/api/v1/meetings/{meetingId}/agenda/chair-comments")
    @SignedIn
    @Operation(operationId = "commentOnAgenda", summary = "The chair comments on the draft agenda to the secretaries")
    fun comment(
        @PathVariable meetingId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: ChairCommentRequest,
    ): ResponseEntity<AgendaDraftResponse> =
        calls.created(meetingId, "meeting.agenda.comment", key, calls.json(body)) {
            chair.comment(meetingId, body.text)
            null
        }

    @PostMapping("/api/v1/meetings/{meetingId}/agenda/items/{itemId}/late-decision")
    @SignedIn
    @Operation(operationId = "decideLateItem", summary = "Agree or decline an item waiting for the chair")
    fun decideItem(
        @PathVariable meetingId: UUID,
        @PathVariable itemId: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: LateDecisionRequest,
    ): ResponseEntity<AgendaDraftResponse> {
        val version = ifMatchVersion(ifMatch)
        return calls.changed(meetingId) { chair.decideItem(meetingId, itemId, version, body.decision()) }
    }

    @PostMapping("/api/v1/meetings/{meetingId}/agenda/papers/{paperId}/late-decision")
    @SignedIn
    @Operation(operationId = "decideLatePaper", summary = "Agree or decline a paper waiting for the chair")
    fun decidePaper(
        @PathVariable meetingId: UUID,
        @PathVariable paperId: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: LateDecisionRequest,
    ): ResponseEntity<AgendaDraftResponse> {
        val version = ifMatchVersion(ifMatch)
        return calls.changed(meetingId) { chair.decidePaper(meetingId, paperId, version, body.decision()) }
    }

    @GetMapping("/api/v1/meetings/{meetingId}/agenda/late-requests")
    @SignedIn
    @Operation(
        operationId = "getMyLateRequests",
        summary = "The late items and papers waiting for the actor's decision (the chair, or the vice-chair when the chair is recused)",
    )
    fun lateRequests(
        @PathVariable meetingId: UUID,
    ): ResponseEntity<LateRequestsResponse> {
        val requests = calls.read { lateRequests.mine(meetingId) }
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .eTag(etagOf(requests.version))
            .body(requests)
    }

    @PostMapping("/api/v1/meetings/{meetingId}/agenda/late-requests/decisions")
    @SignedIn
    @Operation(
        operationId = "decideLateRequest",
        summary = "The person who decides for the item agrees or declines a late item or paper, answered with their late requests",
    )
    fun decideLateRequest(
        @PathVariable meetingId: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: LateRequestDecisionRequest,
    ): ResponseEntity<LateRequestsResponse> {
        val version = ifMatchVersion(ifMatch)
        val requests =
            calls.write {
                lateRequests.decide(meetingId, body.itemId, body.paperId, body.agree, version)
                lateRequests.mine(meetingId)
            }
        return ResponseEntity.ok().eTag(etagOf(requests.version)).body(requests)
    }

    @GetMapping("/api/v1/meetings/{meetingId}/agenda/publish-preview")
    @SignedIn
    @Operation(operationId = "previewAgendaPublication", summary = "What publishing the agenda now would change, and what refuses it")
    fun preview(
        @PathVariable meetingId: UUID,
    ): ResponseEntity<PublishPreviewResponse> {
        val preview = calls.read { previewOf(meetingId) }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(preview)
    }

    @PostMapping("/api/v1/meetings/{meetingId}/agenda/publish")
    @SignedIn
    @Operation(operationId = "publishAgenda", summary = "Publish the next revision of the agenda")
    fun publish(
        @PathVariable meetingId: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: PublishAgendaRequest,
    ): ResponseEntity<AgendaDraftResponse> {
        val version = ifMatchVersion(ifMatch)
        return calls.created(meetingId, "meeting.agenda.publish", key, "$version\n${calls.json(body)}") {
            publisher.publish(meetingId, version, body.note, body.notify)
            null
        }
    }

    /**
     * What publishing now would do, as the actor may read it: items they are recused from (design E04 §1.7) show only what
     * their line shows (the title unless hidden, and the line's flags), and their papers and lateness are left out.
     */
    private fun previewOf(meetingId: UUID): PublishPreviewResponse {
        val (meeting, publication) = publisher.preview(meetingId)
        val version = publication?.version ?: 0
        val snapshot = publication?.snapshot
        val withheld = publication?.recused.orEmpty()
        val paperItems = snapshot?.papers.orEmpty().associate { it.paperId to it.itemId }
        return PublishPreviewResponse(
            meetingId = meeting.id,
            started = publication != null,
            revision = publication?.revision ?: 1,
            noteRequired = (publication?.revision ?: 1) > 1,
            meetingNotScheduled = meeting.status == MeetingStatus.DRAFT,
            chairAgreementMissing = publication?.chairAgreementMissing ?: false,
            nothingToPublish = publication?.nothingNew ?: true,
            unreadablePapers = publication?.unreadable.orEmpty().filter { paperItems[it] !in withheld },
            awaitingChair =
                publication
                    ?.awaiting
                    .orEmpty()
                    .filter { (item, _) -> item.id !in withheld }
                    .map { (item, paper) -> AwaitingChairResponse(item.id, paper?.id, paper?.title ?: item.title) },
            changes =
                snapshot
                    ?.items
                    .orEmpty()
                    .map { if (it.itemId in withheld) MemberAgendaRender.line(it) else it }
                    .filter { it.changes.isNotEmpty() }
                    .map { ItemChangeResponse(it.itemId, it.number, it.previousNumber, it.title, it.removed, it.changes.sorted()) },
            version = version,
        )
    }
}

private fun LateDecisionRequest.decision() = LateDecision(agree, onBehalf, note)
