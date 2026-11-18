package com.memo.meeting.internal

import com.memo.common.authz.SignedIn
import com.memo.common.ratelimit.RateLimit
import com.memo.common.ratelimit.RateLimiter
import com.memo.common.tenant.TenantContext
import com.memo.common.web.etagOf
import com.memo.common.web.ifMatchVersion
import com.memo.forum.AgendaSectionKind
import com.memo.forum.StandingKind
import com.memo.forum.SubmissionPurpose
import com.memo.system.Idempotency
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

data class StartAgendaRequest(
    val from: AgendaOrigin = AgendaOrigin.TEMPLATE,
    /** For `MEETING`: the earlier meeting to copy. */
    val meetingId: UUID? = null,
)

data class SectionRequest(
    val title: String? = null,
    val kind: AgendaSectionKind = AgendaSectionKind.STANDARD,
    /** In camera: for the chair, vice-chair, members and secretaries only. */
    val membersOnly: Boolean = false,
    /** New items placed from the tray land here; at most one section. */
    val receivesSubmissions: Boolean = false,
    /** Adding only: where among the sections, from 0; null: last. */
    val position: Int? = null,
)

data class AgendaItemRequest(
    val kind: ItemKind,
    val sectionId: UUID? = null,
    val parentId: UUID? = null,
    val position: Int? = null,
    val title: String? = null,
    val standingKind: StandingKind? = null,
    val submissionId: UUID? = null,
    val purpose: SubmissionPurpose? = null,
    val minutes: Int? = null,
)

data class AgendaItemChangeRequest(
    val title: String? = null,
    val description: String? = null,
    val purpose: SubmissionPurpose? = null,
    val resolutionSought: String? = null,
    val minutes: Int? = null,
    val presenters: List<Presenter> = emptyList(),
    val attendees: List<UUID> = emptyList(),
    /** Null: no restriction; never an empty list. */
    val restrictedTo: List<UUID>? = null,
    val titleHidden: Boolean = false,
    val classificationId: UUID? = null,
)

data class AgendaMoveRequest(
    val itemId: UUID? = null,
    val sectionId: UUID? = null,
    val toSectionId: UUID? = null,
    val toParentId: UUID? = null,
    val toPosition: Int = 0,
)

data class AgendaPaperRequest(
    val fileId: UUID,
    val title: String? = null,
    val restrictedTo: List<UUID>? = null,
    val classificationId: UUID? = null,
)

data class AgendaPaperChangeRequest(
    val title: String? = null,
    val restrictedTo: List<UUID>? = null,
    val classificationId: UUID? = null,
)

/**
 * The draft agenda (ADR-0060, design E03 §3): read by the committee's officers, built by its secretaries. Every change
 * needs `If-Match` with the agenda's version and answers the whole draft with the new version as its ETag; creates need
 * an `Idempotency-Key`. Builder writes are rate-limited per person.
 */
