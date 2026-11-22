package com.memo.actions.internal

import com.memo.actions.internal.ActionStore.Column
import com.memo.common.authz.Clearances
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.web.FieldProblem
import com.memo.forum.ActionsPolicy
import com.memo.org.OrgDirectory
import com.memo.workflow.WorkflowResolutions
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Who an issued action names, and what it says (ADR-0067, design E07 §1.3, §3): a secretary or `forums.manage` not
 * recused changes its owner (a person or a position) with a reason, and edits its title, description, context, priority,
 * level (up only) and contributors with a reason. The open step resolves again from the action (`WorkflowResolutions.reresolve`):
 * a new owner gets the owner's task (a position's holder, a substitute), and the verifier pool leaves out new
 * contributors. The owner cannot pass an action on: only an officer changes it.
 */
@Service
class ActionOwnership(
    private val store: ActionStore,
    private val lines: ActionLines,
    private val scenes: ActionScenes,
    private val people: ActionPeople,
    private val clearances: Clearances,
    private val org: OrgDirectory,
    private val resolutions: WorkflowResolutions,
    private val items: ActionItemEffects,
    private val effects: ActionProgressEffects,
    private val clock: Clock,
) {
    /** Gives [actionId], seen at [version], to [owner] for [reason]. */
    fun changeOwner(
        actionId: UUID,
        version: Long,
        owner: OwnerInput?,
        reason: String?,
    ): ActionRow {
        val scene = scenes.scene(actionId)
        val row = scene.row
        scenes.requireManager(scene)
        ActionChecks.requireOpen(row.status, "The owner of an open action is changed.")
        val trimmed = reason?.trim()
        val next = owner?.let(::ownerOf)
        requireNoProblems(
            listOfNotNull(
                FieldProblem("owner", "REQUIRED", "Choose who owns the action.").takeIf { owner == null },
                owner?.let { ActionRules.fields(ActionFields(row.title, owner = it)).firstOrNull { p -> p.field.startsWith("owner") } },
                FieldProblem("owner", SAME_OWNER, "This is the action's owner already.").takeIf { next != null && next == row.owner },
                FieldProblem("owner", "SAME_PERSON", "This person is among the people involved: remove them there first.")
                    .takeIf { next?.userId != null && next.userId in scene.contributors },
                ActionChecks.reason(trimmed),
            ),
        )
        requireNoProblems(people.problems(owner, scene.contributors, row.classificationId, row.itemId))
        val newOwner = checkNotNull(next)
        val holder = newOwner.userId ?: newOwner.positionId?.let { org.holders(listOf(it))[it] }
        val values =
            mapOf<Column, Any?>(
                Column.OWNER_KIND to newOwner.kind.name,
                Column.OWNER_USER_ID to newOwner.userId,
                Column.OWNER_POSITION_ID to newOwner.positionId,
                Column.HOLDER_USER_ID to holder,
            )
        if (version != row.version || !store.change(row.id, version, ActionChecks.OPEN, values)) {
            throw ConcurrentModificationException("action ${row.id}", version)
        }
        val details =
            mapOf(
                "fromKind" to row.owner?.kind?.name,
                "fromUserId" to row.owner?.userId?.toString(),
                "fromPositionId" to row.owner?.positionId?.toString(),
                "toKind" to newOwner.kind.name,
                "toUserId" to newOwner.userId?.toString(),
                "toPositionId" to newOwner.positionId?.toString(),
            )
        lines.entry(row.id, "OWNER_CHANGED", scene.viewer.userId, details, now(), EntryMore(text = trimmed))
        // The owner's open task moves: the slot resolves again (a position to its holder, a substitute for them).
        resolutions.reresolve(ActionsPolicy.SUBJECT, row.id)
        val after = checkNotNull(store.find(row.id))
        val told = scene.owners + setOfNotNull(after.holderUserId, holder)
        effects.ownerChanged(row, after, checkNotNull(trimmed), told, scene.viewer.userId)
        return after
    }

    /**
     * Edits the issued [actionId], seen at [version], with [fields] for [reason]: its title, description, context, priority,
     * level (never lower than it is) and contributors. Its owner, date and source note have their own changes (422
     * `NOT_EDITABLE` when they differ).
     */
    fun editIssued(
        actionId: UUID,
        version: Long,
        fields: ActionFields,
        reason: String?,
    ): ActionRow {
        val scene = scenes.scene(actionId)
        val row = scene.row
        scenes.requireManager(scene)
        ActionChecks.requireOpen(row.status, "An action is edited while it is open.")
        val trimmed = reason?.trim()
        val level = fields.classificationId ?: row.classificationId
        val currentRank = clearances.level(row.classificationId)?.rank ?: 0
        val chosenRank = clearances.level(level)?.rank
        val ownerInput = row.owner?.let { OwnerInput(it.kind, it.userId, it.positionId) }
        val asked = fields.owner
        val note = fields.sourceNote
        requireNoProblems(
            listOfNotNull(
                ActionChecks.reason(trimmed),
                FieldProblem("owner", NOT_EDITABLE, "Change the owner with its own action.")
                    .takeIf { asked != null && ownerOf(asked) != row.owner },
                FieldProblem("dueDate", NOT_EDITABLE, "Change the date with its own action.")
                    .takeIf { fields.dueDate != null && fields.dueDate != row.dueDate },
                FieldProblem("sourceNote", NOT_EDITABLE, "The source note is kept once the action is issued.")
                    .takeIf { note != null && note.trim() != row.sourceNote },
                ActionRules.classification(chosenRank, currentRank),
            ) + ActionRules.fields(fields.copy(owner = ownerInput, sourceNote = null)),
        )
        requireNoProblems(people.problems(ownerInput, fields.contributors, level, row.itemId))
        val edited =
            row.copy(
                title = fields.title.orEmpty().trim(),
                description = fields.description?.takeIf { it.isNotBlank() },
                context = fields.context?.takeIf { it.isNotBlank() },
                priority = fields.priority,
                classificationId = level,
            )
        val values =
            mapOf<Column, Any?>(
                Column.TITLE to edited.title,
                Column.DESCRIPTION to edited.description,
                Column.CONTEXT to edited.context,
                Column.PRIORITY to edited.priority.name,
                Column.CLASSIFICATION_ID to edited.classificationId,
            )
        if (version != row.version || !store.change(row.id, version, ActionChecks.OPEN, values)) {
            throw ConcurrentModificationException("action ${row.id}", version)
        }
        val now = now()
        val before = scene.contributors
        val after = fields.contributors.distinct()
        lines.setContributors(row.id, after, scene.viewer.userId, now)
        val changed = changedFields(row, edited) + listOfNotNull("contributors".takeIf { before.toSet() != after.toSet() })
        lines.entry(row.id, "EDITED", scene.viewer.userId, mapOf("fields" to changed), now, EntryMore(text = trimmed))
        items.edited(row, edited, before, after, trimmed)
        effects.contributors(row, after - before.toSet(), before - after.toSet())
        // A new contributor never verifies: while it waits for verification, the pool is drawn again.
        if (row.status == ActionStatus.DONE_PENDING && before.toSet() != after.toSet()) resolutions.reresolve(ActionsPolicy.SUBJECT, row.id)
        return checkNotNull(store.find(row.id))
    }

    private fun changedFields(
        before: ActionRow,
        after: ActionRow,
    ): List<String> =
        listOfNotNull(
            "title".takeIf { before.title != after.title },
            "description".takeIf { before.description != after.description },
            "context".takeIf { before.context != after.context },
            "priority".takeIf { before.priority != after.priority },
            "classificationId".takeIf { before.classificationId != after.classificationId },
        )

    private fun ownerOf(input: OwnerInput): ActionOwner? =
        when (input.kind) {
            OwnerKind.PERSON -> input.userId?.let(ActionOwner::person)
            OwnerKind.POSITION -> input.positionId?.let(ActionOwner::position)
        }

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)

    companion object {
        const val SAME_OWNER = "SAME_OWNER"
        const val NOT_EDITABLE = "NOT_EDITABLE"
    }
}
