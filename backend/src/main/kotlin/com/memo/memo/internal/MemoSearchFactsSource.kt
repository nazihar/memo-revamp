package com.memo.memo.internal

import com.memo.common.tenant.TenantDsl
import com.memo.files.Files
import com.memo.jooq.tables.references.MEMO_ATTACHMENT
import com.memo.jooq.tables.references.MEMO_NOTE
import com.memo.memo.MemoFileFacts
import com.memo.memo.MemoNoteText
import com.memo.memo.MemoSearchFacts
import com.memo.memo.MemoSearchSource
import org.jooq.Record3
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.util.UUID
import com.memo.jooq.tables.references.MEMO_ as MEMO

/**
 * The memo module's side of the search index (ADR-0054): a memo's facts, with the field values of the version it was
 * submitted under (the published version for a draft) flattened to text; its posted notes; and its searchable files (the
 * document and present attachments). Units and people are left out of the text (they are ids); choices are indexed by
 * their labels.
 */
@Component
class MemoSearchFactsSource(
    private val memos: MemoStore,
    private val types: MemoTypeStore,
    private val versions: MemoTypeVersionStore,
    private val files: Files,
    private val tenantDsl: TenantDsl,
) : MemoSearchSource {
    override fun facts(memoId: UUID): MemoSearchFacts? =
        memos.find(memoId)?.let { memo ->
            val fields = (memo.memoTypeVersionId?.let(versions::byId) ?: versions.published(memo.typeId))?.settings?.fields.orEmpty()
            val typeName = types.find(memo.typeId)?.name
            MemoSearchFacts(
                memoId = memo.id,
                classificationRank = memo.classificationRank,
                originUnitId = memo.originUnitId,
                status = memo.status.name,
                typeId = memo.typeId,
                initiatorId = memo.initiatorId,
                amount = fields.firstOrNull { it.type == FieldType.AMOUNT }?.let { amount(memo.metadata[it.key]) },
                createdAt = memo.createdAt,
                updatedAt = memo.updatedAt,
                reference = memo.reference,
                subject = memo.subject,
                fieldsText = (listOfNotNull(typeName) + fields.mapNotNull { text(it, memo.metadata[it.key]) }).joinToString("\n"),
            )
        }

    override fun note(noteId: UUID): MemoNoteText? =
        tenantDsl
            .current()
            .select(MEMO_NOTE.ID, MEMO_NOTE.MEMO_ID, MEMO_NOTE.BODY_TEXT)
            .from(MEMO_NOTE)
            .where(MEMO_NOTE.ID.eq(noteId))
            .fetchOne(::noteText)

    override fun notes(memoId: UUID): List<MemoNoteText> =
        tenantDsl
            .current()
            .select(MEMO_NOTE.ID, MEMO_NOTE.MEMO_ID, MEMO_NOTE.BODY_TEXT)
            .from(MEMO_NOTE)
            .where(MEMO_NOTE.MEMO_ID.eq(memoId))
            .orderBy(MEMO_NOTE.CREATED_AT, MEMO_NOTE.ID)
            .fetch(::noteText)

    override fun file(fileId: UUID): MemoFileFacts? {
        val dsl = tenantDsl.current()
        val attachment =
            dsl
                .select(MEMO_ATTACHMENT.MEMO_ID, MEMO_ATTACHMENT.CLASSIFICATION_RANK, MEMO.CLASSIFICATION_RANK)
                .from(MEMO_ATTACHMENT)
                .join(MEMO)
                .on(MEMO.ID.eq(MEMO_ATTACHMENT.MEMO_ID))
                .where(MEMO_ATTACHMENT.FILE_ID.eq(fileId), MEMO_ATTACHMENT.REMOVED_AT.isNull)
                .fetchOne()
        val document =
            attachment ?: dsl
                .select(MEMO.ID, MEMO.CLASSIFICATION_RANK, MEMO.CLASSIFICATION_RANK)
                .from(MEMO)
                .where(MEMO.DOCUMENT_FILE_ID.eq(fileId))
                .fetchOne()
        return document?.let { row -> fileFacts(fileId, row) }
    }

    override fun files(memoId: UUID): List<MemoFileFacts> {
        val dsl = tenantDsl.current()
        val memo = memos.find(memoId) ?: return emptyList()
        val attachments =
            dsl
                .select(MEMO_ATTACHMENT.FILE_ID, MEMO_ATTACHMENT.CLASSIFICATION_RANK)
                .from(MEMO_ATTACHMENT)
                .where(MEMO_ATTACHMENT.MEMO_ID.eq(memoId), MEMO_ATTACHMENT.REMOVED_AT.isNull)
                .fetch { requireNotNull(it.value1()) to (it.value2() ?: memo.classificationRank) }
        val all = listOfNotNull(memo.documentFileId?.let { it to memo.classificationRank }) + attachments
        val current = files.currentVersions(all.map { it.first })
        return all.map { (fileId, rank) -> MemoFileFacts(fileId, memoId, rank, current[fileId]?.id) }
    }

    /** A file row as (memo, the file's own rank or null for the memo's, the memo's rank). */
    private fun fileFacts(
        fileId: UUID,
        row: Record3<UUID?, Int?, Int?>,
    ): MemoFileFacts {
        val memoRank = requireNotNull(row.value3())
        return MemoFileFacts(fileId, requireNotNull(row.value1()), row.value2() ?: memoRank, files.info(fileId)?.currentVersionId)
    }

    private fun noteText(row: Record3<UUID?, UUID?, String?>): MemoNoteText =
        MemoNoteText(requireNotNull(row.value1()), requireNotNull(row.value2()), row.value3().orEmpty())

    private fun amount(raw: Any?): BigDecimal? = ((raw as? Map<*, *>)?.get("value") as? String)?.toBigDecimalOrNull()

    private fun text(
        field: MemoField,
        raw: Any?,
    ): String? {
        val labels = field.rules.choices.associate { it.key to it.label }
        return when {
            raw == null -> null
            field.type == FieldType.UNIT || field.type == FieldType.PERSON -> null
            field.type == FieldType.CHOICE -> labels[raw.toString()]
            field.type == FieldType.MULTI_CHOICE -> (raw as? List<*>)?.mapNotNull { labels[it.toString()] }?.joinToString(", ")
            field.type == FieldType.AMOUNT -> (raw as? Map<*, *>)?.let { "${it["value"]} ${it["currency"]}" }
            else -> raw.toString()
        }?.takeIf { it.isNotBlank() }
    }
}
