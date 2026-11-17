package com.memo.memo.internal

import com.memo.audit.AuditLog
import com.memo.common.authz.Clearances
import com.memo.common.authz.ForbiddenException
import com.memo.common.authz.Policy
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import com.memo.files.FileInfo
import com.memo.files.FilePurpose
import com.memo.files.Files
import com.memo.files.RenditionKind
import com.memo.files.StorageClass
import com.memo.notification.NotificationKind
import com.memo.tenancy.TenantSettings
import com.memo.workflow.ActionEntry
import com.memo.workflow.WorkflowQueries
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** The note sheet of a memo as read: its entries, the remarks made before E03 (from the actions), and their files. */
data class NoteSheet(
    val memo: Memo,
    val notes: List<MemoNote>,
    /** Actions with a plain remark and no entry of their own (before E03), oldest first. */
    val legacy: List<ActionEntry>,
    val files: Map<UUID, List<UUID>>,
)

/**
 * The note sheet (ADR-0049, design E03 §4): append-only entries in `noteDoc/v1`. Readers comment while the memo is not
 * closed (copy recipients only when the tenant allows it, unit readers never); authors correct their own entries by a
 * new entry; a decision's remark is written with its action, in the same transaction. A mention of someone who cannot
 * read the memo is refused: a mention never grants access. Inside a tenant transaction.
 */
@Service
class MemoNotes(
    private val memos: Memos,
    private val store: MemoStore,
    private val notes: MemoNoteStore,
    private val readable: MemoReadable,
    private val checks: NoteChecks,
    private val settings: TenantSettings,
    private val queries: WorkflowQueries,
    private val notifier: MemoNotifier,
    private val audit: AuditLog,
    private val json: JsonMapper,
    private val clock: Clock,
) {
    fun sheet(memoId: UUID): NoteSheet {
        val memo = memos.get(memoId)
        val entries = notes.of(memo.id)
        val linked = entries.mapNotNull { it.actionSeq }.toSet()
        val legacy =
            memo.workflowInstanceId
                ?.let(queries::history)
                .orEmpty()
                .filter { !it.remark.isNullOrBlank() && it.seq !in linked }
        return NoteSheet(memo, entries, legacy, notes.files(memo.id))
    }

    /** Whether the actor may write comments and corrections on [memo] now. */
    fun mayWrite(memo: Memo): Boolean {
        val kind = signedIn()?.let { readable.kindOf(memo.id, it) }
        val copiesMay = settings.get(COPY_COMMENTS_SETTING)?.value == "true"
        return kind != null && memo.status != MemoStatus.CLOSED && (kind != ParticipantKind.COPY || copiesMay)
    }

    /** Posts a comment, or a correction of [correctsId]; the draft is removed. */
    fun post(
        memoId: UUID,
        body: JsonNode,
        correctsId: UUID?,
        fileIds: List<UUID>,
    ): MemoNote {
        val actor = checkNotNull(signedIn()) { "notes need a signed-in user" }
        val memo = memos.get(memoId)
        store.lock(memo.id)
        if (!mayWrite(memo)) throw ForbiddenException(null)
        val doc = NoteDoc.parse(body)
        val corrected = correctsId?.let { checks.correctable(memo, it, actor) }
        checks.mentions(memo, doc)
        checks.files(memo, actor, fileIds)
        val kind = if (corrected != null) NoteKind.CORRECTION else NoteKind.COMMENT
        val note = NewNote(memo.id, maxOf(1, memo.currentRound), kind, actor, null, null, null, corrected?.id, canonical(doc), doc.text())
        val id = write(note, doc, fileIds)
        notes.deleteDraft(memo.id, actor)
        return notes.of(memo.id).single { it.id == id }
    }

    /**
     * A decision with its remark (ADR-0049): [request]'s `remarkBody`, or its plain `remark` as one paragraph, is checked
     * first; [act] takes the derived text; then the entry is written, linked to the action just taken.
     */
    fun <T> decision(
        memoOf: () -> Memo,
        request: RemarkRequest?,
        act: (String?) -> T,
    ): T {
        val doc = request?.remarkBody?.let(NoteDoc::parse) ?: request?.remark?.takeIf { it.isNotBlank() }?.let(NoteDocument::ofText)
        val memo = memoOf()
        doc?.let { checks.mentions(memo, it) }
        val result = act(doc?.text()?.ifBlank { null } ?: request?.remark)
        if (doc != null && doc.text().length in 1..NoteDoc.MAX_TEXT) remark(memo.id, doc)
        return result
    }

    private fun remark(
        memoId: UUID,
        doc: NoteDocument,
    ) {
        val actor = signedIn()
        val memo = store.find(memoId)
        val action =
            memo?.workflowInstanceId?.let(queries::history)?.lastOrNull { it.actorId == actor && !it.remark.isNullOrBlank() }
        if (actor == null || memo == null || action == null) return
        val note =
            NewNote(
                memo.id,
                action.round,
                NoteKind.REMARK,
                actor,
                action.actingForId,
                action.seq,
                action.kind.name,
                null,
                canonical(doc),
                doc.text(),
            )
        write(note, doc, emptyList())
    }

    private fun write(
        note: NewNote,
        doc: NoteDocument,
        fileIds: List<UUID>,
    ): UUID {
        val mentioned = doc.mentions() - note.authorId
        val id = notes.insert(note, mentioned, fileIds, now())
        val action = if (note.kind == NoteKind.CORRECTION) "memo.note.corrected" else "memo.note.posted"
        audit.record(action, Memos.OBJECT_TYPE, note.memoId.toString(), after = mapOf("note" to id.toString(), "kind" to note.kind.name))
        if (mentioned.isNotEmpty()) {
            mentioned.forEach { notifier.tell(it, NotificationKind.MEMO_MENTIONED, note.memoId) }
            audit.record(
                "memo.mentioned",
                Memos.OBJECT_TYPE,
                note.memoId.toString(),
                after = mapOf("note" to id.toString(), "users" to mentioned.map(UUID::toString).sorted()),
            )
        }
        return id
    }

    private fun canonical(doc: NoteDocument): String = json.writeValueAsString(NoteDoc.json(doc, json))

    private fun signedIn(): UUID? = (ActorContext.currentOrNull()?.actor as? Actor.User)?.userId

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    companion object {
        /** Tenant setting: may copy recipients comment (ADR-0050; off by default, owner's answer 2). */
        const val COPY_COMMENTS_SETTING = "memo.copy.comments"
    }
}

