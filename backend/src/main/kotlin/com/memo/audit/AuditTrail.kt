package com.memo.audit

import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.references.AUDIT_EVENT
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.ZoneOffset

/** One recorded change of an object, as a history view shows it (no client address or correlation id). */
data class AuditHistoryEntry(
    val occurredAt: Instant,
    val action: String,
    val actorType: String,
    val actorId: String?,
    val before: Map<String, Any?>?,
    val after: Map<String, Any?>?,
)

/**
 * Reads the audit trail of one object, newest first (design E04 §6 history; the full audit viewer comes in E07). The
 * caller checks who may read it. Inside a tenant transaction; RLS keeps it to the current tenant.
 */
@Component
class AuditTrail(
    private val tenantDsl: TenantDsl,
    private val json: JsonMapper,
) {
    fun history(
        objectType: String,
        objectId: String,
        limit: Int,
        before: Instant? = null,
    ): List<AuditHistoryEntry> =
        tenantDsl
            .current()
            .select(AUDIT_EVENT.OCCURRED_AT, AUDIT_EVENT.ACTION, AUDIT_EVENT.ACTOR_TYPE, AUDIT_EVENT.ACTOR_ID, AUDIT_EVENT.CANONICAL)
            .from(AUDIT_EVENT)
            .where(AUDIT_EVENT.OBJECT_TYPE.eq(objectType), AUDIT_EVENT.OBJECT_ID.eq(objectId))
            .and(
                before?.let { AUDIT_EVENT.OCCURRED_AT.lt(it.atOffset(ZoneOffset.UTC)) } ?: org.jooq.impl.DSL
                    .noCondition(),
            ).orderBy(AUDIT_EVENT.OCCURRED_AT.desc(), AUDIT_EVENT.SEQ.desc())
            .limit(limit.coerceIn(1, MAX_LIMIT))
            .fetch { row ->
                @Suppress("UNCHECKED_CAST")
                val canonical = json.readValue(row.value5(), Map::class.java) as Map<String, Any?>

                @Suppress("UNCHECKED_CAST")
                AuditHistoryEntry(
                    row.value1()!!.toInstant(),
                    row.value2()!!,
                    row.value3()!!,
                    row.value4(),
                    canonical["before"] as Map<String, Any?>?,
                    canonical["after"] as Map<String, Any?>?,
                )
            }

    private companion object {
        const val MAX_LIMIT = 200
    }
}
