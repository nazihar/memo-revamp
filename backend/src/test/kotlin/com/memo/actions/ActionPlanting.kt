package com.memo.actions

import com.memo.actions.internal.ActionOwner
import com.memo.actions.internal.ActionPriority
import com.memo.actions.internal.ActionRow
import com.memo.actions.internal.ActionSource
import com.memo.actions.internal.ActionStatus
import com.memo.actions.internal.ActionStore
import com.memo.actions.internal.ItemCopy
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantId
import com.memo.common.tenant.TenantTransactions
import com.memo.jooq.tables.references.ACTIONS_ITEM
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Actions written straight through the store, for the leak suites (`PaperLeakIT`, `RecusalLeakIT`) and the item-column
 * refresh: their planted meetings are scheduled weeks ahead, where no action can be raised, so the rows a real meeting
 * would have are set up as the app role in the tenant (as `DecisionPlanting` does for decisions). An issued one carries a
 * reference and its owner as holder, without a workflow instance.
 */
class ActionPlanting(
    private val transactions: TenantTransactions,
    private val store: ActionStore,
    private val tenantDsl: TenantDsl,
) {
    /** An action on [itemId] of [meetingId] (null: the meeting's), with the item's columns as given; its id. */
    @Suppress("LongParameterList")
    fun action(
        tenant: TenantId,
        forumId: UUID,
        meetingId: UUID,
        itemId: UUID?,
        item: ItemCopy?,
        classificationId: UUID,
        by: UUID,
        title: String,
        owner: UUID,
        issued: Boolean = true,
        context: String? = null,
    ): UUID {
        val id = UUID.randomUUID()
        val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val due = LocalDate.now(ZoneOffset.UTC).plusDays(30)
        val row =
            ActionRow(
                id = id,
                forumId = forumId,
                numberYear = null,
                sequence = null,
                reference = null,
                source = if (itemId == null) ActionSource.MEETING else ActionSource.ITEM,
                meetingId = meetingId,
                itemId = itemId,
                decisionId = null,
                decisionSeq = null,
                conditionIndex = null,
                sourceNote = null,
                sourceLabel = "BRC 1/2026" + (itemId?.let { ", item 2" } ?: ""),
                item = item,
                title = title,
                description = null,
                context = context,
                classificationId = classificationId,
                owner = ActionOwner.person(owner),
                holderUserId = null,
                ownerStep = null,
                priority = ActionPriority.NORMAL,
                dueDate = due,
                originalDueDate = null,
                dueRevisions = 0,
                amberFrom = null,
                status = ActionStatus.DRAFT,
                workflowInstanceId = null,
                stepName = null,
                doneAt = null,
                closedAt = null,
                cancelKind = null,
                reopenCount = 0,
                createdBy = by,
                createdAt = now,
                issuedBy = null,
                issuedAt = null,
                version = 0,
            )
        transactions.inTenant(tenant) {
            store.insert(row)
            if (issued) issue(row, owner)
        }
        return id
    }

    /** Numbers [row] as issued, its owner holding the owner's task. */
    private fun issue(
        row: ActionRow,
        owner: UUID,
    ) {
        val due = checkNotNull(row.dueDate)
        val number = store.nextNumber(row.forumId, due.year)
        check(
            store.issue(
                row.id,
                0,
                ActionStore.Issue(due.year, number, "BRC A$number/${due.year}", due, null, row.createdBy, row.createdAt),
            ),
        )
        val a = ACTIONS_ITEM
        tenantDsl
            .current()
            .update(a)
            .set(a.HOLDER_USER_ID, owner)
            .set(a.VERSION, a.VERSION.plus(1))
            .where(a.ID.eq(row.id))
            .execute()
    }
}
