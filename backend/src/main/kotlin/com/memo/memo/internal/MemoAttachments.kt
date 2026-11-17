package com.memo.memo.internal

import com.memo.audit.AuditLog
import com.memo.common.authz.Clearances
import com.memo.common.authz.Policy
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantDsl
import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import com.memo.docedit.DocumentEditing
import com.memo.files.FileInfo
import com.memo.files.FilePurpose
import com.memo.files.FileType
import com.memo.files.FileVersionCreated
import com.memo.files.Files
import com.memo.files.RenditionKind
import com.memo.files.StorageClass
import com.memo.jooq.tables.references.MEMO_ATTACHMENT
import com.memo.jooq.tables.references.MEMO_SUBMISSION_ATTACHMENT
import com.memo.tenancy.TenantSettings
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.HexFormat
import java.util.UUID

/** One attachment of a memo (ADR-0046). */
data class AttachmentRow(
    val id: UUID,
    val memoId: UUID,
    val fileId: UUID,
    val title: String,
    val classificationId: UUID?,
    val classificationRank: Int?,
    val position: Int,
    val addedBy: UUID,
    val addedAt: Instant,
    val version: Long,
) {
    /** The level that decides who may open it: its own when stricter, else the memo's. */
    fun effectiveRank(memoRank: Int): Int = maxOf(classificationRank ?: memoRank, memoRank)
}

/** An attachment as one submission bound it. */
data class BoundAttachment(
    val attachmentId: UUID,
    val fileVersionId: UUID,
    val sha256: String,
    val position: Int,
)