@RestController
@Tag(name = "meeting")
class AgendaController(
    private val starts: AgendaStart,
    private val sections: AgendaSections,
    private val moves: AgendaMoves,
    private val views: AgendaDraftViews,
    private val calls: AgendaCalls,
) {
    @GetMapping("/api/v1/meetings/{meetingId}/agenda/draft")
    @SignedIn
    @Operation(operationId = "getAgendaDraft", summary = "The working draft of a meeting's agenda, for its officers")
    fun draft(
        @PathVariable meetingId: UUID,
    ): ResponseEntity<AgendaDraftResponse> {
        val draft = calls.read { views.draft(meetingId) }
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .eTag(etagOf(draft.version))
            .body(draft)
    }

    @PostMapping("/api/v1/meetings/{meetingId}/agenda")
    @SignedIn
    @Operation(operationId = "startAgenda", summary = "Start a meeting's agenda from the committee's template or an earlier meeting")
    fun start(
        @PathVariable meetingId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: StartAgendaRequest,
    ): ResponseEntity<AgendaDraftResponse> =
        calls.created(meetingId, "meeting.agenda.start", key, "${body.from}\n${body.meetingId}") {
            starts.start(meetingId, body.from, body.meetingId)
        }

    @PostMapping("/api/v1/meetings/{meetingId}/agenda/sections")
    @SignedIn
    @Operation(operationId = "addAgendaSection", summary = "Add a section to the draft agenda")
    fun addSection(
        @PathVariable meetingId: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: SectionRequest,
    ): ResponseEntity<AgendaDraftResponse> {
        val version = ifMatchVersion(ifMatch)
        return calls.created(meetingId, "meeting.agenda.section", key, "$version\n${calls.json(body)}") {
            sections.add(meetingId, version, body.input(), body.position)
            null
        }
    }

    @PatchMapping("/api/v1/meetings/{meetingId}/agenda/sections/{sectionId}")
    @SignedIn
    @Operation(operationId = "updateAgendaSection", summary = "Rename a section, or change its kind and flags")
    fun updateSection(
        @PathVariable meetingId: UUID,
        @PathVariable sectionId: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: SectionRequest,
    ): ResponseEntity<AgendaDraftResponse> {
        val version = ifMatchVersion(ifMatch)
        return calls.changed(meetingId) { sections.update(meetingId, sectionId, version, body.input()) }
    }

    @PostMapping("/api/v1/meetings/{meetingId}/agenda/sections/{sectionId}/remove")
    @SignedIn
    @Operation(operationId = "removeAgendaSection", summary = "Remove an empty section")
    fun removeSection(
        @PathVariable meetingId: UUID,
        @PathVariable sectionId: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
    ): ResponseEntity<AgendaDraftResponse> {
        val version = ifMatchVersion(ifMatch)
        return calls.changed(meetingId) { sections.remove(meetingId, sectionId, version) }
    }

    @PostMapping("/api/v1/meetings/{meetingId}/agenda/moves")
    @SignedIn
    @Operation(operationId = "moveAgendaRow", summary = "Move an item (to a position, a section or under an item) or a section")
    fun move(
        @PathVariable meetingId: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: AgendaMoveRequest,
    ): ResponseEntity<AgendaDraftResponse> {
        val version = ifMatchVersion(ifMatch)
        return calls.changed(meetingId) {
            moves.move(meetingId, version, MoveInput(body.itemId, body.sectionId, body.toSectionId, body.toParentId, body.toPosition))
        }
    }
}

