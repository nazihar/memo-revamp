package com.memo.memo.internal

import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.references.MEMO_ROUTE_ENTRY
import com.memo.workflow.RouteEntry
import com.memo.workflow.RouteGroup
import org.jooq.JSONB
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper
import java.time.OffsetDateTime
import java.util.UUID
import com.memo.jooq.tables.references.MEMO_ as MEMO

/**
 * A memo's route: the initiator's ordered entries (design E01 §2, E04 §4.7), each a person, a position, or a group.
 * Every method runs inside a tenant transaction.
 */
@Repository
class MemoRouteStore(
    private val tenantDsl: TenantDsl,
    private val json: JsonMapper,
) {
    /** The ordered entries. */
    fun route(memo: UUID): List<RouteEntry> =
        tenantDsl
            .current()
            .select(MEMO_ROUTE_ENTRY.APPROVER_ID, MEMO_ROUTE_ENTRY.POSITION_ID, MEMO_ROUTE_ENTRY.GROUP_ENTRY)
            .from(MEMO_ROUTE_ENTRY)
            .where(MEMO_ROUTE_ENTRY.MEMO_ID.eq(memo))
            .orderBy(MEMO_ROUTE_ENTRY.ORDINAL)
            .fetch { (approver, position, group) ->
                RouteEntry(approver, position, group?.let { json.readValue(it.data(), RouteGroup::class.java) })
            }

    /**
     * Replaces the route as a whole and bumps the memo's version, guarded on [expectedVersion]; false (and nothing
     * written) when the memo changed meanwhile.
     */
    fun replace(
        memo: UUID,
        expectedVersion: Long,
        entries: List<RouteEntry>,
        now: OffsetDateTime,
    ): Boolean {
        val dsl = tenantDsl.current()
        val bumped =
            dsl
                .update(MEMO)
                .set(MEMO.VERSION, MEMO.VERSION.plus(1))
                .set(MEMO.UPDATED_AT, now)
                .where(MEMO.ID.eq(memo), MEMO.VERSION.eq(expectedVersion))
                .execute() == 1
        if (!bumped) return false
        dsl.deleteFrom(MEMO_ROUTE_ENTRY).where(MEMO_ROUTE_ENTRY.MEMO_ID.eq(memo)).execute()
        if (entries.isNotEmpty()) {
            val tenant = tenantDsl.tenant().value
            val insert =
                dsl.insertInto(
                    MEMO_ROUTE_ENTRY,
                    MEMO_ROUTE_ENTRY.TENANT_ID,
                    MEMO_ROUTE_ENTRY.MEMO_ID,
                    MEMO_ROUTE_ENTRY.ORDINAL,
                    MEMO_ROUTE_ENTRY.APPROVER_ID,
                    MEMO_ROUTE_ENTRY.POSITION_ID,
                    MEMO_ROUTE_ENTRY.GROUP_ENTRY,
                )
            entries
                .foldIndexed(insert) { index, step, entry ->
                    step.values(
                        tenant,
                        memo,
                        index + 1,
                        entry.userId,
                        entry.positionId,
                        entry.group?.let { JSONB.valueOf(json.writeValueAsString(it)) },
                    )
                }.execute()
        }
        return true
    }
}