/** What a note must satisfy beyond its format (ADR-0049): mentions, files, and which entry may be corrected. */
@Component
class NoteChecks(
    private val notes: MemoNoteStore,
    private val readable: MemoReadable,
    private val clearances: Clearances,
    private val files: Files,
) {
    /** The entry [id] if the actor may correct it: theirs (or written for them), on this memo, not deep in corrections. */
    fun correctable(
        memo: Memo,
        id: UUID,
        actor: UUID,
    ): MemoNote {
        val all = notes.of(memo.id).associateBy { it.id }
        val target = all[id] ?: throw MemoNotFoundException("note")
        refuseIf(target.authorId != actor && target.actingForId != actor, "NOT_YOURS") { "You can correct only your own notes." }
        val depth = generateSequence(target) { it.correctsId?.let(all::get) }.count()
        refuseIf(depth > MAX_CORRECTIONS, "TOO_MANY_CORRECTIONS") { "This note was corrected too often; write a new note." }
        return target
    }

    /** Everyone mentioned must be able to read the memo now: an active participant cleared for it. */
    fun mentions(
        memo: Memo,
        doc: NoteDocument,
    ) {
        val mentioned = doc.mentions()
        if (mentioned.isEmpty()) return
        val ranks = clearances.rankOf(mentioned)
        val blind = mentioned.filter { readable.kindOf(memo.id, it) == null || (ranks[it] ?: 0) < memo.classificationRank }
        val labels = doc.inlines().filterIsInstance<NoteMention>().associate { it.userId to it.label }
        val problems = blind.map { FieldProblem("mentions.$it", "MENTION_NO_ACCESS", "${labels[it]} cannot see this memo.") }
        if (problems.isNotEmpty()) throw MemoRejectedException(problems)
    }

    /** At most five files, each a note attachment uploaded for this memo by the author and not yet bound to an entry. */
    fun files(
        memo: Memo,
        author: UUID,
        fileIds: List<UUID>,
    ) {
        refuseIf(
            fileIds.size > MAX_FILES || fileIds.toSet().size != fileIds.size,
            "TOO_MANY_FILES",
        ) { "At most $MAX_FILES files, once each." }
        val problems =
            fileIds.mapIndexedNotNull { i, id ->
                val info = files.info(id)
                val ok =
                    info != null &&
                        info.purpose == MemoNoteAttachmentPurpose.KEY &&
                        info.ownerId == memo.id.toString() &&
                        info.createdBy == author &&
                        !notes.bound(id)
                FieldProblem("attachmentIds[$i]", "UNKNOWN", "Attach a file you uploaded for this note.").takeIf { !ok }
            }
        if (problems.isNotEmpty()) throw MemoRejectedException(problems)
    }

    companion object {
        const val MAX_FILES = 5
        const val MAX_CORRECTIONS = 10
    }
}

