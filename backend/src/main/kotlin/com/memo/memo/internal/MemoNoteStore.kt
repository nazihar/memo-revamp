package com.memo.memo.internal

import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.references.MEMO_NOTE
import com.memo.jooq.tables.references.MEMO_NOTE_ATTACHMENT
import com.memo.jooq.tables.references.MEMO_NOTE_DRAFT
import com.memo.jooq.tables.references.MEMO_NOTE_MENTION
import org.jooq.JSONB
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/** What an entry is (ADR-0049). */
enum class NoteKind { REMARK, COMMENT, CORRECTION }

/** An entry of the note sheet as stored: its body is the validated, canonical `noteDoc/v1` JSON. */
data class MemoNote(
    val id: UUID,
    val memoId: UUID,
    val round: Int,
    val kind: NoteKind,
    val authorId: UUID,
    val actingForId: UUID?,
    val actionSeq: Long?,
    val actionKind: String?,
    val correctsId: UUID?,
    val body: String,
    val text: String,
    val createdAt: Instant,
)

/** An entry to write. */
data class NewNote(
    val memoId: UUID,
    val round: Int,
    val kind: NoteKind,
    val authorId: UUID,
    val actingForId: UUID?,
    val actionSeq: Long?,
    val actionKind: String?,
    val correctsId: UUID?,
    val body: String,
    val text: String,
)

data class NoteDraft(
    val body: String,
    val version: Long,
    val updatedAt: Instant,
)

