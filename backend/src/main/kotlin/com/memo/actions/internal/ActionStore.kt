package com.memo.actions.internal

import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.records.ActionsItemRecord
import com.memo.jooq.tables.references.ACTIONS_COUNTER
import com.memo.jooq.tables.references.ACTIONS_ITEM
import org.jooq.Condition
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * The source item's columns the visibility rule reads (ADR-0061, design E07 §1.14): copied from the latest published
 * revision when the action is raised, and refreshed when that meeting publishes again.
 */
data class ItemCopy(
    val parentId: UUID?,
    val classificationId: UUID,
    val restrictedTo: List<UUID>?,
    val titleHidden: Boolean,
    val membersOnly: Boolean,
)

/** An action item as stored (design E07 §1.1). */
data class ActionRow(
    val id: UUID,
    val forumId: UUID,
    val numberYear: Int?,
    val sequence: Int?,
    val reference: String?,
    val source: ActionSource,
    val meetingId: UUID?,
    val itemId: UUID?,
    val decisionId: UUID?,
    val decisionSeq: Int?,
    val conditionIndex: Int?,
    val sourceNote: String?,
    val sourceLabel: String,
    val item: ItemCopy?,
    val title: String,
    val description: String?,
    val context: String?,
    val classificationId: UUID,
    val owner: ActionOwner?,
    val holderUserId: UUID?,
    val ownerStep: String?,
    val priority: ActionPriority,
    val dueDate: LocalDate?,
    val originalDueDate: LocalDate?,
    val dueRevisions: Int,
    val amberFrom: LocalDate?,
    val status: ActionStatus,
    val workflowInstanceId: UUID?,
    val stepName: String?,
    val doneAt: Instant?,
    val closedAt: Instant?,
    val cancelKind: CancelKind?,
    val reopenCount: Int,
    val createdBy: UUID,
    val createdAt: Instant,
    val issuedBy: UUID?,
    val issuedAt: Instant?,
    val version: Long,
)

/** A person involved beside the owner, while they are (design E07 §1.3). */
data class ContributorRow(
    val actionId: UUID,
    val userId: UUID,
    val addedBy: UUID,
    val addedAt: Instant,
)

/** One entry of an action's timeline (design E07 §1.6). */
data class EntryRow(
    val actionId: UUID,
    val seq: Int,
    val kind: String,
    val actorId: UUID?,
    val details: Map<String, Any?>,
    val at: Instant,
)

/**
 * Action items, their contributors, counters and timelines (design E07 §1.1, §6), inside a tenant transaction: the locks,
 * the writes and the rows the writing services read under their lock; contributors and the timeline are [ActionLines],
 * the engine's view and the copied item columns [ActionTracking]. Every change of an action is a guarded update
 * (`… WHERE id = ? AND status = ? AND version = ?`, 0 rows a conflict) or, for the engine's events, made under the lock
 * the engine took through the subject; the trigger repeats the rules. Readers reach actions through [ActionAccess].
 */
