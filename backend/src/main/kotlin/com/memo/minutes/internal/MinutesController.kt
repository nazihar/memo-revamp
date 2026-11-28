package com.memo.minutes.internal

import com.memo.common.authz.SignedIn
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.etagOf
import com.memo.docedit.DocumentEditing
import com.memo.files.FileVersionInfo
import com.memo.files.Files
import com.memo.forum.MinutesApproval
import com.memo.identity.UserSummaries
import com.memo.meeting.MeetingStatus
import com.memo.meeting.Meetings
import com.memo.system.Idempotency
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/** Make the whole draft; over an existing one only with [replaceEdits] ("All your edits will be replaced"). */
data class MinutesDraftRequest(
    val replaceEdits: Boolean = false,
)

/** Fill one item's block again from its decision and notes, or put it back when it is missing ("Restore item n"). */
data class MinutesItemRequest(
    /** Informative: the job restores a missing block and fills a present one again either way. */
    val restore: Boolean = false,
)

/** The draft's current version: the file to open in the editor (`POST /files/{fileId}/edit-sessions`). */
data class MinutesDraftResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val fileId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val versionId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val number: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val savedAt: Instant,
    val savedBy: MinutesPerson?,
)

data class MinutesPerson(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
)

/** The draft job asked for last, while it runs or once it gave up ([error]: a stable code). */
data class MinutesWorkResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val kind: DraftWork,
    val itemId: UUID?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val state: WorkState,
    val error: String?,
    val requestedAt: Instant?,
)

/** One problem of the structure banner: the item (or an unknown control's tag) and what is wrong. */
data class StructureProblemResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val problem: StructureProblemKind,
    val itemId: UUID?,
    val number: String?,
    val tag: String?,
)

/**
 * The structure check of the draft's [checkedVersionId] ([pending]: a newer version is not checked yet): whether every
 * item has its block ("Restore item n"), and the tracked changes and comments left unresolved.
 */
data class MinutesStructureResponse(
    val checkedVersionId: UUID?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val pending: Boolean,
    val ok: Boolean?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val problems: List<StructureProblemResponse>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val trackedChanges: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val comments: Int,
    val checkedAt: Instant?,
)

/** An item of the minutes by its number only (the meeting page names it). */
data class MinutesItemResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val itemId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val number: String,
)

/** A meeting the minutes refer to (the one approving them, the one they are linked to). */
data class MinutesMeetingResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    val label: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val startsAt: Instant,
)

/**
 * The reviewer's last answer while the draft is back with the secretaries (flow AE1, E06 T11): agreed or sent back, with
 * their comments to the secretary, and who recorded it on their behalf.
 */
data class MinutesReviewNoteResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val decision: ReviewDecision,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val by: MinutesPerson,
    val onBehalfBy: MinutesPerson?,
    val how: AgreedHow?,
    val comment: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val at: Instant,
)

/** How the readers answered the circulation (flow AF4): for the officers. */
data class MinutesAnswersResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val readers: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val noComments: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val suggestions: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val notYet: Int,
)

/** One redacted item of a copy, by its number only. */
data class MinutesRedactionResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val itemId: UUID,
    val number: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val kind: RedactionKind,
)

/** A copy the readers get ("See the copy for…", flow AD4): what it redacts and who reads it. */
data class MinutesCopySetResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val setKey: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val redacted: List<MinutesRedactionResponse>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val readers: List<MinutesPerson>,
)

/**
 * A meeting's minutes as the viewer reads them (flows AD–AG): the stage and approval mode for everyone who reads them;
 * for the secretaries also the draft (not to one recused from any item, who cannot open it), the request in progress, the
 * structure banner, the undecided submission items, the readers' answers and copies; the reviewer, the confirmer, the
 * deadline and the approving or linked meeting; and what the viewer may do.
 */
