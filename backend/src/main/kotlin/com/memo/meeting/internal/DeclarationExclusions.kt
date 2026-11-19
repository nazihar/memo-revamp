package com.memo.meeting.internal

import com.memo.jooq.tables.references.MEETING_DECLARATION
import com.memo.meeting.PaperExclusions
import org.jooq.Condition
import org.jooq.Field
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * The recusal rule in the paper rule's hook (ADR-0062, design E04 §1.6): a person is kept from an item, and from its
 * papers, while they hold an effective declaration (RECUSED or AWAITING_RULING) on it or on its parent. One `EXISTS`
 * on the partial index `(tenant_id, user_id, item_id)`, evaluated now, so a declaration hides the item in every revision,
 * old ones included. It applies to everyone, officers and `forums.manage` included (E03 threat E2). **An alternate
 * standing in for a member is recused wherever the member is** (ADR-0064, design E05 §1.5): the declarant may also be
 * the member of the person's current ALTERNATE row at the declaration's meeting.
 */
@Component
class DeclarationExclusions : PaperExclusions {
    override fun excluded(
        userId: UUID,
        item: Field<UUID?>,
        parent: Field<UUID?>,
    ): Condition = recused(userId, item, parent)

    override fun excludedFromPapers(
        userId: UUID,
        item: Field<UUID?>,
        parent: Field<UUID?>,
    ): Condition = recused(userId, item, parent)

    private fun recused(
        userId: UUID,
        item: Field<UUID?>,
        parent: Field<UUID?>,
    ): Condition {
        val d = MEETING_DECLARATION
        return DSL.exists(
            DSL
                .selectOne()
                .from(d)
                .where(
                    RecusalStore.declarants(userId, d.MEETING_ID),
                    d.ITEM_ID.eq(item).or(d.ITEM_ID.eq(parent)),
                    d.STATE.`in`(RecusalStore.EFFECTIVE),
                ),
        )
    }
}