/** `memo_note` and its derived rows, and `memo_note_draft` (V15); inside a tenant transaction. */
@Repository
class MemoNoteStore(
    private val tenantDsl: TenantDsl,
    private val ids: IdGenerator,
) {
    fun insert(
        note: NewNote,
        mentions: Set<UUID>,
        files: List<UUID>,
        at: OffsetDateTime,
    ): UUID {
        val id = ids.next()
        val tenant = tenantDsl.tenant().value
        val dsl = tenantDsl.current()
        dsl
            .insertInto(MEMO_NOTE)
            .set(MEMO_NOTE.ID, id)
            .set(MEMO_NOTE.TENANT_ID, tenant)
            .set(MEMO_NOTE.MEMO_ID, note.memoId)
            .set(MEMO_NOTE.ROUND, note.round)
            .set(MEMO_NOTE.KIND, note.kind.name)
            .set(MEMO_NOTE.AUTHOR_ID, note.authorId)
            .set(MEMO_NOTE.ACTING_FOR_ID, note.actingForId)
            .set(MEMO_NOTE.ACTION_SEQ, note.actionSeq)
            .set(MEMO_NOTE.ACTION_KIND, note.actionKind)
            .set(MEMO_NOTE.CORRECTS_ID, note.correctsId)
            .set(MEMO_NOTE.BODY, JSONB.valueOf(note.body))
            .set(MEMO_NOTE.BODY_SCHEMA, NoteDoc.SCHEMA)
            .set(MEMO_NOTE.BODY_TEXT, note.text)
            .set(MEMO_NOTE.CREATED_AT, at)
            .execute()
        if (mentions.isNotEmpty()) {
            val insert =
                dsl.insertInto(
                    MEMO_NOTE_MENTION,
                    MEMO_NOTE_MENTION.TENANT_ID,
                    MEMO_NOTE_MENTION.NOTE_ID,
                    MEMO_NOTE_MENTION.MEMO_ID,
                    MEMO_NOTE_MENTION.USER_ID,
                )
            mentions.fold(insert) { q, user -> q.values(tenant, id, note.memoId, user) }.execute()
        }
        if (files.isNotEmpty()) {
            val insert =
                dsl.insertInto(
                    MEMO_NOTE_ATTACHMENT,
                    MEMO_NOTE_ATTACHMENT.TENANT_ID,
                    MEMO_NOTE_ATTACHMENT.NOTE_ID,
                    MEMO_NOTE_ATTACHMENT.MEMO_ID,
                    MEMO_NOTE_ATTACHMENT.FILE_ID,
                    MEMO_NOTE_ATTACHMENT.POSITION,
                )
            files.withIndex().fold(insert) { q, (i, file) -> q.values(tenant, id, note.memoId, file, i) }.execute()
        }
        return id
    }

    /** The entries of [memoId], oldest first. */
    fun of(memoId: UUID): List<MemoNote> =
        tenantDsl
            .current()
            .selectFrom(MEMO_NOTE)
            .where(MEMO_NOTE.MEMO_ID.eq(memoId))
            .orderBy(MEMO_NOTE.CREATED_AT, MEMO_NOTE.ID)
            .fetch {
                MemoNote(
                    it.id,
                    it.memoId,
                    it.round,
                    NoteKind.valueOf(it.kind),
                    it.authorId,
                    it.actingForId,
                    it.actionSeq,
                    it.actionKind,
                    it.correctsId,
                    it.body.data(),
                    it.bodyText,
                    it.createdAt.toInstant(),
                )
            }

    /** The files bound to the entries of [memoId], by entry, in order. */
    fun files(memoId: UUID): Map<UUID, List<UUID>> =
        tenantDsl
            .current()
            .selectFrom(MEMO_NOTE_ATTACHMENT)
            .where(MEMO_NOTE_ATTACHMENT.MEMO_ID.eq(memoId))
            .orderBy(MEMO_NOTE_ATTACHMENT.NOTE_ID, MEMO_NOTE_ATTACHMENT.POSITION)
            .fetch()
            .groupBy({ it.noteId }, { it.fileId })

    /** Whether [fileId] is bound to an entry already. */
    fun bound(fileId: UUID): Boolean = tenantDsl.current().fetchExists(MEMO_NOTE_ATTACHMENT, MEMO_NOTE_ATTACHMENT.FILE_ID.eq(fileId))

    fun draft(
        memoId: UUID,
        userId: UUID,
    ): NoteDraft? =
        tenantDsl
            .current()
            .selectFrom(MEMO_NOTE_DRAFT)
            .where(MEMO_NOTE_DRAFT.MEMO_ID.eq(memoId), MEMO_NOTE_DRAFT.USER_ID.eq(userId))
            .fetchOne { NoteDraft(it.body.data(), it.version ?: 0, it.updatedAt.toInstant()) }

    /** Creates the draft (when [version] is null) or replaces it at [version]; false when it changed since. */
    fun saveDraft(
        memoId: UUID,
        userId: UUID,
        body: String,
        version: Long?,
        at: OffsetDateTime,
    ): Boolean {
        val dsl = tenantDsl.current()
        return if (version == null) {
            dsl
                .insertInto(MEMO_NOTE_DRAFT)
                .set(MEMO_NOTE_DRAFT.TENANT_ID, tenantDsl.tenant().value)
                .set(MEMO_NOTE_DRAFT.MEMO_ID, memoId)
                .set(MEMO_NOTE_DRAFT.USER_ID, userId)
                .set(MEMO_NOTE_DRAFT.BODY, JSONB.valueOf(body))
                .set(MEMO_NOTE_DRAFT.UPDATED_AT, at)
                .onConflictDoNothing()
                .execute() == 1
        } else {
            dsl
                .update(MEMO_NOTE_DRAFT)
                .set(MEMO_NOTE_DRAFT.BODY, JSONB.valueOf(body))
                .set(MEMO_NOTE_DRAFT.VERSION, MEMO_NOTE_DRAFT.VERSION.plus(1))
                .set(MEMO_NOTE_DRAFT.UPDATED_AT, at)
                .where(MEMO_NOTE_DRAFT.MEMO_ID.eq(memoId), MEMO_NOTE_DRAFT.USER_ID.eq(userId), MEMO_NOTE_DRAFT.VERSION.eq(version))
                .execute() == 1
        }
    }

    fun deleteDraft(
        memoId: UUID,
        userId: UUID,
    ) {
        tenantDsl
            .current()
            .deleteFrom(
                MEMO_NOTE_DRAFT,
            ).where(MEMO_NOTE_DRAFT.MEMO_ID.eq(memoId), MEMO_NOTE_DRAFT.USER_ID.eq(userId))
            .execute()
    }
}