/** `memo_attachment` and `memo_submission_attachment`; every method runs inside a tenant transaction. */
@Repository
class MemoAttachmentStore(
    private val tenantDsl: TenantDsl,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    fun insert(
        memoId: UUID,
        fileId: UUID,
        title: String,
        addedBy: UUID,
    ): UUID {
        val id = ids.next()
        tenantDsl
            .current()
            .insertInto(MEMO_ATTACHMENT)
            .set(MEMO_ATTACHMENT.ID, id)
            .set(MEMO_ATTACHMENT.TENANT_ID, tenantDsl.tenant().value)
            .set(MEMO_ATTACHMENT.MEMO_ID, memoId)
            .set(MEMO_ATTACHMENT.FILE_ID, fileId)
            .set(MEMO_ATTACHMENT.TITLE, title)
            .set(MEMO_ATTACHMENT.POSITION, countPresent(memoId))
            .set(MEMO_ATTACHMENT.ADDED_BY, addedBy)
            .set(MEMO_ATTACHMENT.ADDED_AT, now())
            .execute()
        return id
    }

    /** The attachments not removed, in order. */
    fun present(memoId: UUID): List<AttachmentRow> =
        tenantDsl
            .current()
            .selectFrom(MEMO_ATTACHMENT)
            .where(MEMO_ATTACHMENT.MEMO_ID.eq(memoId), MEMO_ATTACHMENT.REMOVED_AT.isNull)
            .orderBy(MEMO_ATTACHMENT.POSITION, MEMO_ATTACHMENT.ADDED_AT)
            .fetch { it.toRow() }

    fun countPresent(memoId: UUID): Int =
        tenantDsl.current().fetchCount(MEMO_ATTACHMENT, MEMO_ATTACHMENT.MEMO_ID.eq(memoId), MEMO_ATTACHMENT.REMOVED_AT.isNull)

    fun byFile(fileId: UUID): AttachmentRow? =
        tenantDsl
            .current()
            .selectFrom(MEMO_ATTACHMENT)
            .where(MEMO_ATTACHMENT.FILE_ID.eq(fileId))
            .fetchOne()
            ?.toRow()

    fun get(
        memoId: UUID,
        id: UUID,
    ): AttachmentRow? =
        tenantDsl
            .current()
            .selectFrom(MEMO_ATTACHMENT)
            .where(MEMO_ATTACHMENT.ID.eq(id), MEMO_ATTACHMENT.MEMO_ID.eq(memoId), MEMO_ATTACHMENT.REMOVED_AT.isNull)
            .fetchOne()
            ?.toRow()

    /** Title, level and position, guarded on [expectedVersion]; false when it changed meanwhile. */
    fun update(
        row: AttachmentRow,
        expectedVersion: Long,
    ): Boolean =
        tenantDsl
            .current()
            .update(MEMO_ATTACHMENT)
            .set(MEMO_ATTACHMENT.TITLE, row.title)
            .set(MEMO_ATTACHMENT.CLASSIFICATION_ID, row.classificationId)
            .set(MEMO_ATTACHMENT.CLASSIFICATION_RANK, row.classificationRank)
            .set(MEMO_ATTACHMENT.POSITION, row.position)
            .set(MEMO_ATTACHMENT.VERSION, MEMO_ATTACHMENT.VERSION.plus(1))
            .where(MEMO_ATTACHMENT.ID.eq(row.id), MEMO_ATTACHMENT.VERSION.eq(expectedVersion))
            .execute() == 1

    fun remove(
        id: UUID,
        by: UUID,
    ): Boolean =
        tenantDsl
            .current()
            .update(MEMO_ATTACHMENT)
            .set(MEMO_ATTACHMENT.REMOVED_BY, by)
            .set(MEMO_ATTACHMENT.REMOVED_AT, now())
            .set(MEMO_ATTACHMENT.VERSION, MEMO_ATTACHMENT.VERSION.plus(1))
            .where(MEMO_ATTACHMENT.ID.eq(id), MEMO_ATTACHMENT.REMOVED_AT.isNull)
            .execute() == 1

    fun bind(
        memoId: UUID,
        round: Int,
        bound: List<BoundAttachment>,
    ) {
        if (bound.isEmpty()) return
        val dsl = tenantDsl.current()
        dsl
            .batch(
                bound.map { b ->
                    dsl
                        .insertInto(MEMO_SUBMISSION_ATTACHMENT)
                        .set(MEMO_SUBMISSION_ATTACHMENT.TENANT_ID, tenantDsl.tenant().value)
                        .set(MEMO_SUBMISSION_ATTACHMENT.MEMO_ID, memoId)
                        .set(MEMO_SUBMISSION_ATTACHMENT.ROUND, round)
                        .set(MEMO_SUBMISSION_ATTACHMENT.ATTACHMENT_ID, b.attachmentId)
                        .set(MEMO_SUBMISSION_ATTACHMENT.FILE_VERSION_ID, b.fileVersionId)
                        .set(MEMO_SUBMISSION_ATTACHMENT.SHA256, HexFormat.of().parseHex(b.sha256))
                        .set(MEMO_SUBMISSION_ATTACHMENT.POSITION, b.position)
                        .onConflictDoNothing()
                },
            ).execute()
    }

    /** What round [round] bound, with the attachment rows (removed ones included). */
    fun bound(
        memoId: UUID,
        round: Int,
    ): List<Pair<BoundAttachment, AttachmentRow>> =
        tenantDsl
            .current()
            .select()
            .from(MEMO_SUBMISSION_ATTACHMENT)
            .join(MEMO_ATTACHMENT)
            .on(
                MEMO_ATTACHMENT.ID.eq(MEMO_SUBMISSION_ATTACHMENT.ATTACHMENT_ID),
                MEMO_ATTACHMENT.TENANT_ID.eq(MEMO_SUBMISSION_ATTACHMENT.TENANT_ID),
            ).where(MEMO_SUBMISSION_ATTACHMENT.MEMO_ID.eq(memoId), MEMO_SUBMISSION_ATTACHMENT.ROUND.eq(round))
            .orderBy(MEMO_SUBMISSION_ATTACHMENT.POSITION)
            .fetch { r ->
                BoundAttachment(
                    r.get(MEMO_SUBMISSION_ATTACHMENT.ATTACHMENT_ID)!!,
                    r.get(MEMO_SUBMISSION_ATTACHMENT.FILE_VERSION_ID)!!,
                    HexFormat.of().formatHex(r.get(MEMO_SUBMISSION_ATTACHMENT.SHA256)!!),
                    r.get(MEMO_SUBMISSION_ATTACHMENT.POSITION)!!,
                ) to r.into(MEMO_ATTACHMENT).toRow()
            }

    private fun com.memo.jooq.tables.records.MemoAttachmentRecord.toRow() =
        AttachmentRow(
            id = id,
            memoId = memoId,
            fileId = fileId,
            title = title,
            classificationId = classificationId,
            classificationRank = classificationRank,
            position = position,
            addedBy = addedBy,
            addedAt = addedAt.toInstant(),
            version = version ?: 0,
        )

    private fun now() = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
}