@Repository
class ActionStore(
    private val tenantDsl: TenantDsl,
) {
    /** The action [id], locked to the end of the transaction (the subject's lock, taken before the instance's). */
    fun lock(id: UUID): ActionRow? =
        tenantDsl
            .current()
            .selectFrom(ACTIONS_ITEM)
            .where(ACTIONS_ITEM.ID.eq(id))
            .forUpdate()
            .fetchOne { it.actionRow() }

    /** The action [id] as it is now, without a lock. */
    fun find(id: UUID): ActionRow? =
        tenantDsl
            .current()
            .selectFrom(ACTIONS_ITEM)
            .where(ACTIONS_ITEM.ID.eq(id))
            .fetchOne { it.actionRow() }

    /** The actions matching [condition], oldest first. */
    fun where(condition: Condition): List<ActionRow> =
        tenantDsl
            .current()
            .selectFrom(ACTIONS_ITEM)
            .where(condition)
            .orderBy(ACTIONS_ITEM.CREATED_AT, ACTIONS_ITEM.ID)
            .fetch { it.actionRow() }

    /** [meetingId]'s drafts, oldest first. */
    fun draftsOf(meetingId: UUID): List<ActionRow> =
        where(ACTIONS_ITEM.MEETING_ID.eq(meetingId).and(ACTIONS_ITEM.STATUS.eq(ActionStatus.DRAFT.name)))

    /** The actions raised from [decisionId]. */
    fun ofDecision(decisionId: UUID): List<ActionRow> = where(ACTIONS_ITEM.DECISION_ID.eq(decisionId))

    /** The actions of [meetingId] raised from an item. */
    fun ofMeetingItems(meetingId: UUID): List<ActionRow> = where(ACTIONS_ITEM.MEETING_ID.eq(meetingId).and(ACTIONS_ITEM.ITEM_ID.isNotNull))

    /** Writes the new draft [row] (its contributors are [ActionLines]'). */
    fun insert(row: ActionRow) {
        val a = ACTIONS_ITEM
        tenantDsl
            .current()
            .insertInto(a)
            .set(a.ID, row.id)
            .set(a.TENANT_ID, tenantDsl.tenant().value)
            .set(a.FORUM_ID, row.forumId)
            .set(a.SOURCE, row.source.name)
            .set(a.MEETING_ID, row.meetingId)
            .set(a.ITEM_ID, row.itemId)
            .set(a.DECISION_ID, row.decisionId)
            .set(a.DECISION_SEQ, row.decisionSeq)
            .set(a.CONDITION_INDEX, row.conditionIndex)
            .set(a.SOURCE_NOTE, row.sourceNote)
            .set(a.SOURCE_LABEL, row.sourceLabel)
            .set(a.ITEM_PARENT_ID, row.item?.parentId)
            .set(a.ITEM_CLASSIFICATION_ID, row.item?.classificationId)
            .set(a.ITEM_RESTRICTED_TO, row.item?.restrictedTo?.toTypedArray<UUID?>())
            .set(a.ITEM_TITLE_HIDDEN, row.item?.titleHidden ?: false)
            .set(a.ITEM_MEMBERS_ONLY, row.item?.membersOnly ?: false)
            .set(a.TITLE, row.title)
            .set(a.DESCRIPTION, row.description)
            .set(a.CONTEXT, row.context)
            .set(a.CLASSIFICATION_ID, row.classificationId)
            .set(a.OWNER_KIND, row.owner?.kind?.name)
            .set(a.OWNER_USER_ID, row.owner?.userId)
            .set(a.OWNER_POSITION_ID, row.owner?.positionId)
            .set(a.PRIORITY, row.priority.name)
            .set(a.DUE_DATE, row.dueDate)
            .set(a.STATUS, row.status.name)
            .set(a.CREATED_BY, row.createdBy)
            .set(a.CREATED_AT, row.createdAt.atOffset(ZoneOffset.UTC))
            .set(a.VERSION, 0L)
            .execute()
    }

    /** Replaces the draft's fields with [row]'s, as seen at [version]; false when it moved on or is no longer a draft. */
    fun editDraft(
        row: ActionRow,
        version: Long,
    ): Boolean {
        val a = ACTIONS_ITEM
        return tenantDsl
            .current()
            .update(a)
            .set(a.TITLE, row.title)
            .set(a.DESCRIPTION, row.description)
            .set(a.CONTEXT, row.context)
            .set(a.SOURCE_NOTE, row.sourceNote)
            .set(a.CLASSIFICATION_ID, row.classificationId)
            .set(a.OWNER_KIND, row.owner?.kind?.name)
            .set(a.OWNER_USER_ID, row.owner?.userId)
            .set(a.OWNER_POSITION_ID, row.owner?.positionId)
            .set(a.PRIORITY, row.priority.name)
            .set(a.DUE_DATE, row.dueDate)
            .set(a.VERSION, version + 1)
            .where(a.ID.eq(row.id), a.STATUS.eq(ActionStatus.DRAFT.name), a.VERSION.eq(version))
            .execute() == 1
    }

    /** Discards the draft as seen at [version] (CANCELLED, DISCARDED; kept, never deleted); false when it moved on. */
    fun discard(
        id: UUID,
        version: Long,
        reason: String?,
    ): Boolean {
        val a = ACTIONS_ITEM
        return tenantDsl
            .current()
            .update(a)
            .set(a.STATUS, ActionStatus.CANCELLED.name)
            .set(a.CANCEL_KIND, CancelKind.DISCARDED.name)
            .set(a.CANCEL_REASON, reason)
            .set(a.VERSION, version + 1)
            .where(a.ID.eq(id), a.STATUS.eq(ActionStatus.DRAFT.name), a.VERSION.eq(version))
            .execute() == 1
    }

    /** The next number of [forumId]'s actions in [year]: the counter row stays locked until commit (gapless). */
    fun nextNumber(
        forumId: UUID,
        year: Int,
    ): Int {
        val c = ACTIONS_COUNTER
        return tenantDsl
            .current()
            .insertInto(c)
            .set(c.TENANT_ID, tenantDsl.tenant().value)
            .set(c.FORUM_ID, forumId)
            .set(c.YEAR, year)
            .set(c.LAST_NUMBER, 1)
            .onConflict(c.TENANT_ID, c.FORUM_ID, c.YEAR)
            .doUpdate()
            .set(c.LAST_NUMBER, c.LAST_NUMBER.plus(1))
            .returning(c.LAST_NUMBER)
            .fetchSingle()
            .lastNumber
    }

    /** Issues the draft as seen at [version]: its reference and dates; false when it moved on or is no longer a draft. */
    fun issue(
        id: UUID,
        version: Long,
        issue: Issue,
    ): Boolean {
        val a = ACTIONS_ITEM
        return tenantDsl
            .current()
            .update(a)
            .set(a.STATUS, ActionStatus.OPEN.name)
            .set(a.NUMBER_YEAR, issue.year)
            .set(a.SEQUENCE, issue.number)
            .set(a.REFERENCE, issue.reference)
            .set(a.ORIGINAL_DUE_DATE, issue.dueDate)
            .set(a.AMBER_FROM, issue.amberFrom)
            .set(a.ISSUED_BY, issue.by)
            .set(a.ISSUED_AT, issue.at.atOffset(ZoneOffset.UTC))
            .set(a.VERSION, version + 1)
            .where(a.ID.eq(id), a.STATUS.eq(ActionStatus.DRAFT.name), a.VERSION.eq(version), a.DUE_DATE.eq(issue.dueDate))
            .execute() == 1
    }

    /** What issuing writes. */
    data class Issue(
        val year: Int,
        val number: Int,
        val reference: String,
        val dueDate: LocalDate,
        val amberFrom: LocalDate?,
        val by: UUID,
        val at: Instant,
    )
}

