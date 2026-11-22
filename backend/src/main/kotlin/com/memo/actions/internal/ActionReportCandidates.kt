package com.memo.actions.internal

import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.references.ACTIONS_ENTRY
import com.memo.jooq.tables.references.ACTIONS_ITEM
import org.jooq.Condition
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The actions the action taken report and the update requests look at (design E07 §1.12, §9 (b)), inside a tenant
 * transaction: what a report of a committee lists, the latest word on each, the committees with open actions, and the
 * owners to ask for an update. Read without a lock; the update requests lock each action before they write to it.
 */
@Repository
class ActionReportCandidates(
    private val tenantDsl: TenantDsl,
) {
    /** [forumId]'s issued actions not finished, and those closed or cancelled after [since] (every one when null). */
    fun forReport(
        forumId: UUID,
        since: Instant?,
    ): List<ActionRow> {
        val a = ACTIONS_ITEM
        val ended = listOf(ActionStatus.CLOSED.name, ActionStatus.CANCELLED.name)
        val recent = since?.let { a.CLOSED_AT.gt(it.atOffset(ZoneOffset.UTC)) } ?: DSL.noCondition()
        val open = a.STATUS.`in`(ActionChecks.OPEN.map { it.name })
        return actions(
            a.FORUM_ID
                .eq(forumId)
                .and(a.REFERENCE.isNotNull)
                .and(DSL.or(open, a.STATUS.`in`(ended).and(recent))),
        )
    }

    /**
     * [forumId]'s issued actions open or in progress, held by someone, with no update since [since] (none at all when null),
     * and matching [visible]: the owners to ask for an update.
     */
    fun awaitingUpdate(
        forumId: UUID,
        since: Instant?,
        visible: Condition,
    ): List<ActionRow> {
        val a = ACTIONS_ITEM
        val quiet = since?.let { a.LAST_UPDATE_AT.isNull.or(a.LAST_UPDATE_AT.lt(it.atOffset(ZoneOffset.UTC))) } ?: a.LAST_UPDATE_AT.isNull
        val underway = a.STATUS.`in`(ActionChecks.UNDERWAY.map { it.name })
        val held =
            a.REFERENCE.isNotNull
                .and(underway)
                .and(a.HOLDER_USER_ID.isNotNull)
        return actions(
            a.FORUM_ID
                .eq(forumId)
                .and(held)
                .and(quiet)
                .and(visible),
        )
    }

    /** The committees with an issued action open or in progress. */
    fun forumsWithOpen(): Set<UUID> =
        tenantDsl
            .current()
            .selectDistinct(ACTIONS_ITEM.FORUM_ID)
            .from(ACTIONS_ITEM)
            .where(ACTIONS_ITEM.STATUS.`in`(ActionChecks.UNDERWAY.map { it.name }), ACTIONS_ITEM.REFERENCE.isNotNull)
            .fetchSet { checkNotNull(it.value1()) }

    /** The latest written word of each of [actionIds] (an update, or the note of "done"): its text and when. */
    fun latestUpdates(actionIds: Collection<UUID>): Map<UUID, Pair<String, Instant>> {
        if (actionIds.isEmpty()) return emptyMap()
        val e = ACTIONS_ENTRY
        return tenantDsl
            .current()
            .selectDistinct(e.ACTION_ID, e.TEXT, e.AT)
            .on(e.ACTION_ID)
            .from(e)
            .where(e.ACTION_ID.`in`(actionIds), e.KIND.`in`(UPDATE_KINDS), e.TEXT.isNotNull)
            .orderBy(e.ACTION_ID, e.SEQ.desc())
            .fetch { checkNotNull(it.value1()) to (checkNotNull(it.value2()) to checkNotNull(it.value3()).toInstant()) }
            .toMap()
    }

    private fun actions(condition: Condition): List<ActionRow> =
        tenantDsl
            .current()
            .selectFrom(ACTIONS_ITEM)
            .where(condition)
            .orderBy(ACTIONS_ITEM.CREATED_AT, ACTIONS_ITEM.ID)
            .fetch { it.actionRow() }

    private companion object {
        /** The entries whose text is the latest word on an action: an update, and what was done. */
        val UPDATE_KINDS = listOf(ActionProgress.UPDATE, ActionClosure.DONE)
    }
}
