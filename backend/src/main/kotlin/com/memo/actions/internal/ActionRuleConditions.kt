package com.memo.actions.internal

import com.memo.jooq.tables.references.ACTIONS_CONTRIBUTOR
import com.memo.jooq.tables.references.ACTIONS_ITEM
import com.memo.jooq.tables.references.ACTIONS_REPORT_ROW
import com.memo.meeting.ItemColumns
import com.memo.meeting.PaperAccess
import com.memo.meeting.PaperExclusions
import org.jooq.Condition
import org.jooq.Field
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * The parts of the one rule (design E07 §1.14) as jOOQ conditions, for [ActionAccess] on the action's own row and for the
 * action taken report's rows (design E07 §1.12), which carry the same copied level and item columns:
 * - [involved]: the viewer holds, owns (as the person or the position's holder), contributes to, or holds or may claim the
 *   open task of the action;
 * - [byRule]: cleared for the level, not recused from the item, and, without an item, an officer, a reader or a member
 *   today; with one, the item rule ([PaperAccess.itemCondition]) or a reader's unrestricted, unhidden, open item they are
 *   cleared for;
 * - [report]: a report row by [byRule] on its own columns, or the viewer involved in the row's action now.
 */
@Component
class ActionRuleConditions(
    private val paperAccess: PaperAccess,
    private val exclusions: PaperExclusions,
) {
    /** Whether [viewer] is involved in the action (`actions_item`). */
    fun involved(viewer: ActionViewer): Condition {
        val a = ACTIONS_ITEM
        val c = ACTIONS_CONTRIBUTOR
        val contributes =
            DSL.exists(
                DSL
                    .selectOne()
                    .from(c)
                    .where(c.TENANT_ID.eq(a.TENANT_ID), c.ACTION_ID.eq(a.ID), c.USER_ID.eq(viewer.userId), c.REMOVED_AT.isNull),
            )
        return DSL.or(
            listOfNotNull(
                a.HOLDER_USER_ID.eq(viewer.userId),
                a.OWNER_USER_ID.eq(viewer.userId),
                viewer.positions.takeIf { it.isNotEmpty() }?.let { a.OWNER_POSITION_ID.`in`(it) },
                contributes,
                viewer.tasks().takeIf { it.isNotEmpty() }?.let { a.ID.`in`(it) },
            ),
        )
    }

    /** The rule's second part for [viewer] on [forumId], on a row's [classification] and copied item [columns]. */
    fun byRule(
        viewer: ActionViewer,
        forumId: UUID,
        classification: Field<UUID?>,
        columns: ItemColumns,
    ): Condition {
        val paper = viewer.paper(forumId)
        val cleared = if (viewer.cleared.isEmpty()) DSL.falseCondition() else classification.`in`(viewer.cleared)
        val notRecused = exclusions.excluded(viewer.userId, columns.item, columns.parent).not()
        val withoutItem =
            columns.item.isNull.and(
                if (viewer.officer(forumId) || viewer.reader || viewer.member(forumId)) DSL.trueCondition() else DSL.falseCondition(),
            )
        val reads =
            if (viewer.reader && viewer.cleared.isNotEmpty()) {
                columns.restrictedTo.isNull
                    .and(columns.titleHidden.isFalse)
                    .and(columns.membersOnly.isFalse)
                    .and(columns.classification.`in`(viewer.cleared))
            } else {
                DSL.falseCondition()
            }
        val withItem = columns.item.isNotNull.and(DSL.or(paperAccess.itemCondition(paper, columns), reads))
        return cleared.and(notRecused).and(DSL.or(withoutItem, withItem))
    }

    /**
     * The rows of a report of [forumId] that [viewer] may see, as a condition on `actions_report_row`: the rule on the row's
     * level and item columns as they were when the report was built, or the viewer involved in the row's action now.
     * Correction rows (section 6) have no action and follow the rule alone. Counts come from these rows, so a count never
     * reveals a row.
     */
    fun report(
        viewer: ActionViewer,
        forumId: UUID,
    ): Condition {
        val r = ACTIONS_REPORT_ROW
        val a = ACTIONS_ITEM
        val involved = DSL.exists(DSL.selectOne().from(a).where(a.TENANT_ID.eq(r.TENANT_ID), a.ID.eq(r.ACTION_ID), involved(viewer)))
        return DSL.or(byRule(viewer, forumId, r.CLASSIFICATION_ID, REPORT_COLUMNS), involved)
    }

    private companion object {
        /** A report row's copied item columns, read as an action's are (no attendee reads an action). */
        val REPORT_COLUMNS =
            ItemColumns(
                item = ACTIONS_REPORT_ROW.ITEM_ID,
                parent = ACTIONS_REPORT_ROW.ITEM_PARENT_ID,
                classification = ACTIONS_REPORT_ROW.ITEM_CLASSIFICATION_ID,
                restrictedTo = ACTIONS_REPORT_ROW.ITEM_RESTRICTED_TO,
                titleHidden = ACTIONS_REPORT_ROW.ITEM_TITLE_HIDDEN,
                membersOnly = ACTIONS_REPORT_ROW.ITEM_MEMBERS_ONLY,
                attendee = { DSL.falseCondition() },
            )
    }
}