data class MinutesResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val meetingId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val forumId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val state: MinutesStage,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val approval: MinutesApproval,
    val heldAt: Instant?,
    val draft: MinutesDraftResponse?,
    val work: MinutesWorkResponse?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val structure: MinutesStructureResponse,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val items: List<MinutesItemResponse>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val undecided: List<MinutesItemResponse>,
    /** The viewer declared an interest in an item: another secretary drafts and edits the minutes. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val viewerRecused: Boolean,
    /** May make the draft or fill an item again now. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val mayGenerate: Boolean,
    /** May open the draft in the editor now (the reviewer: in review mode, their changes tracked). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val mayEdit: Boolean,
    /**
     * The file to open in the editor (`POST /files/{fileId}/edit-sessions`) while [mayEdit]: the draft, for the reviewer in
     * review mode too, who gets no [draft] (E06 T11).
     */
    val editFileId: UUID? = null,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val version: Long,
    /** How the viewer reads the minutes. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val viewerAccess: MinutesAccessKind = MinutesAccessKind.MANAGER,
    /** What the viewer has to do: REVIEW, CONFIRM, COMMENT or READ; null when nothing. */
    val myAction: String? = null,
    val reviewer: MinutesPerson? = null,
    /** Who approves the minutes ("Waiting for Dr F. Karim to approve"), once circulated. */
    val confirmer: MinutesPerson? = null,
    val commentsUntil: Instant? = null,
    val circulatedAt: Instant? = null,
    val approvedAt: Instant? = null,
    val approvingMeeting: MinutesMeetingResponse? = null,
    val linkedMeeting: MinutesMeetingResponse? = null,
    /** The reviewer agreed: the secretary may circulate once their suggested changes are accepted. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val agreed: Boolean = false,
    /** The viewer has answered the circulation. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val answered: Boolean = false,
    /** The officers: how the readers answered. */
    val answers: MinutesAnswersResponse? = null,
    /** The secretaries: the copies the readers get. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val copies: List<MinutesCopySetResponse> = emptyList(),
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val maySendForReview: Boolean = false,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val mayReview: Boolean = false,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val mayCirculate: Boolean = false,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val mayWithdraw: Boolean = false,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val mayEndCirculation: Boolean = false,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val mayComment: Boolean = false,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val mayConfirm: Boolean = false,
    /** May read their own copy now (`GET …/minutes/document`). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val mayReadCopy: Boolean = false,
    /** When the last correction to the approved minutes was approved ("Corrected on …", E06 T8); null when none was. */
    val correctedAt: Instant? = null,
    /** May propose a correction to the approved minutes (a secretary or `forums.manage`; per item, not recused from it). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val mayProposeCorrection: Boolean = false,
    /**
     * May "Make the record again" (`POST …/minutes/record/retry`): a secretary or `forums.manage` not recused from any
     * item, while the newest record version, or a reader's approved copy of it, has failed after its tries.
     */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val mayRetryRecord: Boolean = false,
    /**
     * The secretaries not recused from any item, while the minutes are a draft: the reviewer's last answer (agreed or sent
     * back) with their comments; null before a review and once the draft is sent again or circulated.
     */
    val lastReview: MinutesReviewNoteResponse? = null,
)

/**
 * The minutes page (design E06 §3): the committee's secretaries and `forums.manage` once the meeting is held; the reviewer
 * while the draft is with them; the readers of the circulated and approved minutes ([MinutesReading]). 404 to everyone
 * else and before the meeting is held.
 */
