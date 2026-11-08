package com.memo.audit

import com.memo.common.tenant.PlatformTransactions
import com.memo.common.tenant.TenantTransactions
import com.memo.jooq.tables.references.AUDIT_ANCHOR
import com.memo.jooq.tables.references.AUDIT_EVENT
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.ZoneOffset

/** What to look for in an audit chain (ADR-0033): a time range, plus optional narrowing. */
data class AuditFilter(
    val from: Instant,
    val to: Instant,
    /** `identity.user` matches `identity.user.invited`, `identity.user.locked`, … */
    val actionPrefix: String? = null,
    val actorId: String? = null,
    val objectType: String? = null,
    val objectId: String? = null,
) {
    /** Only a time range: the export is chain-continuous. */
    val timeRangeOnly: Boolean get() = actionPrefix == null && actorId == null && objectType == null && objectId == null
}

/** One audit entry as the viewer shows it. */
data class AuditRecord(
    val seq: Long,
    val occurredAt: Instant,
    val actorType: String,
    val actorId: String?,
    val actingAsId: String?,
    val clientIp: String?,
    val clientUserAgent: String?,
    val action: String,
    val objectType: String,
    val objectId: String?,
    val before: Map<String, Any?>?,
    val after: Map<String, Any?>?,
)

/** An entry exactly as stored, for export: canonical text and hashes. */
class AuditLine(
    val seq: Long,
    val prevHash: ByteArray,
    val hash: ByteArray,
    val canonical: String,
)

/** An anchor written to the records bucket (ADR-0019). */
data class AuditAnchorRef(
    val seq: Long,
    val hash: ByteArray,
    val objectKey: String,
)

/**
 * Reads an audit chain (ADR-0033): the tenant's in a tenant transaction, the platform chain in a platform transaction.
 * Which chain is visible is decided by RLS from the transaction's scope, so the same queries serve both.
 */
@Component
class AuditQuery(
    private val dsl: DSLContext,
    private val json: JsonMapper,
) {
    /** Newest first; [beforeSeq] continues a page. */
    fun page(
        filter: AuditFilter,
        beforeSeq: Long?,
        limit: Int,
    ): List<AuditRecord> =
        scoped()
            .select(
                AUDIT_EVENT.SEQ,
                AUDIT_EVENT.OCCURRED_AT,
                AUDIT_EVENT.ACTOR_TYPE,
                AUDIT_EVENT.ACTOR_ID,
                AUDIT_EVENT.ACTING_AS_ID,
                AUDIT_EVENT.CLIENT_IP,
                AUDIT_EVENT.CLIENT_USER_AGENT,
                AUDIT_EVENT.ACTION,
                AUDIT_EVENT.OBJECT_TYPE,
                AUDIT_EVENT.OBJECT_ID,
                AUDIT_EVENT.CANONICAL,
            ).from(AUDIT_EVENT)
            .where(conditions(filter))
            .and(beforeSeq?.let { AUDIT_EVENT.SEQ.lt(it) } ?: DSL.noCondition())
            .orderBy(AUDIT_EVENT.SEQ.desc())
            .limit(limit.coerceIn(1, MAX_PAGE))
            .fetch { row ->
                val canonical = json.readTree(row.value11())
                @Suppress("UNCHECKED_CAST")
                AuditRecord(
                    seq = row.value1()!!,
                    occurredAt = row.value2()!!.toInstant(),
                    actorType = row.value3()!!,
                    actorId = row.value4(),
                    actingAsId = row.value5(),
                    clientIp = row.value6(),
                    clientUserAgent = row.value7(),
                    action = row.value8()!!,
                    objectType = row.value9()!!,
                    objectId = row.value10(),
                    before =
                        canonical["before"]?.takeIf { it.isObject }?.let {
                            json.treeToValue(
                                it,
                                Map::class.java,
                            ) as Map<String, Any?>
                        },
                    after =
                        canonical["after"]?.takeIf { it.isObject }?.let {
                            json.treeToValue(
                                it,
                                Map::class.java,
                            ) as Map<String, Any?>
                        },
                )
            }

    fun count(filter: AuditFilter): Int = scoped().fetchCount(AUDIT_EVENT, conditions(filter))

    /**
     * Every matching entry in sequence order, streamed with a cursor (no full load). An unfiltered range is widened to the
     * full sequence span between its first and last entry, so it is contiguous even where commit order and time differ.
     */
    fun forEachLine(
        filter: AuditFilter,
        consumer: (AuditLine) -> Unit,
    ) {
        val condition =
            if (filter.timeRangeOnly) {
                val span = seqSpan(filter) ?: return
                AUDIT_EVENT.SEQ.between(span.first, span.last)
            } else {
                conditions(filter)
            }
        scoped()
            .select(AUDIT_EVENT.SEQ, AUDIT_EVENT.PREV_HASH, AUDIT_EVENT.HASH, AUDIT_EVENT.CANONICAL)
            .from(AUDIT_EVENT)
            .where(condition)
            .orderBy(AUDIT_EVENT.SEQ)
            .fetchSize(FETCH_SIZE)
            .fetchLazy()
            .use { cursor -> cursor.forEach { consumer(AuditLine(it.value1()!!, it.value2()!!, it.value3()!!, it.value4()!!)) } }
    }

    /** Anchors with a sequence number in [first]..[last]. */
    fun anchors(
        first: Long,
        last: Long,
    ): List<AuditAnchorRef> =
        scoped()
            .select(AUDIT_ANCHOR.SEQ, AUDIT_ANCHOR.HASH, AUDIT_ANCHOR.OBJECT_KEY)
            .from(AUDIT_ANCHOR)
            .where(AUDIT_ANCHOR.SEQ.between(first, last))
            .orderBy(AUDIT_ANCHOR.SEQ)
            .fetch { AuditAnchorRef(it.value1()!!, it.value2()!!, it.value3()!!) }

    private fun seqSpan(filter: AuditFilter): LongRange? {
        val span =
            scoped()
                .select(DSL.min(AUDIT_EVENT.SEQ), DSL.max(AUDIT_EVENT.SEQ))
                .from(AUDIT_EVENT)
                .where(conditions(filter))
                .fetchOne()
        val first = span?.value1()
        val last = span?.value2()
        return if (first == null || last == null) null else first..last
    }

    private fun conditions(filter: AuditFilter): Condition =
        DSL.and(
            listOfNotNull(
                AUDIT_EVENT.OCCURRED_AT.ge(filter.from.atOffset(ZoneOffset.UTC)),
                AUDIT_EVENT.OCCURRED_AT.lt(filter.to.atOffset(ZoneOffset.UTC)),
                filter.actionPrefix?.let { AUDIT_EVENT.ACTION.eq(it).or(AUDIT_EVENT.ACTION.startsWith("$it.")) },
                filter.actorId?.let { AUDIT_EVENT.ACTOR_ID.eq(it) },
                filter.objectType?.let { AUDIT_EVENT.OBJECT_TYPE.eq(it) },
                filter.objectId?.let { AUDIT_EVENT.OBJECT_ID.eq(it) },
            ),
        )

    /** Fails closed outside a tenant or platform transaction: RLS would show nothing, which must not look like "no events". */
    private fun scoped(): DSLContext {
        check(TenantTransactions.currentTenantOrNull() != null || PlatformTransactions.isPlatformTransaction()) {
            "audit queries run in a tenant or platform transaction"
        }
        return dsl
    }

    private companion object {
        const val MAX_PAGE = 200
        const val FETCH_SIZE = 1000
    }
}
