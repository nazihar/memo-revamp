package com.memo.actions.internal

import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.records.ActionsItemRecord
import com.memo.jooq.tables.references.ACTIONS_COUNTER
import com.memo.jooq.tables.references.ACTIONS_ITEM
import org.jooq.Condition
import org.jooq.Field
import org.jooq.impl.DSL
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
    // Progress, dates and endings (T4): kept last, with defaults, so a draft is written without them.
    val confidence: Confidence? = null,
    val lastUpdateAt: Instant? = null,
    val staleFrom: LocalDate? = null,
    val ragOverride: RagColour? = null,
    val ragOverrideReason: String? = null,
    val ragOverrideBy: UUID? = null,
    val ragOverrideUntil: LocalDate? = null,
    val cancelReason: String? = null,
    val supersededBy: UUID? = null,
    val closedBy: UUID? = null,
) {
    /** What red, amber and green read of it (design E07 §1.5). */
    val ragFacts: RagFacts get() = RagFacts(status, dueDate, amberFrom, staleFrom, confidence, ragOverride, ragOverrideUntil)
}

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
    val more: EntryMore = EntryMore(),
)

/** How a secretary heard from the owner they write for (design E07 §1.6). */
enum class How { PHONE, EMAIL, IN_PERSON, MEETING }

/** What an entry carries beyond its kind and details: what someone wrote, and how. */
data class EntryMore(
    /** The owner a secretary writes for. */
    val onBehalfOf: UUID? = null,
    val how: How? = null,
    /** What was written (an update, a reason): business content, never audited. */
    val text: String? = null,
    val confidence: Confidence? = null,
    /** Evidence files (`action.evidence`). */
    val fileIds: List<UUID> = emptyList(),
    /** The earlier entry this one corrects. */
    val corrects: Int? = null,
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

    /** The actions matching [condition], oldest first, at most [limit]. */
    fun where(
        condition: Condition,
        limit: Int = Int.MAX_VALUE,
    ): List<ActionRow> =
        tenantDsl
            .current()
            .selectFrom(ACTIONS_ITEM)
            .where(condition)
            .orderBy(ACTIONS_ITEM.CREATED_AT, ACTIONS_ITEM.ID)
            .limit(limit)
            .fetch { it.actionRow() }

    /** [meetingId]'s drafts, oldest first. */
    fun draftsOf(meetingId: UUID): List<ActionRow> =
        where(ACTIONS_ITEM.MEETING_ID.eq(meetingId).and(ACTIONS_ITEM.STATUS.eq(ActionStatus.DRAFT.name)))

    /** The actions raised from [decisionId]. */
    fun ofDecision(decisionId: UUID): List<ActionRow> = where(ACTIONS_ITEM.DECISION_ID.eq(decisionId))

    /** The actions of [meetingId], drafts included, oldest first. */
    fun ofMeeting(meetingId: UUID): List<ActionRow> = where(ACTIONS_ITEM.MEETING_ID.eq(meetingId))

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
            .set(a.STALE_FROM, issue.staleFrom)
            .set(a.ISSUED_BY, issue.by)
            .set(a.ISSUED_AT, issue.at.atOffset(ZoneOffset.UTC))
            .set(a.VERSION, version + 1)
            .where(a.ID.eq(id), a.STATUS.eq(ActionStatus.DRAFT.name), a.VERSION.eq(version), a.DUE_DATE.eq(issue.dueDate))
            .execute() == 1
    }

    /**
     * Changes the open action [id] as seen at [version] in one of [from]: sets [values] and moves the version by one (the
     * guarded update of design E07 §1.1, §6); false when it moved on or is in another status. Instants are written in
     * UTC and enums by name.
     */
    fun change(
        id: UUID,
        version: Long,
        from: Collection<ActionStatus>,
        values: Map<Column, Any?>,
    ): Boolean {
        val a = ACTIONS_ITEM
        val set: Map<Field<*>, Any?> =
            values.entries.associate { (column, value) ->
                column.field to
                    when (value) {
                        is Instant -> value.atOffset(ZoneOffset.UTC)
                        is Enum<*> -> value.name
                        else -> value
                    }
            } + (a.VERSION to version + 1)
        return tenantDsl
            .current()
            .update(a)
            .set(set)
            .where(a.ID.eq(id), a.STATUS.`in`(from.map { it.name }), a.VERSION.eq(version))
            .execute() == 1
    }

    /** The columns of an action its changes set (design E07 §1.1); the rest are set once or by the engine's events. */
    enum class Column(
        val field: Field<*>,
    ) {
        STATUS(ACTIONS_ITEM.STATUS),
        TITLE(ACTIONS_ITEM.TITLE),
        DESCRIPTION(ACTIONS_ITEM.DESCRIPTION),
        CONTEXT(ACTIONS_ITEM.CONTEXT),
        PRIORITY(ACTIONS_ITEM.PRIORITY),
        CLASSIFICATION_ID(ACTIONS_ITEM.CLASSIFICATION_ID),
        OWNER_KIND(ACTIONS_ITEM.OWNER_KIND),
        OWNER_USER_ID(ACTIONS_ITEM.OWNER_USER_ID),
        OWNER_POSITION_ID(ACTIONS_ITEM.OWNER_POSITION_ID),
        HOLDER_USER_ID(ACTIONS_ITEM.HOLDER_USER_ID),
        OWNER_STEP(ACTIONS_ITEM.OWNER_STEP),
        STEP_NAME(ACTIONS_ITEM.STEP_NAME),
        WORKFLOW_INSTANCE_ID(ACTIONS_ITEM.WORKFLOW_INSTANCE_ID),
        DUE_DATE(ACTIONS_ITEM.DUE_DATE),
        DUE_REVISIONS(ACTIONS_ITEM.DUE_REVISIONS),
        AMBER_FROM(ACTIONS_ITEM.AMBER_FROM),
        CONFIDENCE(ACTIONS_ITEM.CONFIDENCE),
        LAST_UPDATE_AT(ACTIONS_ITEM.LAST_UPDATE_AT),
        STALE_FROM(ACTIONS_ITEM.STALE_FROM),
        RAG_OVERRIDE(ACTIONS_ITEM.RAG_OVERRIDE),
        RAG_OVERRIDE_REASON(ACTIONS_ITEM.RAG_OVERRIDE_REASON),
        RAG_OVERRIDE_BY(ACTIONS_ITEM.RAG_OVERRIDE_BY),
        RAG_OVERRIDE_UNTIL(ACTIONS_ITEM.RAG_OVERRIDE_UNTIL),
        DONE_AT(ACTIONS_ITEM.DONE_AT),
        CLOSED_AT(ACTIONS_ITEM.CLOSED_AT),
        CLOSED_BY(ACTIONS_ITEM.CLOSED_BY),
        CANCEL_KIND(ACTIONS_ITEM.CANCEL_KIND),
        CANCEL_REASON(ACTIONS_ITEM.CANCEL_REASON),
        SUPERSEDED_BY(ACTIONS_ITEM.SUPERSEDED_BY),
        REOPEN_COUNT(ACTIONS_ITEM.REOPEN_COUNT),
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
        /** "No recent update" from this day, counted from the issue; null when the committee type has no such rule. */
        val staleFrom: LocalDate? = null,
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
        confidence = confidence?.let(Confidence::valueOf),
        lastUpdateAt = lastUpdateAt?.toInstant(),
        staleFrom = staleFrom,
        ragOverride = ragOverride?.let(RagColour::valueOf),
        ragOverrideReason = ragOverrideReason,
        ragOverrideBy = ragOverrideBy,
        ragOverrideUntil = ragOverrideUntil,
        cancelReason = cancelReason,
        supersededBy = supersededBy,
        closedBy = closedBy,
    )

