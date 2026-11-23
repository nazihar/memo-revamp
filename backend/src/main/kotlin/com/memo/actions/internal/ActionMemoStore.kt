package com.memo.actions.internal

import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.references.ACTIONS_MEMO
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** How a memo follows up an action (design E07 §1.11): raised from it as a draft, or an existing memo linked to it. */
enum class MemoLinkKind { RAISED, LINKED }

/** A follow-up memo of an action, as stored (`actions_memo`). */
data class ActionMemoRow(
    val actionId: UUID,
    val memoId: UUID,
    val kind: MemoLinkKind,
    val linkedBy: UUID,
    val linkedAt: Instant,
)

/**
 * The follow-up memos of actions (design E07 §1.11, `actions/V3`), inside a tenant transaction: a link is written once
 * (under the action's lock) and never changed or removed. The memo is the memo module's row, referred to by id.
 */
@Repository
class ActionMemoStore(
    private val tenantDsl: TenantDsl,
) {
    /** Links [memoId] to [actionId]; false when it was linked already. */
    fun insert(
        actionId: UUID,
        memoId: UUID,
        kind: MemoLinkKind,
        by: UUID,
        at: Instant,
    ): Boolean {
        val m = ACTIONS_MEMO
        return tenantDsl
            .current()
            .insertInto(m)
            .set(m.TENANT_ID, tenantDsl.tenant().value)
            .set(m.ACTION_ID, actionId)
            .set(m.MEMO_ID, memoId)
            .set(m.KIND, kind.name)
            .set(m.LINKED_BY, by)
            .set(m.LINKED_AT, at.atOffset(ZoneOffset.UTC))
            .onConflictDoNothing()
            .execute() == 1
    }

    /** [actionId]'s follow-up memos, the first linked first. */
    fun of(actionId: UUID): List<ActionMemoRow> = where(ACTIONS_MEMO.ACTION_ID.eq(actionId))

    /** The follow-up memos of each of [actionIds], read in one query, the first linked first. */
    fun ofActions(actionIds: Collection<UUID>): Map<UUID, List<ActionMemoRow>> =
        if (actionIds.isEmpty()) emptyMap() else where(ACTIONS_MEMO.ACTION_ID.`in`(actionIds)).groupBy { it.actionId }

    /** The actions [memoId] follows up, the first linked first. */
    fun ofMemo(memoId: UUID): List<ActionMemoRow> = where(ACTIONS_MEMO.MEMO_ID.eq(memoId))

    private fun where(condition: org.jooq.Condition): List<ActionMemoRow> =
        tenantDsl
            .current()
            .selectFrom(ACTIONS_MEMO)
            .where(condition)
            .orderBy(ACTIONS_MEMO.LINKED_AT, ACTIONS_MEMO.ACTION_ID, ACTIONS_MEMO.MEMO_ID)
            .fetch {
                ActionMemoRow(it.actionId, it.memoId, MemoLinkKind.valueOf(it.kind), it.linkedBy, it.linkedAt.toInstant())
            }
}