/** Items and papers of the draft agenda (design E03 §3), with the same rules as [AgendaController]. */
@RestController
@Tag(name = "meeting")
class AgendaItemsController(
    private val items: AgendaItems,
    private val papers: AgendaPapers,
    private val calls: AgendaCalls,
) {
    @PostMapping("/api/v1/meetings/{meetingId}/agenda/items")
    @SignedIn
    @Operation(operationId = "addAgendaItem", summary = "Add an item, sub-item or break, or place a submission from the tray")
    fun add(
        @PathVariable meetingId: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: AgendaItemRequest,
    ): ResponseEntity<AgendaDraftResponse> {
        val version = ifMatchVersion(ifMatch)
        val input =
            ItemInput(
                body.kind,
                body.sectionId,
                body.parentId,
                body.position,
                body.title,
                body.standingKind,
                body.submissionId,
                body.purpose,
                body.minutes,
            )
        return calls.created(meetingId, "meeting.agenda.item", key, "$version\n${calls.json(body)}") {
            items.add(meetingId, version, input)
            null
        }
    }

    @PatchMapping("/api/v1/meetings/{meetingId}/agenda/items/{itemId}")
    @SignedIn
    @Operation(operationId = "updateAgendaItem", summary = "Change an item's details, people, restriction, hidden title or level")
    fun update(
        @PathVariable meetingId: UUID,
        @PathVariable itemId: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: AgendaItemChangeRequest,
    ): ResponseEntity<AgendaDraftResponse> {
        val version = ifMatchVersion(ifMatch)
        val change =
            ItemChange(
                body.title,
                body.description,
                body.purpose,
                body.resolutionSought,
                body.minutes,
                body.presenters,
                body.attendees,
                body.restrictedTo,
                body.titleHidden,
                body.classificationId,
            )
        return calls.changed(meetingId) { items.update(meetingId, itemId, version, change) }
    }

    @PostMapping("/api/v1/meetings/{meetingId}/agenda/items/{itemId}/remove")
    @SignedIn
    @Operation(operationId = "removeAgendaItem", summary = "Take an item off the draft (a submission goes back to the tray)")
    fun remove(
        @PathVariable meetingId: UUID,
        @PathVariable itemId: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
    ): ResponseEntity<AgendaDraftResponse> {
        val version = ifMatchVersion(ifMatch)
        return calls.changed(meetingId) { items.remove(meetingId, itemId, version) }
    }

    @PostMapping("/api/v1/meetings/{meetingId}/agenda/items/{itemId}/papers")
    @SignedIn
    @Operation(operationId = "addAgendaPaper", summary = "Put a memo file back into the pack, or add an uploaded meeting paper")
    fun addPaper(
        @PathVariable meetingId: UUID,
        @PathVariable itemId: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: AgendaPaperRequest,
    ): ResponseEntity<AgendaDraftResponse> {
        val version = ifMatchVersion(ifMatch)
        val input = PaperInput(body.fileId, body.title, body.restrictedTo, body.classificationId)
        return calls.created(meetingId, "meeting.agenda.paper", key, "$itemId\n$version\n${calls.json(body)}") {
            papers.add(meetingId, itemId, version, input)
            null
        }
    }

    @PatchMapping("/api/v1/meetings/{meetingId}/agenda/papers/{paperId}")
    @SignedIn
    @Operation(operationId = "updateAgendaPaper", summary = "Change a paper's title, restriction or level")
    fun updatePaper(
        @PathVariable meetingId: UUID,
        @PathVariable paperId: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: AgendaPaperChangeRequest,
    ): ResponseEntity<AgendaDraftResponse> {
        val version = ifMatchVersion(ifMatch)
        return calls.changed(meetingId) {
            papers.update(meetingId, paperId, version, PaperChange(body.title, body.restrictedTo, body.classificationId))
        }
    }

    @PostMapping("/api/v1/meetings/{meetingId}/agenda/papers/{paperId}/remove")
    @SignedIn
    @Operation(operationId = "removeAgendaPaper", summary = "Take a paper out of its item")
    fun removePaper(
        @PathVariable meetingId: UUID,
        @PathVariable paperId: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
    ): ResponseEntity<AgendaDraftResponse> {
        val version = ifMatchVersion(ifMatch)
        return calls.changed(meetingId) { papers.remove(meetingId, paperId, version) }
    }
}

/** The transactions, rate limit and idempotency of the agenda endpoints, and their answer: the whole draft. */
@Component
class AgendaCalls(
    private val calls: MeetingCalls,
    private val views: AgendaDraftViews,
    private val limiter: RateLimiter,
    private val idempotency: Idempotency,
    private val json: JsonMapper,
) {
    fun <T> read(block: () -> T): T = calls.read(block)

    fun json(value: Any): String = json.writeValueAsString(value)

    /** Runs [change] (rate-limited) and answers the draft with its new version as the ETag. */
    fun changed(
        meetingId: UUID,
        change: () -> Unit,
    ): ResponseEntity<AgendaDraftResponse> {
        limit()
        val draft =
            calls.write {
                change()
                views.draft(meetingId)
            }
        return ResponseEntity.ok().eTag(etagOf(draft.version)).body(draft)
    }

    /** Runs [create] once per [key] (a replay answers the current draft) and answers 201 with the draft. */
    fun created(
        meetingId: UUID,
        operation: String,
        key: String,
        request: String,
        create: () -> CopyReport?,
    ): ResponseEntity<AgendaDraftResponse> {
        limit()
        val draft =
            calls.write {
                var report: CopyReport? = null
                idempotency.once(operation, key, fingerprint("$meetingId\n$request")) {
                    report = create()
                    meetingId.toString()
                }
                views.draft(meetingId, report)
            }
        return ResponseEntity.status(HttpStatus.CREATED).eTag(etagOf(draft.version)).body(draft)
    }

    private fun limit() {
        actorOrNull()?.let { limiter.consume(RateLimit.AGENDA_WRITE_PER_USER, "${TenantContext.current()}:$it") }
    }
}

private fun SectionRequest.input() = SectionInput(title, kind, membersOnly, receivesSubmissions)