/** Private drafts of notes (ADR-0049): one per person and memo, auto-saved, never shown to anyone else. */
@Service
class MemoNoteDrafts(
    private val memos: Memos,
    private val notes: MemoNoteStore,
    private val json: JsonMapper,
    private val clock: Clock,
) {
    fun draft(memoId: UUID): NoteDraft? = notes.draft(memos.get(memoId).id, checkNotNull(signedIn()))

    /** Saves the actor's draft; [version] null creates it. Not validated beyond size: a draft may be half-written. */
    fun saveDraft(
        memoId: UUID,
        body: JsonNode,
        version: Long?,
    ): NoteDraft {
        val memo = memos.get(memoId)
        val actor = checkNotNull(signedIn())
        val text = json.writeValueAsString(body)
        refuseIf(!body.isObject || text.length > NoteDoc.MAX_JSON_BYTES, "TOO_LARGE") { "A draft is a note's JSON, at most 64 KiB." }
        if (!notes.saveDraft(memo.id, actor, text, version, now())) throw ConcurrentModificationException("note draft", version ?: 0)
        return checkNotNull(notes.draft(memo.id, actor))
    }

    fun deleteDraft(memoId: UUID) = notes.deleteDraft(memos.get(memoId).id, checkNotNull(signedIn()))

    private fun signedIn(): UUID? = (ActorContext.currentOrNull()?.actor as? Actor.User)?.userId

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
}

/**
 * Files on a note (ADR-0049): the memo attachments' safe types and size; uploaded while drafting by someone who may
 * write on the memo, owned by the memo, bound to an entry when it is posted; read by the memo's readers.
 */
@Component
class MemoNoteAttachmentPurpose(
    private val memos: MemoStore,
    private val notes: MemoNotes,
    private val policy: Policy,
) : FilePurpose {
    override val key = KEY
    override val allowedTypes = MemoAttachmentPurpose.SAFE
    override val maxBytes = MemoAttachmentPurpose.MAX_BYTES
    override val storage = StorageClass.RECORD
    override val auditReads = true
    override val inlineTypes = setOf(com.memo.files.FileType.PDF, com.memo.files.FileType.PNG, com.memo.files.FileType.JPEG)
    override val renditions = setOf(RenditionKind.PDF)

    override fun mayUpload(ownerId: String?): Boolean = memoOf(ownerId)?.let(notes::mayWrite) == true

    override fun mayRead(file: FileInfo): Boolean = memoOf(file.ownerId) != null

    private fun memoOf(ownerId: String?): Memo? {
        val id = ownerId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val user = (ActorContext.currentOrNull()?.actor as? Actor.User)?.userId
        return if (id == null || user == null) null else memos.visible(id, user, policy.clearanceRank())
    }

    companion object {
        const val KEY = "memo.note.attachment"
    }
}
