package com.memo.memo.internal

import com.memo.common.tenant.TenantDsl
import com.memo.memo.MemoSearchFacts
import com.memo.memo.MemoSearchSource
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.util.UUID
import com.memo.jooq.tables.references.MEMO_ as MEMO

/**
 * The memo module's side of the search index (ADR-0054): a memo's facts, with the field values of the version it was
 * submitted under (the published version for a draft) flattened to text. Units and people are left out (they are ids);
 * choices are indexed by their labels.
 */
@Component
class MemoSearchFactsSource(
    private val memos: MemoStore,
    private val types: MemoTypeStore,
    private val versions: MemoTypeVersionStore,
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

    override fun ids(
        after: UUID?,
        limit: Int,
    ): List<UUID> =
        tenantDsl
            .current()
            .select(MEMO.ID)
            .from(MEMO)
            .where(if (after == null) MEMO.ID.isNotNull else MEMO.ID.gt(after))
            .orderBy(MEMO.ID)
            .limit(limit)
            .fetch(MEMO.ID)
            .filterNotNull()

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
