package com.memo.meeting.internal

import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.references.MEETING_DECLARATION
import com.memo.meeting.DeclarationState
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * Who is recused from what (ADR-0062, design E04 §1.6), read from the effective declarations (RECUSED or AWAITING_RULING)
 * on the partial index `(tenant_id, user_id, item_id)`, inside a tenant transaction. A declaration on an item covers its
 * sub-items: callers pass an item and its parent.
 */
@Repository
class RecusalStore(
    private val tenantDsl: TenantDsl,
) {
    /** The people among [userIds] with an effective declaration (RECUSED or AWAITING_RULING) on any of [itemIds]. */
    fun recusedAmong(
        itemIds: Collection<UUID>,
        userIds: Collection<UUID>,
    ): Set<UUID> {
        if (itemIds.isEmpty() || userIds.isEmpty()) return emptySet()
        return tenantDsl
            .current()
            .selectDistinct(MEETING_DECLARATION.USER_ID)
            .from(MEETING_DECLARATION)
            .where(
                MEETING_DECLARATION.USER_ID.`in`(userIds),
                MEETING_DECLARATION.ITEM_ID.`in`(itemIds),
                MEETING_DECLARATION.STATE.`in`(EFFECTIVE),
            ).fetchSet(MEETING_DECLARATION.USER_ID)
            .filterNotNull()
            .toSet()
    }

    /** The people with an effective declaration on any of [itemIds]. */
    fun recusedOn(itemIds: Collection<UUID>): Set<UUID> {
        if (itemIds.isEmpty()) return emptySet()
        return tenantDsl
            .current()
            .selectDistinct(MEETING_DECLARATION.USER_ID)
            .from(MEETING_DECLARATION)
            .where(MEETING_DECLARATION.ITEM_ID.`in`(itemIds), MEETING_DECLARATION.STATE.`in`(EFFECTIVE))
            .fetchSet(MEETING_DECLARATION.USER_ID)
            .filterNotNull()
            .toSet()
    }

    /** The effective declarations of [meetingId] (of [userId] only, when given): (item, person) pairs. */
    fun effective(
        meetingId: UUID,
        userId: UUID? = null,
    ): List<Pair<UUID, UUID>> =
        tenantDsl
            .current()
            .select(MEETING_DECLARATION.ITEM_ID, MEETING_DECLARATION.USER_ID)
            .from(MEETING_DECLARATION)
            .where(MEETING_DECLARATION.MEETING_ID.eq(meetingId), MEETING_DECLARATION.STATE.`in`(EFFECTIVE))
            .and(if (userId == null) DSL.noCondition() else MEETING_DECLARATION.USER_ID.eq(userId))
            .fetch { checkNotNull(it.value1()) to checkNotNull(it.value2()) }

    /**
     * The items of [meetingId] that [userId] is recused from: those they hold an effective declaration on, and the
     * sub-items among [items] (the draft's live rows) of those.
     */
    fun recusedItems(
        meetingId: UUID,
        userId: UUID,
        items: Collection<ItemRow>,
    ): Set<UUID> {
        val declared = effective(meetingId, userId).map { it.first }.toSet()
        if (declared.isEmpty()) return emptySet()
        return declared + items.filter { it.parentId in declared }.map { it.id }
    }

    companion object {
        /** The states that recuse (ADR-0062): a pending ruling withholds the papers too. */
        val EFFECTIVE = listOf(DeclarationState.RECUSED.name, DeclarationState.AWAITING_RULING.name)
    }
}