/**
 * Files attached to memos (ADR-0046): the safe types, narrowed by the tenant setting `memo.attachments.types`; up to
 * 50 MiB; records; reads audited; a PDF and text built for each version. Who may add or change them and who may read
 * them follows the memo, and for reading also the attachment's own level.
 */
@Component
class MemoAttachmentPurpose(
    private val memos: MemoStore,
    private val coauthors: MemoCoauthorStore,
    private val attachments: MemoAttachmentStore,
    private val policy: Policy,
    private val settings: TenantSettings,
) : FilePurpose {
    override val key = KEY
    override val allowedTypes = SAFE
    override val maxBytes = MAX_BYTES
    override val storage = StorageClass.RECORD
    override val auditReads = true
    override val inlineTypes = setOf(FileType.PDF, FileType.PNG, FileType.JPEG, FileType.WEBP)
    override val renditions = setOf(RenditionKind.PDF, RenditionKind.TEXT)

    override fun allowedTypesNow(): Set<FileType> {
        val listed = settings.get(TYPES_SETTING)?.value ?: return allowedTypes
        val wanted =
            listed
                .split(',')
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .toSet()
        return allowedTypes.filterTo(mutableSetOf()) { type -> type.extensions.any { it in wanted } }
    }

    override fun mayUpload(ownerId: String?): Boolean {
        val memo = memoOf(ownerId) ?: return false
        val user = currentUser()
        return (memo.initiatorId == user || (user != null && coauthors.isCoauthor(memo.id, user))) && memo.status.editable
    }

    override fun mayRead(file: FileInfo): Boolean {
        val memo = memoOf(file.ownerId)
        val attachment = attachments.byFile(file.id)
        return memo != null && attachment != null && attachment.effectiveRank(memo.classificationRank) <= policy.clearanceRank()
    }

    private fun memoOf(ownerId: String?): Memo? {
        val id = ownerId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val user = currentUser()
        return if (id == null || user == null) null else memos.visible(id, user, policy.clearanceRank())
    }

    private fun currentUser(): UUID? = (ActorContext.currentOrNull()?.actor as? Actor.User)?.userId

    companion object {
        const val KEY = "memo.attachment"
        const val TYPES_SETTING = "memo.attachments.types"
        const val MAX_BYTES = 50L shl 20
        const val MAX_COUNT = 50
        val SAFE =
            setOf(
                FileType.PDF,
                FileType.PNG,
                FileType.JPEG,
                FileType.WEBP,
                FileType.TEXT,
                FileType.ZIP,
                FileType.DOCX,
                FileType.XLSX,
                FileType.PPTX,
                FileType.ODT,
                FileType.ODS,
                FileType.ODP,
            )
    }
}

/**
 * Records an attachment when its first version is stored, and checks every later version, in the upload's transaction
 * under the memo's row lock: the memo must still be editable, and a memo holds at most [MemoAttachmentPurpose.MAX_COUNT].
 * A refusal vetoes the upload, which rolls back as a whole.
 */
@Component
class MemoAttachmentListener(
    private val memos: MemoStore,
    private val attachments: MemoAttachmentStore,
    private val files: Files,
    private val audit: AuditLog,
) {
    @EventListener
    fun onVersion(event: FileVersionCreated) {
        if (event.purposeKey != MemoAttachmentPurpose.KEY) return
        val memo = event.ownerId?.let { memos.lock(UUID.fromString(it)) } ?: throw MemoNotFoundException()
        refuseIf(!memo.status.editable, "WRONG_STATE") { "Attachments can change only while the memo is a draft or returned to you." }
        if (attachments.byFile(event.fileId) != null) return
        refuseIf(attachments.countPresent(memo.id) >= MemoAttachmentPurpose.MAX_COUNT, "ATTACHMENT_LIMIT") {
            "A memo has at most ${MemoAttachmentPurpose.MAX_COUNT} attachments."
        }
        val actor = (ActorContext.current().actor as? Actor.User)?.userId ?: throw IllegalStateException("attachments need a user")
        val title = files.version(event.versionId)?.name ?: "attachment"
        val id = attachments.insert(memo.id, event.fileId, title, actor)
        audit.record("memo.attachment.added", Memos.OBJECT_TYPE, memo.id.toString(), after = mapOf("attachment" to id.toString()))
    }
}

