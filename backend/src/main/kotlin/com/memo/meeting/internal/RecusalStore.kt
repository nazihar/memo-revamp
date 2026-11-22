package com.memo.meeting.internal

import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.references.MEETING_AUDIENCE
import com.memo.jooq.tables.references.MEETING_DECLARATION
import com.memo.jooq.tables.references.MEETING_ITEM
import com.memo.meeting.DeclarationState
import org.jooq.Condition
import org.jooq.Field
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * Who is recused from what (ADR-0062, design E04 §1.6), read from the effective declarations (RECUSED or AWAITING_RULING)
 * on the partial index `(tenant_id, user_id, item_id)`, inside a tenant transaction. A declaration on an item covers its
 * sub-items: callers pass an item and its parent. **An alternate standing in for a declarant is recused with them**
 * (ADR-0064, design E05 §1.5): every answer here counts the person's current ALTERNATE row at the declaration's meeting.
 */
@Repository
class RecusalStore(
    private val tenantDsl: TenantDsl,
) {
    /** The people among [userIds] recused from any of [itemIds]: by their own declaration, or their member's. */
    fun recusedAmong(
        itemIds: Collection<UUID>,
        userIds: Collection<UUID>,
    ): Set<UUID> {
        if (itemIds.isEmpty() || userIds.isEmpty()) return emptySet()
        val declarants =
            tenantDsl
                .current()
                .selectDistinct(MEETING_DECLARATION.USER_ID)
                .from(MEETING_DECLARATION)
                .where(
                    MEETING_DECLARATION.USER_ID.`in`(userIds),
                    MEETING_DECLARATION.ITEM_ID.`in`(itemIds),
                    MEETING_DECLARATION.STATE.`in`(EFFECTIVE),
                ).fetchSet(MEETING_DECLARATION.USER_ID)
                .filterNotNull()
        val alternates = standingIn(MEETING_DECLARATION.ITEM_ID.`in`(itemIds), MEETING_AUDIENCE.USER_ID.`in`(userIds))
        return (declarants + alternates.map { it.second }).toSet()
    }

    /** The people recused from any of [itemIds]: the declarants, and the alternates standing in for them. */
    fun recusedOn(itemIds: Collection<UUID>): Set<UUID> {
        if (itemIds.isEmpty()) return emptySet()
        val declarants =
            tenantDsl
                .current()
                .selectDistinct(MEETING_DECLARATION.USER_ID)
                .from(MEETING_DECLARATION)
                .where(MEETING_DECLARATION.ITEM_ID.`in`(itemIds), MEETING_DECLARATION.STATE.`in`(EFFECTIVE))
                .fetchSet(MEETING_DECLARATION.USER_ID)
                .filterNotNull()
        return (declarants + standingIn(MEETING_DECLARATION.ITEM_ID.`in`(itemIds)).map { it.second }).toSet()
    }

    /**
     * The (item, person) pairs recused at [meetingId] (of [userId] only, when given): each effective declaration, and the
     * same item for the alternate standing in for its declarant.
     */
    fun effective(
        meetingId: UUID,
        userId: UUID? = null,
    ): List<Pair<UUID, UUID>> {
        val declared =
            tenantDsl
                .current()
                .select(MEETING_DECLARATION.ITEM_ID, MEETING_DECLARATION.USER_ID)
                .from(MEETING_DECLARATION)
                .where(MEETING_DECLARATION.MEETING_ID.eq(meetingId), MEETING_DECLARATION.STATE.`in`(EFFECTIVE))
                .and(if (userId == null) DSL.noCondition() else MEETING_DECLARATION.USER_ID.eq(userId))
                .fetch { checkNotNull(it.value1()) to checkNotNull(it.value2()) }
        val inherited =
            standingIn(
                MEETING_DECLARATION.MEETING_ID.eq(meetingId),
                if (userId == null) DSL.noCondition() else MEETING_AUDIENCE.USER_ID.eq(userId),
            )
        return (declared + inherited).distinct()
    }

    /**
     * The items of [meetingId] that [userId] is recused from: those they (or the member they stand in for) hold an
     * effective declaration on, and the sub-items among [items] (the draft's live rows) of those.
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

    /**
     * Every item, at any meeting, that [userId] is recused from (E08's register, ADR-0069): their effective declarations,
     * those of the member they stand in for as an alternate there, and the sub-items of both. Two indexed queries.
     */
    fun itemsOf(userId: UUID): Set<UUID> {
        val d = MEETING_DECLARATION
        val own =
            tenantDsl
                .current()
                .selectDistinct(d.ITEM_ID)
                .from(d)
                .where(d.USER_ID.eq(userId), d.STATE.`in`(EFFECTIVE))
                .fetch(d.ITEM_ID)
                .filterNotNull()
        val declared = (own + standingIn(MEETING_AUDIENCE.USER_ID.eq(userId)).map { it.first }).toSet()
        if (declared.isEmpty()) return emptySet()
        val subItems =
            tenantDsl
                .current()
                .select(MEETING_ITEM.ID)
                .from(MEETING_ITEM)
                .where(MEETING_ITEM.PARENT_ID.`in`(declared))
                .fetch(MEETING_ITEM.ID)
                .filterNotNull()
        return declared + subItems
    }

    /**
     * (item, alternate) for each effective declaration matching [conditions] whose declarant has an alternate standing in
     * at the declaration's meeting.
     */
    private fun standingIn(vararg conditions: Condition): List<Pair<UUID, UUID>> {
        val d = MEETING_DECLARATION
        val a = MEETING_AUDIENCE
        return tenantDsl
            .current()
            .selectDistinct(d.ITEM_ID, a.USER_ID)
            .from(d)
            .join(a)
            .on(a.TENANT_ID.eq(d.TENANT_ID), a.MEETING_ID.eq(d.MEETING_ID), a.FOR_USER_ID.eq(d.USER_ID), standing(a.SOURCE, a.REMOVED_AT))
            .where(d.STATE.`in`(EFFECTIVE), *conditions)
            .fetch { checkNotNull(it.value1()) to checkNotNull(it.value2()) }
    }

    companion object {
        /** The states that recuse (ADR-0062): a pending ruling withholds the papers too. */
        val EFFECTIVE = listOf(DeclarationState.RECUSED.name, DeclarationState.AWAITING_RULING.name)

        /** A current ALTERNATE row of the audience. */
        fun standing(
            source: Field<String?>,
            removedAt: Field<*>,
        ): Condition = source.eq(AudienceSource.ALTERNATE.name).and(removedAt.isNull)

        /**
         * The declarants whose recusals [userId] carries at the meeting [meetingId] refers to: [userId] themselves, and the
         * member they stand in for there (ADR-0064). For the paper rule's anti-join ([DeclarationExclusions]).
         */
        fun declarants(
            userId: UUID,
            meetingId: Field<UUID?>,
        ): Condition {
            val a = MEETING_AUDIENCE
            return MEETING_DECLARATION.USER_ID.eq(userId).or(
                MEETING_DECLARATION.USER_ID.`in`(
                    DSL
                        .select(a.FOR_USER_ID)
                        .from(a)
                        .where(a.USER_ID.eq(userId), a.MEETING_ID.eq(meetingId), standing(a.SOURCE, a.REMOVED_AT)),
                ),
            )
        }
    }
}
