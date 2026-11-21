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

/**
 * A meeting's minutes as the secretaries see them while drafting (flow AD1–AD3): the stage and approval mode, the draft
 * (not to a secretary recused from any item, who cannot open it), the request in progress, the structure banner, the
 * submission items still undecided, and what the viewer may do.
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
    /** May open the draft in the editor now. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val mayEdit: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val version: Long,
)

/**
 * The minutes page (design E06 §3): read by the committee's secretaries and `forums.manage` while the minutes are a draft
 * (T6 opens them to the reviewer and the readers of later stages); 404 to everyone else and before the meeting is held.
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
) {
    fun view(meetingId: UUID): MinutesResponse {
        val view = viewers.of(meetingId)
        val meeting = meetings.find(meetingId)?.takeIf { view.manages } ?: throw MinutesNotFoundException("minutes")
        // A meeting held before T5 has no row until its first request: it reads as an empty draft.
        val stored = store.find(meetingId)
        val row = stored ?: drafts.unsaved(meeting) ?: throw MinutesNotFoundException("minutes")
        val recused = drafts.recusedFromAny(meetingId, view.userId)
        val current =
            row.fileId
                ?.let(files::info)
                ?.currentVersionId
                ?.let(files::version)
        val idle = meeting.status == MeetingStatus.HELD && !recused && row.workState != WorkState.PENDING
        return MinutesResponse(
            meetingId = meetingId,
            forumId = meeting.forumId,
            state = row.state,
            approval = row.policy.approval,
            heldAt = stored?.heldAt,
            draft = current?.takeIf { !recused }?.let { draft(checkNotNull(row.fileId), it) },
            work = work(row),
            structure = structure(row, current),
            items = MinutesTemplateData.minuted(view.agenda.items).map { MinutesItemResponse(it.id, checkNotNull(it.number)) },
            undecided = source.undecided(meetingId).map { MinutesItemResponse(it.id, it.number) },
            viewerRecused = recused,
            mayGenerate = idle && row.state == MinutesStage.DRAFT,
            mayEdit = idle && current != null && row.state in MinutesDraftPurpose.EDITABLE && editing.enabled,
            version = row.version,
        )
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
