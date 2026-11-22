package com.memo.actions.internal

import com.memo.common.authz.Clearances
import com.memo.common.authz.Permission
import com.memo.common.authz.Policy
import com.memo.common.web.FieldProblem
import com.memo.forum.ForumDirectory
import com.memo.forum.ForumPolicies
import com.memo.forum.ForumRef
import com.memo.meeting.Meetings
import com.memo.org.OrgDirectory
import org.springframework.stereotype.Component
import java.util.UUID

/** An action locked for a change, and how the actor stands to it (design E07 §1.4, §4). */
class ActionScene(
    val row: ActionRow,
    val viewer: ActionViewer,
    /** The actor is recused from the action's item (ADR-0062). */
    val recused: Boolean,
    val contributors: List<UUID>,
    /** Who owns it now: the person, the position's holder, the holder of the owner's task. */
    val owners: Set<UUID>,
) {
    /** The actor owns the action: the person, the position's holder today, or whoever holds the owner's task. */
    val owner: Boolean get() = viewer.userId in owners || row.owner?.positionId?.let { it in viewer.positions } == true

    val contributor: Boolean get() = viewer.userId in contributors
}

/**
 * What every officer change of an action shares (design E07 §1.1, §1.14): the action locked and seen by the actor, the
 * level its source gives it, and the checks of its fields and people. 404 for an action the actor does not see, 403 for
 * one they see but do not raise actions for, 403 `RECUSED` for an officer recused from its item.
 */
@Component
class ActionScenes(
    private val store: ActionStore,
    private val access: ActionAccess,
    private val viewers: ActionViewers,
    private val people: ActionPeople,
    private val lines: ActionLines,
    private val meetings: Meetings,
    private val directory: ForumDirectory,
    private val policies: ForumPolicies,
    private val clearances: Clearances,
    private val policy: Policy,
    private val org: OrgDirectory,
) {
    /** The action [actionId], locked, for a change by an officer of its committee who is not recused from its item. */
    fun officer(actionId: UUID): ActionRow {
        val row = store.lock(actionId)
        val viewer = viewers.actor()
        notFoundUnless(row != null && access.sees(viewer, row.id, row.forumId), "action")
        checkNotNull(row)
        forbidUnless(viewer.manages(row.forumId), Permission.FORUMS_MANAGE)
        forbidUnless(
            row.itemId == null || !access.recused(viewer, row.id),
            reason = ActionRules.RECUSED,
            detail = "You declared an interest in this item: another officer changes its actions.",
        )
        return row
    }

    /** The action [actionId], locked, as the actor stands to it: 404 when they do not see it. */
    fun scene(actionId: UUID): ActionScene {
        val row = store.lock(actionId)
        val viewer = viewers.actor()
        notFoundUnless(row != null && access.sees(viewer, row.id, row.forumId), "action")
        checkNotNull(row)
        val recused = row.itemId != null && access.recused(viewer, row.id)
        return ActionScene(row, viewer, recused, lines.contributorsOf(row.id), owners(row))
    }

    /** 403 `RECUSED` for an actor recused from the action's item. */
    fun requireNotRecused(scene: ActionScene) =
        forbidUnless(
            !scene.recused,
            reason = ActionRules.RECUSED,
            detail = "You declared an interest in this item: another officer changes its actions.",
        )

    /** A secretary of the committee in office or its chair (the vice-chair when there is none), not recused. */
    fun requireChairOrSecretary(scene: ActionScene) {
        val forum = scene.row.forumId
        forbidUnless(scene.viewer.secretary(forum) || scene.viewer.chairs(forum))
        requireNotRecused(scene)
    }

    /** A secretary of the committee or `forums.manage`, not recused (who raises, re-owns, re-dates and reopens actions). */
    fun requireManager(scene: ActionScene) {
        forbidUnless(scene.viewer.manages(scene.row.forumId), Permission.FORUMS_MANAGE)
        requireNotRecused(scene)
    }

    /** Who owns [row] now: the person, the position's holder today, and the holder of the owner's task (a substitute). */
    fun owners(row: ActionRow): Set<UUID> =
        setOfNotNull(
            row.owner?.userId,
            row.owner?.positionId?.let { org.holders(listOf(it))[it] },
            row.holderUserId.takeIf { row.status != ActionStatus.DRAFT },
        )

    /** The level the action's source gives it: the item's, the meeting's, or the committee's. */
    fun sourceLevel(row: ActionRow): UUID =
        row.item?.classificationId
            ?: row.meetingId?.let { meetings.find(it)?.classificationId }
            ?: level(checkNotNull(directory.forum(row.forumId)))

    /** A committee's level: its own, else its type's default, else the tenant's lowest. */
    fun level(forum: ForumRef): UUID =
        forum.classificationId ?: policies.current(forum.id)?.defaultClassificationId ?: clearances.lowest().id

    /**
     * The fields' problems (lengths, the people, the date, the level: the source's [base] or higher, and one the actor is
     * cleared for), with [more]; 422 if there are any. Returns [fields] with the level filled in.
     */
    fun check(
        fields: ActionFields,
        base: UUID,
        itemId: UUID?,
        more: List<FieldProblem>,
    ): ActionFields {
        val baseRank = clearances.level(base)?.rank ?: 0
        val chosen = fields.classificationId ?: base
        val chosenRank = clearances.level(chosen)?.rank
        val level =
            ActionRules.classification(chosenRank, baseRank)
                ?: FieldProblem("classificationId", "CLASSIFICATION", "You are not cleared for this level.")
                    .takeIf { (chosenRank ?: 0) > policy.clearanceRank() }
        val named = if (level == null) people.problems(fields.owner, fields.contributors, chosen, itemId) else emptyList()
        requireNoProblems(more + ActionRules.fields(fields) + listOfNotNull(level, ActionRules.due(fields.dueDate, org.today())) + named)
        return fields.copy(classificationId = chosen)
    }
}
