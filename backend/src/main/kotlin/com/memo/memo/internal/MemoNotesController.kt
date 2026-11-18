package com.memo.memo.internal

import com.memo.common.authz.Policy
import com.memo.common.authz.SignedIn
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.etagOf
import com.memo.files.Files
import com.memo.identity.UserSummaries
import com.memo.workflow.ActionKind
import com.memo.workflow.WorkflowForums
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/** A committee's decision or decline, shown on the note sheet with the committee's name (ADR-0058). */
private val FORUM_ACTIONS = setOf(ActionKind.FORUM_DECIDED, ActionKind.FORUM_DECLINED)

data class NoteRequest(
    /** The note in `noteDoc/v1` (ADR-0049). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val body: JsonNode,
    /** Set for a correction: the entry it corrects (one of the reader's own). */
    val correctsId: UUID? = null,
    /** Files uploaded for this note (purpose `memo.note.attachment`, owner the memo), at most five. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val attachmentIds: List<UUID> = emptyList(),
)

data class NoteDraftRequest(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val body: JsonNode,
)

data class NoteDraftView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val body: JsonNode,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val version: Long,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val updatedAt: Instant,
)

data class NoteFileView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val fileId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val mediaType: String,
)

/** One entry of the note sheet. */
data class NoteEntryView(
    /** The entry's id; for a remark made before E03, `action-<seq>` (it cannot be corrected). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val kind: NoteKind,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val round: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val author: Named,
    val actingFor: Named? = null,
    /** For a decision's remark: the action (`SUBMIT`, `APPROVE`, `RETURN`, `REJECT`, …). */
    val actionKind: String? = null,
    /** `noteDoc/v1`. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val body: JsonNode,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val text: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val createdAt: Instant,
    val correctsId: String? = null,
    /** The corrections of this entry, oldest first. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val correctedBy: List<String> = emptyList(),
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val attachments: List<NoteFileView> = emptyList(),
    /** The memos it refers to that the reader may open (others show as "Memo (restricted)"). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val readableMemos: List<UUID> = emptyList(),
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val canCorrect: Boolean = false,
    /** A committee's decision or decline (ADR-0058): the committee, by name: "Returned by the Credit Committee". */
    val committee: String? = null,
    /** Its outcome: APPROVED, APPROVED_WITH_CONDITIONS, NOTED, REJECTED, RETURNED, or DECLINED. */
    val committeeOutcome: String? = null,
)

data class NoteSheetView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val entries: List<NoteEntryView>,
    /** Whether the reader may comment now. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val canWrite: Boolean,
)

/** The note sheet of a memo (ADR-0049, design E03 §4). */
@RestController
@Tag(name = "memo")
class MemoNotesController(
    private val notes: MemoNotes,
    private val drafts: MemoNoteDrafts,
    private val store: MemoStore,
    private val files: Files,
    private val users: UserSummaries,
    private val policy: Policy,
    private val json: JsonMapper,
    private val transactions: TenantTransactions,
    private val workflow: WorkflowForums,
) {
    @GetMapping("/api/v1/memos/{id}/notes")
    @SignedIn
    @Operation(operationId = "listMemoNotes", summary = "The memo's note sheet: remarks, comments and corrections, oldest first")
    fun list(
        @PathVariable id: UUID,
    ): ResponseEntity<NoteSheetView> {
        val view =
            transactions.readInTenant(TenantContext.current()) {
                val sheet = notes.sheet(id)
                NoteSheetView(views(sheet), notes.mayWrite(sheet.memo))
            }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view)
    }

    @PostMapping("/api/v1/memos/{id}/notes")
    @SignedIn
    @Operation(operationId = "postMemoNote", summary = "Post a comment, or a correction of one of your entries")
    fun post(
        @PathVariable id: UUID,
        @RequestBody body: NoteRequest,
    ): ResponseEntity<NoteEntryView> {
        val view =
            transactions.inTenant(TenantContext.current()) {
                val note = notes.post(id, body.body, body.correctsId, body.attachmentIds)
                views(notes.sheet(id)).single { it.id == note.id.toString() }
            }
        return ResponseEntity.status(HttpStatus.CREATED).body(view)
    }

    @GetMapping("/api/v1/memos/{id}/notes/draft")
    @SignedIn
    @Operation(operationId = "getMemoNoteDraft", summary = "Your unposted note on the memo (204 when there is none)")
    fun draft(
        @PathVariable id: UUID,
    ): ResponseEntity<NoteDraftView> {
        val draft = transactions.readInTenant(TenantContext.current()) { drafts.draft(id) } ?: return ResponseEntity.noContent().build()
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .eTag(etagOf(draft.version))
            .body(view(draft))
    }

    @PutMapping("/api/v1/memos/{id}/notes/draft")
    @SignedIn
    @Operation(operationId = "saveMemoNoteDraft", summary = "Save your unposted note; If-Match once it exists")
    fun saveDraft(
        @PathVariable id: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: NoteDraftRequest,
    ): ResponseEntity<NoteDraftView> {
        val version = ifMatch?.removePrefix("W/")?.trim('"', ' ')?.toLongOrNull()
        val draft = transactions.inTenant(TenantContext.current()) { drafts.saveDraft(id, body.body, version) }
        return ResponseEntity.ok().eTag(etagOf(draft.version)).body(view(draft))
    }

    @DeleteMapping("/api/v1/memos/{id}/notes/draft")
    @SignedIn
    @Operation(operationId = "deleteMemoNoteDraft", summary = "Throw your unposted note away")
    fun deleteDraft(
        @PathVariable id: UUID,
    ): ResponseEntity<Unit> {
        transactions.inTenant(TenantContext.current()) { drafts.deleteDraft(id) }
        return ResponseEntity.noContent().build()
    }

    private fun view(draft: NoteDraft) = NoteDraftView(json.readTree(draft.body), draft.version, draft.updatedAt)

    private fun views(sheet: NoteSheet): List<NoteEntryView> {
        val actor = (ActorContext.current().actor as? Actor.User)?.userId
        val names =
            users
                .of(
                    sheet.notes.flatMap { listOfNotNull(it.authorId, it.actingForId) } +
                        sheet.legacy.flatMap { listOfNotNull(it.actorId, it.actingForId) },
                ).mapValues { it.value.displayName }

        fun named(id: UUID) = Named(id, names[id].orEmpty())
        val fileInfo = files.currentVersions(sheet.files.values.flatten())
        val corrections = sheet.notes.filter { it.correctsId != null }.groupBy({ it.correctsId!! }, { it.id.toString() })
        val writable = notes.mayWrite(sheet.memo)
        val entries =
            sheet.notes.map { n ->
                val body = json.readTree(n.body)
                val refs = runCatching { NoteDoc.parse(body).memoRefs() }.getOrDefault(emptySet())
                NoteEntryView(
                    id = n.id.toString(),
                    kind = n.kind,
                    round = n.round,
                    author = named(n.authorId),
                    actingFor = n.actingForId?.let(::named),
                    actionKind = n.actionKind,
                    body = body,
                    text = n.text,
                    createdAt = n.createdAt,
                    correctsId = n.correctsId?.toString(),
                    correctedBy = corrections[n.id].orEmpty(),
                    attachments =
                        sheet.files[n.id].orEmpty().mapNotNull { f ->
                            fileInfo[f]?.let { NoteFileView(f, it.name, it.mediaType) }
                        },
                    readableMemos = refs.filter { actor != null && store.visible(it, actor, policy.clearanceRank()) != null },
                    canCorrect = writable && actor != null && (n.authorId == actor || n.actingForId == actor),
                )
            }
        val forumNames by lazy { workflow.forumNames() }
        val legacy =
            sheet.legacy.map { a ->
                val text = a.remark.orEmpty()
                val forum = (a.details["forum"] as? String)?.takeIf { a.kind in FORUM_ACTIONS }
                NoteEntryView(
                    id = "action-${a.seq}",
                    kind = NoteKind.REMARK,
                    round = a.round,
                    author = named(a.actorId),
                    actingFor = a.actingForId?.let(::named),
                    actionKind = a.kind.name,
                    body = NoteDoc.json(NoteDocument.ofText(text), json),
                    text = text,
                    createdAt = a.occurredAt,
                    committee = forum?.let { forumNames[it] ?: it },
                    committeeOutcome =
                        forum?.let { if (a.kind == ActionKind.FORUM_DECLINED) "DECLINED" else a.details["outcome"] as? String },
                )
            }
        return (entries + legacy).sortedBy { it.createdAt }
    }
}