/** An action record as a row. */
internal fun ActionsItemRecord.actionRow() =
    ActionRow(
        id = id,
        forumId = forumId,
        numberYear = numberYear,
        sequence = sequence,
        reference = reference,
        source = ActionSource.valueOf(source),
        meetingId = meetingId,
        itemId = itemId,
        decisionId = decisionId,
        decisionSeq = decisionSeq,
        conditionIndex = conditionIndex,
        sourceNote = sourceNote,
        sourceLabel = sourceLabel,
        item =
            itemClassificationId?.let {
                ItemCopy(itemParentId, it, itemRestrictedTo?.filterNotNull(), itemTitleHidden == true, itemMembersOnly == true)
            },
        title = title,
        description = description,
        context = context,
        classificationId = classificationId,
        owner =
            ownerKind?.let {
                when (OwnerKind.valueOf(it)) {
                    OwnerKind.PERSON -> ActionOwner.person(checkNotNull(ownerUserId))
                    OwnerKind.POSITION -> ActionOwner.position(checkNotNull(ownerPositionId))
                }
            },
        holderUserId = holderUserId,
        ownerStep = ownerStep,
        priority = priority?.let(ActionPriority::valueOf) ?: ActionPriority.NORMAL,
        dueDate = dueDate,
        originalDueDate = originalDueDate,
        dueRevisions = dueRevisions ?: 0,
        amberFrom = amberFrom,
        status = ActionStatus.valueOf(status),
        workflowInstanceId = workflowInstanceId,
        stepName = stepName,
        doneAt = doneAt?.toInstant(),
        closedAt = closedAt?.toInstant(),
        cancelKind = cancelKind?.let(CancelKind::valueOf),
        reopenCount = reopenCount ?: 0,
        createdBy = createdBy,
        createdAt = createdAt.toInstant(),
        issuedBy = issuedBy,
        issuedAt = issuedAt?.toInstant(),
        version = version ?: 0,
    )
