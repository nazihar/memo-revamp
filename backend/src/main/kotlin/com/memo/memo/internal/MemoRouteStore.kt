package com.memo.memo.internal

import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.references.MEMO_ROUTE_ENTRY
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID
import com.memo.jooq.tables.references.MEMO_ as MEMO

/** A memo's route: the initiator's ordered approvers (design E01 §2). Every method runs inside a tenant transaction. */
@Repository
class MemoRouteStore(
    private val tenantDsl: TenantDsl,
) {
    /** The ordered approvers. */
    fun route(memo: UUID): List<UUID> =
        tenantDsl
            .current()
            .select(MEMO_ROUTE_ENTRY.APPROVER_ID)
            .from(MEMO_ROUTE_ENTRY)
            .where(MEMO_ROUTE_ENTRY.MEMO_ID.eq(memo))
            .orderBy(MEMO_ROUTE_ENTRY.ORDINAL)
            .fetch(MEMO_ROUTE_ENTRY.APPROVER_ID)
            .filterNotNull()

    /**
     * Replaces the route as a whole and bumps the memo's version, guarded on [expectedVersion]; false (and nothing
     * written) when the memo changed meanwhile.
     */
    fun replace(
        memo: UUID,
        expectedVersion: Long,
        approvers: List<UUID>,
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
        if (approvers.isNotEmpty()) {
            val tenant = tenantDsl.tenant().value
            val insert =
                dsl.insertInto(
                    MEMO_ROUTE_ENTRY,
                    MEMO_ROUTE_ENTRY.TENANT_ID,
                    MEMO_ROUTE_ENTRY.MEMO_ID,
                    MEMO_ROUTE_ENTRY.ORDINAL,
                    MEMO_ROUTE_ENTRY.APPROVER_ID,
                )
            approvers
                .foldIndexed(insert) { index, step, approver -> step.values(tenant, memo, index + 1, approver) }
                .execute()
        }
        return true
    }
}
