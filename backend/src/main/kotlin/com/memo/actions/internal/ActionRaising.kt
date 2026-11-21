package com.memo.actions.internal

import com.memo.common.authz.Permission
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.id.IdGenerator
import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import com.memo.forum.ForumDirectory
import com.memo.forum.ForumRef
import com.memo.forum.ForumStatus
import com.memo.meeting.AgendaItemRef
import com.memo.meeting.MeetingView
import com.memo.meeting.MeetingViewers
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** A draft raised from a meeting (design E07 §1.2): from a decision (optionally one of its conditions), an item, or the meeting. */
data class RaiseInput(
    val source: ActionSource,
    val itemId: UUID? = null,
    val decisionId: UUID? = null,
    val conditionIndex: Int? = null,
    val fields: ActionFields,
)

/**
 * Raising, editing and discarding draft actions (ADR-0067, design E07 §1.1, §1.2): the committee's secretaries in office
 * and `forums.manage`, not recused from the source item (403 `RECUSED`). From a meeting while it is in progress, held or
 * minuted (409 `WRONG_STATE`); directly while the committee is active. A raised action is a DRAFT seen only by them.
 * Every change of a draft locks it first (the subject's lock) and is a guarded update on its version. Issuing is
 * [ActionIssuing].
 */
@Service
class ActionRaising(
    private val store: ActionStore,
    private val lines: ActionLines,
    private val scenes: ActionScenes,
    private val viewers: ActionViewers,
    private val meetingViewers: MeetingViewers,
    private val decisions: DecisionSource,
    private val directory: ForumDirectory,
    private val effects: ActionItemEffects,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    /** Raises a draft on [meetingId] from [input]'s source; returns its id. */
    fun raise(
        meetingId: UUID,
        input: RaiseInput,
    ): UUID {
        val view = meetingViewers.of(meetingId)
        forbidUnless(view.manages, Permission.FORUMS_MANAGE)
        val decision = decision(view, input)
        val item = item(view, decision?.itemId ?: input.itemId)
        val kind =
            when {
                decision != null -> ActionSource.DECISION
                item != null -> ActionSource.ITEM
                else -> ActionSource.MEETING
            }
        val forum = checkNotNull(directory.forum(view.meeting.forumId))
        ActionRules.requireRaiseWindow(view.meeting.status)
        val conditions = decision?.conditions.orEmpty()
        val condition = input.conditionIndex?.takeIf { decision != null }
        val prefill = condition?.let(conditions::getOrNull)
        val fields =
            input.fields.copy(
                title = input.fields.title?.takeIf { it.isNotBlank() } ?: prefill?.take(ActionRules.TITLE_MAX),
                context = input.fields.context ?: prefill?.take(ActionRules.CONTEXT_MAX),
                sourceNote = null,
            )
        val problems =
            listOfNotNull(
                FieldProblem("conditionIndex", "SOURCE", "Only an action from a decision names a condition.")
                    .takeIf { input.conditionIndex != null && decision == null },
                ActionRules.condition(condition, conditions.size),
                FieldProblem("sourceNote", "SOURCE", "A source note is for a direct action.").takeIf { input.fields.sourceNote != null },
            )
        val source = Source(kind, view.meeting.id, item, decision, condition)
        val label = ActionReferences.label(kind, forum.name, view.meeting.label, item?.number, condition)
        val base = item?.classificationId ?: view.meeting.classificationId
        return draft(forum, source, label, scenes.check(fields, base, item?.id, problems)).id
    }

    /** Raises a DIRECT draft on [forumId] (the committee's tracker, between meetings); returns its id. */
    fun raiseDirect(
        forumId: UUID,
        fields: ActionFields,
    ): UUID {
        val forum = directory.forum(forumId)
        notFoundUnless(forum != null, "forum")
        checkNotNull(forum)
        val viewer = viewers.actor()
        notFoundUnless(viewer.manages(forumId) || viewer.member(forumId) || viewer.reader, "forum")
        forbidUnless(viewer.manages(forumId), Permission.FORUMS_MANAGE)
        refuseIf(forum.status != ForumStatus.ACTIVE, ActionRules.WRONG_STATE) { "The committee is dissolved." }
        val label = ActionReferences.label(ActionSource.DIRECT, forum.name, null, null, null)
        val checked = scenes.check(fields, scenes.level(forum), null, emptyList())
        return draft(forum, Source(ActionSource.DIRECT, null, null, null, null), label, checked).id
    }

    /** Replaces the draft [actionId]'s fields, seen at [version] (If-Match). */
    fun edit(
        actionId: UUID,
        version: Long,
        fields: ActionFields,
    ): ActionRow {
        val row = scenes.officer(actionId)
        ActionRules.requireDraft(row.status)
        if (version != row.version) throw ConcurrentModificationException("action $actionId", version)
        val direct = row.source == ActionSource.DIRECT
        val problems =
            listOfNotNull(
                FieldProblem(
                    "sourceNote",
                    "SOURCE",
                    "A source note is for a direct action.",
                ).takeIf { fields.sourceNote != null && !direct },
            )
        val checked = scenes.check(fields, scenes.sourceLevel(row), row.itemId, problems)
        val contributorsBefore = lines.contributorsOf(row.id)
        val edited =
            row.copy(
                title = checked.title.orEmpty().trim(),
                description = checked.description?.takeIf { it.isNotBlank() },
                context = checked.context?.takeIf { it.isNotBlank() },
                sourceNote = checked.sourceNote?.trim()?.takeIf { it.isNotEmpty() && direct },
                classificationId = checked.classificationId ?: row.classificationId,
                owner = owner(checked.owner),
                priority = checked.priority,
                dueDate = checked.dueDate,
            )
        if (!store.editDraft(edited, version)) throw ConcurrentModificationException("action $actionId", version)
        lines.setContributors(row.id, checked.contributors, actor(), now())
        effects.edited(row, edited, contributorsBefore, checked.contributors)
        return checkNotNull(store.find(row.id))
    }

    /** Discards the draft [actionId], seen at [version]: CANCELLED, DISCARDED, kept and never numbered. */
    fun discard(
        actionId: UUID,
        version: Long,
        reason: String?,
    ): ActionRow {
        val row = scenes.officer(actionId)
        ActionRules.requireDraft(row.status)
        val trimmed = reason?.trim()?.takeIf { it.isNotEmpty() }
        rejectIf(
            FieldProblem("reason", "LENGTH", "At most ${ActionRules.REASON_MAX} characters.")
                .takeIf { (trimmed?.length ?: 0) > ActionRules.REASON_MAX },
        )
        if (version != row.version || !store.discard(row.id, version, trimmed)) {
            throw ConcurrentModificationException("action $actionId", version)
        }
        lines.entry(row.id, "DISCARDED", actor(), mapOf("reasonSha256" to trimmed?.let(ActionRules::sha256)), now())
        effects.discarded(row, trimmed)
        return checkNotNull(store.find(row.id))
    }

    /** The decision a draft on [view]'s meeting comes from: named (DECISION), or the item's own (ITEM with a decision). */
    private fun decision(
        view: MeetingView,
        input: RaiseInput,
    ): DecisionSourceRef? =
        when (input.source) {
            ActionSource.DECISION -> {
                rejectIf(FieldProblem("decisionId", "REQUIRED", "Name the decision.").takeIf { input.decisionId == null })
                val found =
                    input.decisionId?.let(decisions::find)?.takeIf {
                        it.meetingId == view.meeting.id && (input.itemId == null || input.itemId == it.itemId)
                    }
                rejectIf(FieldProblem("decisionId", "unknown", "No such decision at this meeting.").takeIf { found == null })
                found
            }

            ActionSource.ITEM -> {
                rejectIf(FieldProblem("itemId", "REQUIRED", "Name the item.").takeIf { input.itemId == null })
                // An item with a decision raises from its decision, so the chain to the resolution is kept.
                input.itemId?.let(decisions::ofItem)
            }

            ActionSource.MEETING -> {
                rejectIf(
                    FieldProblem("itemId", "SOURCE", "An action from the meeting names no item.")
                        .takeIf { input.itemId != null || input.decisionId != null },
                )
                null
            }

            ActionSource.DIRECT -> {
                rejectIf(FieldProblem("source", "SOURCE", "A direct action is raised on the committee."))
                null
            }
        }

    /** The item [itemId] of [view]'s meeting: seen in full (404 otherwise) and not recused from (403 `RECUSED`). */
    private fun item(
        view: MeetingView,
        itemId: UUID?,
    ): AgendaItemRef? {
        if (itemId == null) return null
        val item = view.agenda.item(itemId)?.takeIf { view.lines.containsKey(it.id) }
        notFoundUnless(item != null, "item")
        forbidUnless(
            itemId !in view.recused,
            reason = ActionRules.RECUSED,
            detail = "You declared an interest in this item: another officer raises its actions.",
        )
        notFoundUnless(view.seesItem(itemId), "item")
        return item
    }

    private class Source(
        val kind: ActionSource,
        val meetingId: UUID?,
        val item: AgendaItemRef?,
        val decision: DecisionSourceRef?,
        val conditionIndex: Int?,
    )

    /** Writes the draft of [checked] fields from [source], its contributors and its entry, and audits it. */
    private fun draft(
        forum: ForumRef,
        source: Source,
        label: String,
        checked: ActionFields,
    ): ActionRow {
        val now = now()
        val item = source.item
        val row =
            ActionRow(
                id = ids.next(),
                forumId = forum.id,
                numberYear = null,
                sequence = null,
                reference = null,
                source = source.kind,
                meetingId = source.meetingId,
                itemId = item?.id,
                decisionId = source.decision?.decisionId,
                decisionSeq = source.decision?.seq,
                conditionIndex = source.conditionIndex,
                sourceNote = checked.sourceNote?.trim()?.takeIf { it.isNotEmpty() },
                sourceLabel = label,
                item = item?.let { ItemCopy(it.parentId, it.classificationId, it.restrictedTo, it.titleHidden, it.membersOnly) },
                title = checked.title.orEmpty().trim(),
                description = checked.description?.takeIf { it.isNotBlank() },
                context = checked.context?.takeIf { it.isNotBlank() },
                classificationId = checkNotNull(checked.classificationId),
                owner = owner(checked.owner),
                holderUserId = null,
                ownerStep = null,
                priority = checked.priority,
                dueDate = checked.dueDate,
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
                createdBy = actor(),
                createdAt = now,
                issuedBy = null,
                issuedAt = null,
                version = 0,
            )
        store.insert(row)
        lines.setContributors(row.id, checked.contributors, row.createdBy, now)
        lines.entry(row.id, "DRAFTED", row.createdBy, mapOf("source" to source.kind.name), now)
        effects.drafted(row, checked.contributors)
        return row
    }

    private fun owner(input: OwnerInput?): ActionOwner? =
        input?.let {
            when (it.kind) {
                OwnerKind.PERSON -> ActionOwner.person(checkNotNull(it.userId))
                OwnerKind.POSITION -> ActionOwner.position(checkNotNull(it.positionId))
            }
        }

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)
}