@Service
class MinutesPages(
    private val drafts: MinutesDrafts,
    private val store: MinutesStore,
    private val meetings: Meetings,
    private val viewers: com.memo.meeting.MeetingViewers,
    private val source: MinutesDraftSource,
    private val files: Files,
    private val editing: DocumentEditing,
    private val users: UserSummaries,
    private val reading: MinutesReading,
    private val people: MinutesPeople,
    private val review: MinutesReview,
    private val comments: MinutesCommentStore,
    private val copies: MinutesCopies,
    private val corrections: MinutesCorrectionStore,
    private val retries: RecordRetries,
) {
    fun view(meetingId: UUID): MinutesResponse {
        val view = reading.actorView(meetingId)
        val meeting = meetings.find(meetingId)
        // A meeting held before T5 has no row until its first request: it reads as an empty draft.
        val stored = store.find(meetingId)
        val row = (stored ?: meeting?.let(drafts::unsaved))?.takeIf { stored != null || view.manages }
        val readers = row?.let(people::readers).orEmpty()
        val reader = row?.let { reading.readerOf(view, it, lazyOf(readers)) }
        if (meeting == null || row == null || reader == null) throw MinutesNotFoundException("minutes")
        val scene = scene(reader, meeting, stored != null, readers)
        return base(scene)
            .copy(
                viewerAccess = reader.access,
                myAction = reading.action(reader, lazyOf(readers)),
                reviewer = row.reviewerId?.takeIf { row.state != MinutesStage.DRAFT || scene.manager }?.let(scene.person),
                confirmer = scene.confirmer?.userId?.let(scene.person),
                commentsUntil = row.commentsUntil.takeIf { row.state in MinutesReading.READ_STAGES || scene.manager },
                circulatedAt = row.circulatedAt,
                approvedAt = row.approvedAt,
                correctedAt = row.takeIf { it.state == MinutesStage.APPROVED }?.let { corrections.correctedAt(it.meetingId) },
                approvingMeeting = row.approvingMeetingId?.let(::meetingOf),
                linkedMeeting = row.linkedMeetingId?.let(::meetingOf),
                agreed = scene.agreed,
                answered = view.userId in scene.answered,
                answers = answers(scene),
                copies = if (scene.manager && row.state in MinutesCopies.COPYING) copySets(row, view.agenda.items) else emptyList(),
                lastReview = lastReview(scene),
            ).let { mays(scene, it) }
    }

    /** The reviewer's last answer, for the secretaries drafting (not one recused from an item: comments may name it). */
    private fun lastReview(s: Scene): MinutesReviewNoteResponse? {
        val step =
            if (s.manager && !s.recused && s.row.state == MinutesStage.DRAFT) {
                store.events(s.row.meetingId).lastOrNull { it.kind in MinutesSteps.REVIEW_STEPS }
            } else {
                null
            }
        val decision = step?.let { REVIEW_DECISIONS[it.kind] }
        val by = step?.actor
        if (step == null || decision == null || by == null) return null
        val names = users.names(setOfNotNull(by, step.onBehalfBy))
        val person = { id: UUID -> MinutesPerson(id, names[id].orEmpty()) }
        return MinutesReviewNoteResponse(
            decision = decision,
            by = person(by),
            onBehalfBy = step.onBehalfBy?.let(person),
            how = step.how?.let { how -> AgreedHow.entries.firstOrNull { it.name == how } },
            comment = step.note,
            at = step.at,
        )
    }

    /** What the page is built from, read once. */
    private class Scene(
        val reader: MinutesReader,
        val meeting: com.memo.meeting.MeetingRef,
        val stored: Boolean,
        val readers: Map<UUID, ReaderKind>,
        val current: FileVersionInfo?,
        val recused: Boolean,
        val confirmer: MinutesOfficer?,
        val answered: Set<UUID>,
        val secretaries: Set<UUID>,
        val agreed: Boolean,
        /** The approved minutes' record, or a reader's copy of it, failed after its tries (read for the secretaries only). */
        val recordFailed: Boolean,
        val person: (UUID) -> MinutesPerson,
    ) {
        val row: MinutesRow get() = reader.row
        val manager: Boolean get() = reader.access == MinutesAccessKind.MANAGER
        val held: Boolean get() = meeting.status == MeetingStatus.HELD
        val reviewing: Boolean get() = row.state == MinutesStage.CHAIR_REVIEW && row.reviewerId == reader.userId
        val idle: Boolean get() = held && !recused && row.workState != WorkState.PENDING
        val officer: Boolean get() = manager || reviewing || confirmer?.userId == reader.userId

        /** "Make the record again": a secretary not recused, while the record or a copy of it has failed. */
        val mayRetryRecord: Boolean get() = manager && !recused && recordFailed

        /** A reader of circulated minutes who answers them (secretaries answer comments instead). */
        val comments: Boolean
            get() = row.state == MinutesStage.CIRCULATED && readers.containsKey(reader.userId) && reader.userId !in secretaries
    }

    private fun scene(
        reader: MinutesReader,
        meeting: com.memo.meeting.MeetingRef,
        stored: Boolean,
        readers: Map<UUID, ReaderKind>,
    ): Scene {
        val row = reader.row
        val confirmer = if (row.state in MinutesReading.READ_STAGES) people.confirmer(row) else null
        val names = users.names(listOfNotNull(row.reviewerId, confirmer?.userId).toSet())
        return Scene(
            reader = reader,
            meeting = meeting,
            stored = stored,
            readers = readers,
            current =
                row.fileId
                    ?.let(files::info)
                    ?.currentVersionId
                    ?.let(files::version),
            recused = drafts.recusedFromAny(row.meetingId, reader.userId),
            confirmer = confirmer,
            answered = people.answeredBy(row.meetingId),
            secretaries = people.secretaries(row.forumId),
            agreed = reader.access == MinutesAccessKind.MANAGER && row.state == MinutesStage.DRAFT && review.agreementStands(row.meetingId),
            recordFailed =
                reader.access == MinutesAccessKind.MANAGER && row.state == MinutesStage.APPROVED && retries.retryable(row.meetingId),
            person = { id -> MinutesPerson(id, names[id].orEmpty()) },
        )
    }

    /** The page as the secretaries drafting it see it (T5), the draft and the structure theirs only. */
    private fun base(s: Scene): MinutesResponse {
        val row = s.row
        val manager = s.manager
        val mayEdit =
            editing.enabled && s.current != null && s.held &&
                ((manager && s.idle && row.state in MinutesDraftPurpose.EDITABLE) || s.reviewing)
        return MinutesResponse(
            meetingId = row.meetingId,
            forumId = row.forumId,
            state = row.state,
            approval = row.policy.approval,
            heldAt = row.heldAt.takeIf { s.stored },
            draft = s.current?.takeIf { manager && !s.recused }?.let { draft(checkNotNull(row.fileId), it) },
            work = work(row).takeIf { manager },
            structure = if (manager) structure(row, s.current) else EMPTY_STRUCTURE,
            items = MinutesTemplateData.minuted(s.reader.view.agenda.items).map { MinutesItemResponse(it.id, checkNotNull(it.number)) },
            undecided = if (manager) source.undecided(row.meetingId).map { MinutesItemResponse(it.id, it.number) } else emptyList(),
            viewerRecused = s.recused,
            mayGenerate = manager && s.idle && row.state == MinutesStage.DRAFT,
            mayEdit = mayEdit,
            editFileId = row.fileId.takeIf { mayEdit },
            version = row.version,
        )
    }

    /** What the viewer may do now (the page's buttons). */
    private fun mays(
        s: Scene,
        page: MinutesResponse,
    ): MinutesResponse {
        val row = s.row
        val allows = { step: MinutesTransition -> MinutesStateMachine.allows(row.state, step, row.policy.approval) }
        val secretary = s.manager && !s.recused && s.held && s.current != null
        return page.copy(
            maySendForReview = secretary && allows(MinutesTransition.SEND_FOR_REVIEW),
            mayReview = s.reviewing || (s.manager && row.state == MinutesStage.CHAIR_REVIEW),
            mayCirculate = secretary && row.state == MinutesStage.DRAFT && (s.agreed || people.reviewer(row) == null),
            mayWithdraw = s.manager && allows(MinutesTransition.WITHDRAW),
            mayEndCirculation = s.manager && allows(MinutesTransition.END_CIRCULATION),
            mayComment = s.comments,
            mayConfirm = row.state == MinutesStage.AWAITING_CHAIR && s.confirmer?.userId == s.reader.userId,
            mayReadCopy = row.state in MinutesCopies.COPYING,
            mayProposeCorrection = s.manager && row.state == MinutesStage.APPROVED,
            mayRetryRecord = s.mayRetryRecord,
        )
    }

    private fun meetingOf(id: UUID): MinutesMeetingResponse? =
        meetings.find(id)?.let { MinutesMeetingResponse(it.id, it.label, it.startsAt) }

    /** How the readers answered, for the officers once the minutes are circulated. */
    private fun answers(s: Scene): MinutesAnswersResponse? {
        if (!s.officer || s.row.state !in MinutesReading.READ_STAGES) return null
        val all = comments.of(s.row.meetingId)
        val required = s.readers.filter { it.value != ReaderKind.ASKED && it.key !in s.secretaries }.keys
        return MinutesAnswersResponse(
            readers = required.size,
            noComments = all.count { it.kind == CommentKind.NO_COMMENTS },
            suggestions = all.count { it.kind == CommentKind.SUGGESTION },
            notYet = required.count { it !in s.answered },
        )
    }

    private fun copySets(
        row: MinutesRow,
        agenda: List<com.memo.meeting.AgendaItemRef>,
    ): List<MinutesCopySetResponse> {
        val numbers = agenda.associate { it.id to it.number }
        val sets = copies.sets(row)
        val names = users.names(sets.values.flatten().toSet())
        return sets.entries
            .sortedBy { it.key.redacted.size }
            .map { (set, readers) ->
                MinutesCopySetResponse(
                    setKey = set.key,
                    redacted = set.redacted.map { MinutesRedactionResponse(it.itemId, numbers[it.itemId], it.kind) },
                    readers = readers.sorted().map { MinutesPerson(it, names[it].orEmpty()) },
                )
            }
    }

    private fun draft(
        fileId: UUID,
        version: FileVersionInfo,
    ): MinutesDraftResponse {
        val names = version.uploadedBy?.let { users.names(setOf(it)) }.orEmpty()
        return MinutesDraftResponse(
            fileId,
            version.id,
            version.number,
            version.uploadedAt,
            version.uploadedBy?.let { MinutesPerson(it, names[it].orEmpty()) },
        )
    }

    private fun work(row: MinutesRow): MinutesWorkResponse? {
        val kind = row.workKind
        val state = row.workState
        return if (kind == null ||
            state == null
        ) {
            null
        } else {
            MinutesWorkResponse(kind, row.workItemId, state, row.workError, row.workRequestedAt)
        }
    }

    private fun structure(
        row: MinutesRow,
        current: FileVersionInfo?,
    ) = MinutesStructureResponse(
        checkedVersionId = row.checkedVersionId,
        pending = current != null && row.checkedVersionId != current.id,
        ok = row.structureOk,
        problems = row.structureProblems.map { StructureProblemResponse(it.problem, it.itemId, it.number, it.tag) },
        trackedChanges = row.trackedChanges,
        comments = row.comments,
        checkedAt = row.checkedAt,
    )

    private companion object {
        /** The chair's answer each review step records. */
        val REVIEW_DECISIONS =
            mapOf(MinutesEventKind.AGREED to ReviewDecision.AGREE, MinutesEventKind.RETURNED to ReviewDecision.RETURN)

        /** The structure banner as anyone but the secretaries gets it: nothing. */
        val EMPTY_STRUCTURE = MinutesStructureResponse(null, false, null, emptyList(), 0, 0, null)
    }
}