/** The issued actions not finished (OPEN, IN_PROGRESS, DONE_PENDING), as the daily check reads them ([ActionStore.where]). */
internal val OPEN_ACTIONS: Condition get() = ACTIONS_ITEM.STATUS.`in`(ActionChecks.OPEN.map { it.name })

/**
 * The open actions (issued, not finished) after [after] in the order of [ActionStore.where] (creation, then id), at most
 * [limit]: a keyset page of `actions_item_open_idx`, so a pass over a tenant's open actions never reads the finished ones.
 */
internal fun ActionStore.openAfter(
    after: ActionRow?,
    limit: Int,
): List<ActionRow> {
    val position =
        after?.let {
            DSL
                .row(ACTIONS_ITEM.CREATED_AT, ACTIONS_ITEM.ID)
                .gt(DSL.row(DSL.inline(it.createdAt.atOffset(ZoneOffset.UTC)), DSL.inline(it.id)))
        } ?: DSL.noCondition()
    return where(OPEN_ACTIONS.and(position), limit)
}

/** The actions of [forumId] called [title], oldest first (the demo's seeder finds what it made by its title, E07 T12). */
internal fun ActionStore.titled(
    forumId: UUID,
    title: String,
): List<ActionRow> = where(ACTIONS_ITEM.FORUM_ID.eq(forumId).and(ACTIONS_ITEM.TITLE.eq(title)))