/** An attachment as readers see it: restricted ones show neither title, type nor size (ADR-0046). */
data class AttachmentView(
    @field:io.swagger.v3.oas.annotations.media.Schema(requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED)
    val id: UUID,
    @field:io.swagger.v3.oas.annotations.media.Schema(requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED)
    val restricted: Boolean,
    @field:io.swagger.v3.oas.annotations.media.Schema(requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED)
    val position: Int,
    val fileId: UUID? = null,
    val title: String? = null,
    val mediaType: String? = null,
    val sizeBytes: Long? = null,
    /** The current version's id and number. */
    val versionId: UUID? = null,
    val versionNumber: Int? = null,
    /** Set when the attachment is stricter than the memo. */
    val classification: Named? = null,
    /** The attachment's row version, for If-Match. */
    val version: Long? = null,
)

/**
 * Attachments of memos (ADR-0046): listing with redaction, changes while the memo is editable, the type's rules at
 * submit, and the binding of each submission. Inside a tenant transaction.
 */
@Service
class MemoAttachments(
    private val store: MemoAttachmentStore,
    private val memos: Memos,
    private val coauthors: MemoCoauthorStore,
    private val clearances: Clearances,
    private val policy: Policy,
    private val files: Files,
    private val editing: DocumentEditing,
    private val audit: AuditLog,
) {
    fun list(memoId: UUID): List<AttachmentView> {
        val memo = memos.get(memoId)
        return store.present(memoId).map { view(it, memo) }
    }

    /** What round [round] bound; restricted ones redacted. */
    fun round(
        memoId: UUID,
        round: Int,
    ): List<AttachmentView> {
        val memo = memos.get(memoId)
        return store.bound(memoId, round).map { (bound, row) ->
            val shown = view(row, memo)
            if (shown.restricted) {
                shown
            } else {
                val version = files.version(bound.fileVersionId)
                shown.copy(versionId = bound.fileVersionId, versionNumber = version?.number, sizeBytes = version?.sizeBytes)
            }
        }
    }

    /** Title, a stricter level (or back to the memo's with [clearLevel]) and position, while the memo is editable. */
    fun change(
        memoId: UUID,
        id: UUID,
        expectedVersion: Long,
        change: AttachmentChange,
    ): AttachmentView {
        val memo = editable(memoId)
        val row = store.get(memoId, id) ?: throw MemoNotFoundException()
        val level = change.classificationId?.let(clearances::level)
        val problems = changeProblems(change, level, memo.classificationRank, policy.clearanceRank())
        if (problems.isNotEmpty()) throw MemoRejectedException(problems)
        val next =
            row.copy(
                title = change.title?.trim() ?: row.title,
                classificationId = if (change.clearLevel) null else level?.id ?: row.classificationId,
                classificationRank = if (change.clearLevel) null else level?.rank ?: row.classificationRank,
                position = change.position ?: row.position,
            )
        save(next, expectedVersion)
        if (next.classificationRank != row.classificationRank) {
            audit.record(
                "memo.attachment.reclassified",
                Memos.OBJECT_TYPE,
                memoId.toString(),
                before = mapOf("attachment" to id.toString(), "rank" to row.classificationRank),
                after = mapOf("attachment" to id.toString(), "rank" to next.classificationRank),
            )
        }
        if (next.title != row.title) {
            audit.record("memo.attachment.retitled", Memos.OBJECT_TYPE, memoId.toString(), after = mapOf("attachment" to id.toString()))
        }
        return view(store.get(memoId, id)!!, memo)
    }

    private fun save(
        next: AttachmentRow,
        expectedVersion: Long,
    ) {
        if (!store.update(next, expectedVersion)) throw ConcurrentModificationException("attachment ${next.id}", expectedVersion)
    }

    fun remove(
        memoId: UUID,
        id: UUID,
    ) {
        editable(memoId)
        store.get(memoId, id) ?: throw MemoNotFoundException()
        if (store.remove(id, currentUser())) {
            audit.record("memo.attachment.removed", Memos.OBJECT_TYPE, memoId.toString(), after = mapOf("attachment" to id.toString()))
        }
    }

    /** The type's attachment rules (ADR-0043), checked at submit. */
    fun problems(
        memo: Memo,
        rules: AttachmentRules,
    ): List<FieldProblem> {
        val needed = if (rules.required) maxOf(rules.min, 1) else 0
        val present = store.countPresent(memo.id)
        return listOfNotNull(
            FieldProblem("attachments", "ATTACHMENTS_REQUIRED", "This memo type needs at least $needed attachments.")
                .takeIf { present < needed },
        )
    }

    /** Whether an attachment of [memoId] is open in the editor (a submit waits for it, ADR-0047). */
    fun beingEdited(memoId: UUID): Boolean = store.present(memoId).any { editing.beingEdited(it.fileId) }

    /** Binds every present attachment's current version to round [round]. */
    fun bind(
        memoId: UUID,
        round: Int,
    ) {
        val bound =
            store.present(memoId).mapNotNull { row ->
                val version = files.info(row.fileId)?.currentVersionId?.let(files::version) ?: return@mapNotNull null
                BoundAttachment(row.id, version.id, version.sha256, row.position)
            }
        store.bind(memoId, round, bound)
    }

    private fun view(
        row: AttachmentRow,
        memo: Memo,
    ): AttachmentView {
        val restricted = row.effectiveRank(memo.classificationRank) > policy.clearanceRank()
        if (restricted) return AttachmentView(row.id, true, row.position)
        val version = files.info(row.fileId)?.currentVersionId?.let(files::version)
        val level = row.classificationId?.let { clearances.level(it) }
        return AttachmentView(
            id = row.id,
            restricted = false,
            position = row.position,
            fileId = row.fileId,
            title = row.title,
            mediaType = version?.mediaType,
            sizeBytes = version?.sizeBytes,
            versionId = version?.id,
            versionNumber = version?.number,
            classification = level?.let { Named(it.id, it.name) },
            version = row.version,
        )
    }

    /** The memo if the actor is its initiator or a co-author and it is editable. */
    private fun editable(memoId: UUID): Memo {
        val memo = memos.get(memoId)
        val user = currentUser()
        if (memo.initiatorId != user && !coauthors.isCoauthor(memoId, user)) {
            throw com.memo.common.authz
                .ForbiddenException(null)
        }
        refuseIf(!memo.status.editable, "WRONG_STATE") { "Attachments can change only while the memo is a draft or returned to you." }
        return memo
    }

    private fun currentUser(): UUID =
        (ActorContext.current().actor as? Actor.User)?.userId ?: throw IllegalStateException("attachments need a signed-in user")

    companion object {
        const val TITLE_MAX = 255
    }
}

/** Why [change] cannot be made: an unknown level, one not stricter than the memo's or above the actor's, a bad title. */
private fun changeProblems(
    change: AttachmentChange,
    level: com.memo.common.authz.ClassificationLevelRef?,
    memoRank: Int,
    clearance: Int,
): List<FieldProblem> =
    listOfNotNull(
        FieldProblem("classificationId", "unknown", "No such classification level.")
            .takeIf { change.classificationId != null && level == null },
        FieldProblem("classificationId", "NOT_STRICTER", "Choose a level above the memo's, or the memo's own.")
            .takeIf { level != null && level.rank <= memoRank },
        FieldProblem("classificationId", "above-clearance", "You are not cleared for this level.")
            .takeIf { level != null && level.rank > clearance },
        FieldProblem("title", "length", "1 to 255 characters.")
            .takeIf { change.title != null && change.title.trim().length !in 1..MemoAttachments.TITLE_MAX },
    )

/** What a change to an attachment asks for. */
data class AttachmentChange(
    val title: String? = null,
    val classificationId: UUID? = null,
    /** Back to the memo's own level. */
    val clearLevel: Boolean = false,
    val position: Int? = null,
)
