package com.memo.actions.internal

import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.records.ActionsDueRequestRecord
import com.memo.jooq.tables.references.ACTIONS_DUE_REQUEST
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** Where a request for a new due date stands (design E07 §1.7). */
enum class DueRequestState { PENDING, AGREED, DECLINED, WITHDRAWN }

/** A request for a new due date, as stored. */
data class DueRequestRow(
    val id: UUID,
    val actionId: UUID,
    val oldDate: LocalDate,
    val newDate: LocalDate,
    val reason: String,
    val state: DueRequestState,
    val requestedBy: UUID,
    val requestedAt: Instant,
    val decidedBy: UUID?,
    val decidedAt: Instant?,
    val note: String?,
)

/**
 * The owner's requests for a new due date (design E07 §1.7, §6), inside a tenant transaction and under the action's lock:
 * at most one pending per action (the unique index), decided once (a guarded update from PENDING; the trigger repeats
 * the rule).
 */
@Repository
class ActionDueRequests(
    private val tenantDsl: TenantDsl,
) {
    /** The pending request of [actionId], if any. */
    fun pending(actionId: UUID): DueRequestRow? =
        tenantDsl
            .current()
            .selectFrom(ACTIONS_DUE_REQUEST)
            .where(ACTIONS_DUE_REQUEST.ACTION_ID.eq(actionId), ACTIONS_DUE_REQUEST.STATE.eq(DueRequestState.PENDING.name))
            .fetchOne { it.row() }

    /** The pending requests of [actionIds], by action. */
    fun pending(actionIds: Collection<UUID>): Map<UUID, DueRequestRow> {
        if (actionIds.isEmpty()) return emptyMap()
        return tenantDsl
            .current()
            .selectFrom(ACTIONS_DUE_REQUEST)
            .where(ACTIONS_DUE_REQUEST.ACTION_ID.`in`(actionIds), ACTIONS_DUE_REQUEST.STATE.eq(DueRequestState.PENDING.name))
            .fetch { it.row() }
            .associateBy { it.actionId }
    }

    /** The request [id] of [actionId]; null when it is another action's or none. */
    fun find(
        actionId: UUID,
        id: UUID,
    ): DueRequestRow? =
        tenantDsl
            .current()
            .selectFrom(ACTIONS_DUE_REQUEST)
            .where(ACTIONS_DUE_REQUEST.ID.eq(id), ACTIONS_DUE_REQUEST.ACTION_ID.eq(actionId))
            .fetchOne { it.row() }

    /** Writes a new pending request. */
    fun insert(row: DueRequestRow) {
        val r = ACTIONS_DUE_REQUEST
        tenantDsl
            .current()
            .insertInto(r)
            .set(r.ID, row.id)
            .set(r.TENANT_ID, tenantDsl.tenant().value)
            .set(r.ACTION_ID, row.actionId)
            .set(r.OLD_DATE, row.oldDate)
            .set(r.NEW_DATE, row.newDate)
            .set(r.REASON, row.reason)
            .set(r.STATE, DueRequestState.PENDING.name)
            .set(r.REQUESTED_BY, row.requestedBy)
            .set(r.REQUESTED_AT, row.requestedAt.atOffset(ZoneOffset.UTC))
            .execute()
    }

    /** Decides the pending request [id] as [state]; false when it was decided already. */
    fun decide(
        id: UUID,
        state: DueRequestState,
        by: UUID?,
        at: Instant,
        note: String?,
    ): Boolean {
        val r = ACTIONS_DUE_REQUEST
        return tenantDsl
            .current()
            .update(r)
            .set(r.STATE, state.name)
            .set(r.DECIDED_BY, by)
            .set(r.DECIDED_AT, at.atOffset(ZoneOffset.UTC))
            .set(r.DECISION_NOTE, note)
            .where(r.ID.eq(id), r.STATE.eq(DueRequestState.PENDING.name))
            .execute() == 1
    }

    private fun ActionsDueRequestRecord.row() =
        DueRequestRow(
            id = id,
            actionId = actionId,
            oldDate = oldDate,
            newDate = newDate,
            reason = reason,
            state = DueRequestState.valueOf(state),
            requestedBy = requestedBy,
            requestedAt = requestedAt.toInstant(),
            decidedBy = decidedBy,
            decidedAt = decidedAt?.toInstant(),
            note = decisionNote,
        )
}