/** Drafting the minutes (design E06 §3, flow AD; ADR-0066). The draft is edited through docedit's edit sessions. */
@RestController
@Tag(name = "minutes")
class MinutesController(
    private val pages: MinutesPages,
    private val drafts: MinutesDrafts,
    private val idempotency: Idempotency,
    private val transactions: TenantTransactions,
    private val json: JsonMapper,
) {
    @GetMapping("/api/v1/meetings/{meetingId}/minutes")
    @SignedIn
    @Operation(operationId = "getMinutes", summary = "A meeting's minutes: stage, draft, structure check, what I may do")
    fun get(
        @PathVariable meetingId: UUID,
    ): ResponseEntity<MinutesResponse> {
        val page = transactions.readInTenant(TenantContext.current()) { pages.view(meetingId) }
        return ResponseEntity
            .ok()
            .eTag(etagOf(page.version))
            .cacheControl(CacheControl.noStore())
            .body(page)
    }

    @PostMapping("/api/v1/meetings/{meetingId}/minutes/draft")
    @SignedIn
    @Operation(operationId = "makeMinutesDraft", summary = "Make the draft of the minutes from the committee's template (a job)")
    fun draft(
        @PathVariable meetingId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody(required = false) body: MinutesDraftRequest?,
    ): ResponseEntity<MinutesResponse> = requested(meetingId, null, body?.replaceEdits == true, key, body)

    @PostMapping("/api/v1/meetings/{meetingId}/minutes/items/{itemId}/regenerate")
    @SignedIn
    @Operation(operationId = "regenerateMinutesItem", summary = "Fill one item of the draft again, or restore its block (a job)")
    fun regenerate(
        @PathVariable meetingId: UUID,
        @PathVariable itemId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody(required = false) body: MinutesItemRequest?,
    ): ResponseEntity<MinutesResponse> = requested(meetingId, itemId, false, key, body)

    private fun requested(
        meetingId: UUID,
        itemId: UUID?,
        replaceEdits: Boolean,
        key: String,
        body: Any?,
    ): ResponseEntity<MinutesResponse> {
        val page =
            transactions.inTenant(TenantContext.current()) {
                val request = fingerprint("$meetingId\n$itemId\n${json.writeValueAsString(body)}")
                idempotency.once("minutes.draft", key, request) {
                    drafts.request(meetingId, itemId, replaceEdits)
                    meetingId.toString()
                }
                pages.view(meetingId)
            }
        return ResponseEntity.status(HttpStatus.ACCEPTED).eTag(etagOf(page.version)).body(page)
    }
}
