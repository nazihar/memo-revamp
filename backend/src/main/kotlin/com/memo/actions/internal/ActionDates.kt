package com.memo.actions.internal

import com.memo.actions.internal.ActionStore.Column
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.id.IdGenerator
import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import com.memo.forum.ActionsPolicy
import com.memo.forum.DueChange
import com.memo.forum.ForumDirectory
import com.memo.forum.ForumPolicies
import com.memo.forum.ForumRole
import com.memo.org.OrgDirectory
import com.memo.workflow.Workflows
import com.memo.workflow.WorkingDays
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Agreeing to or declining a request for a new date. */
enum class DueDecision { AGREE, DECLINE }

/**
 * Due-date changes (ADR-0067, design E07 §1.7), by the committee type's `dueChange` today:
 * - SECRETARY_AGREES and CHAIR_AGREES: the owner asks with a reason; a secretary not recused (or the chair, else the
 *   vice-chair, not recused) agrees or declines once. Until then the action keeps its date. One pending request at a time
 *   (409 `PENDING_REQUEST`); it is withdrawn when the date is changed another way or the action is cancelled.
 * - OWNER_WITH_REASON: the owner moves the date with a reason, and the secretaries are told.
 * - A secretary or `forums.manage` not recused may always change it directly, with a reason.
 *
 * A change keeps the original date, counts the revision, moves `amberFrom`, writes an entry and re-plans the owner's
 * step's timers in the same transaction (`Workflows.redue`, ADR-0068).
 */
@Service
class ActionDates(
    private val store: ActionStore,
    private val lines: ActionLines,
    private val requests: ActionDueRequests,
    private val scenes: ActionScenes,
    private val policies: ForumPolicies,
    private val directory: ForumDirectory,
    private val deciders: ActionDeciders,
    private val workingDays: WorkingDays,
    private val workflows: Workflows,
    private val org: OrgDirectory,
    private val effects: ActionDateEffects,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    /** The owner of [actionId], seen at [version], asks for [newDate] for [reason]; returns the request's id. */
    fun request(
        actionId: UUID,
        version: Long,
        newDate: LocalDate?,
        reason: String?,
    ): UUID {
        val scene = scenes.scene(actionId)
        val row = scene.row
        forbidUnless(scene.owner)
        ActionChecks.requireOpen(row.status, "A new date is asked for an action that is open.")
        val mode = policy(row).dueChange
        refuseIf(
            mode == DueChange.OWNER_WITH_REASON,
            OWNER_CHANGES,
        ) { "This committee lets the owner change the date: change it directly." }
        val trimmed = reason?.trim()
        requireNoProblems(dateProblems(row, newDate) + listOfNotNull(ActionChecks.reason(trimmed)))
        refuseIf(requests.pending(row.id) != null, PENDING_REQUEST) { "A new date was asked for already: wait for the answer." }
        val now = now()
        val request =
            DueRequestRow(
                ids.next(),
                row.id,
                checkNotNull(row.dueDate),
                checkNotNull(newDate),
                checkNotNull(trimmed),
                DueRequestState.PENDING,
                scene.viewer.userId,
                now,
                null,
                null,
                null,
            )
        bump(row, version)
        requests.insert(request)
        val details = mapOf("requestId" to request.id.toString(), "from" to request.oldDate.toString(), "to" to newDate.toString())
        lines.entry(row.id, "DUE_REQUESTED", scene.viewer.userId, details, now, EntryMore(text = trimmed))
        effects.dueRequested(row, request, deciders.of(row, mode))
        return request.id
    }

    /** Decides the pending request [requestId] of [actionId], seen at [version], as [decision] with an optional [note]. */
    fun decide(
        actionId: UUID,
        requestId: UUID,
        version: Long,
        decision: DueDecision?,
        note: String?,
    ) {
        val scene = scenes.scene(actionId)
        val row = scene.row
        val request = requests.find(row.id, requestId) ?: throw ActionNotFoundException("request")
        val mode = policy(row).dueChange
        forbidUnless(scene.viewer.userId in deciders.of(row, mode) || deciderButRecused(scene, mode))
        scenes.requireNotRecused(scene)
        refuseIf(request.state != DueRequestState.PENDING, ActionRules.WRONG_STATE) { "This request was answered already." }
        ActionChecks.requireOpen(row.status, "The action is no longer open.")
        val trimmed = note?.trim()?.takeIf { it.isNotEmpty() }
        requireNoProblems(
            listOfNotNull(
                FieldProblem("decision", "REQUIRED", "Agree or decline.").takeIf { decision == null },
                ActionChecks.reason(trimmed, "note", required = false),
                dateProblems(row, request.newDate).firstOrNull()?.takeIf { decision == DueDecision.AGREE },
            ),
        )
        val state = if (decision == DueDecision.AGREE) DueRequestState.AGREED else DueRequestState.DECLINED
        val now = now()
        if (!requests.decide(request.id, state, scene.viewer.userId, now, trimmed)) {
            throw ConcurrentModificationException("due request ${request.id}", version)
        }
        val details = mapOf("requestId" to request.id.toString(), "to" to request.newDate.toString())
        val kind = if (state == DueRequestState.AGREED) "DUE_AGREED" else "DUE_DECLINED"
        lines.entry(row.id, kind, scene.viewer.userId, details, now, EntryMore(text = trimmed))
        effects.dueDecided(row, request, state, trimmed)
        if (state == DueRequestState.AGREED) {
            change(row, version, request.newDate, request.reason, scene.viewer.userId, request.id, emptyList(), request.requestedBy)
        } else {
            bump(row, version)
        }
    }

    /**
     * Changes [actionId]'s due date to [newDate] for [reason], seen at [version]: by a secretary or `forums.manage` not
     * recused, or by the owner when the committee lets them (OWNER_WITH_REASON; the secretaries are told).
     */
    fun changeDirectly(
        actionId: UUID,
        version: Long,
        newDate: LocalDate?,
        reason: String?,
    ) {
        val scene = scenes.scene(actionId)
        val row = scene.row
        val byOwner = scene.owner && !scene.viewer.manages(row.forumId)
        if (byOwner) {
            forbidUnless(policy(row).dueChange == DueChange.OWNER_WITH_REASON, reason = ASK_FOR_IT, detail = "Ask for a new date instead.")
        } else {
            scenes.requireManager(scene)
        }
        ActionChecks.requireOpen(row.status, "The date is changed on an action that is open.")
        val trimmed = reason?.trim()
        requireNoProblems(dateProblems(row, newDate) + listOfNotNull(ActionChecks.reason(trimmed)))
        val pending = requests.pending(row.id)
        if (pending != null) withdraw(row, pending, DUE_CHANGED)
        val secretaries = if (byOwner) effects.secretaries(row, deciders.recused(row)) else emptyList()
        change(row, version, checkNotNull(newDate), checkNotNull(trimmed), scene.viewer.userId, null, secretaries)
    }

    /** Withdraws [request] of [row] because of [cause] (`DUE_CHANGED`, `CANCELLED`; from T5 `DONE`). */
    fun withdraw(
        row: ActionRow,
        request: DueRequestRow,
        cause: String,
    ) {
        val now = now()
        if (!requests.decide(request.id, DueRequestState.WITHDRAWN, actorOrNull(), now, null)) return
        lines.entry(row.id, "DUE_WITHDRAWN", actorOrNull(), mapOf("requestId" to request.id.toString(), "cause" to cause), now)
        effects.dueWithdrawn(row, request, cause)
    }

    /** Writes the new date of [row] (seen at [version]), its revision and amber day, and re-plans the owner's step. */
    @Suppress("LongParameterList")
    private fun change(
        row: ActionRow,
        version: Long,
        newDate: LocalDate,
        reason: String,
        by: UUID,
        requestId: UUID?,
        secretaries: Collection<UUID>,
        requester: UUID? = null,
    ) {
        val from = checkNotNull(row.dueDate)
        val revisions = row.dueRevisions + 1
        val values =
            mapOf<Column, Any?>(
                Column.DUE_DATE to newDate,
                Column.DUE_REVISIONS to revisions,
                Column.AMBER_FROM to workingDays.before(newDate, policy(row).amberWorkingDays),
            )
        if (version != row.version || !store.change(row.id, version, ActionChecks.OPEN, values)) {
            throw ConcurrentModificationException("action ${row.id}", version)
        }
        val details =
            mapOf(
                "from" to from.toString(),
                "to" to newDate.toString(),
                "revisions" to revisions,
                "requestId" to requestId?.toString(),
            )
        lines.entry(row.id, "DUE_CHANGED", by, details, now(), EntryMore(text = reason))
        // The owner's reminders, the due notice and the escalation follow the new date (ADR-0068).
        workflows.redue(ActionsPolicy.SUBJECT, row.id)
        effects.dueChanged(row, from, newDate, revisions, reason, requestId, setOfNotNull(by, requester), secretaries)
    }

    /** Moves the action's version by one: its answer changed (a request asked or declined) though its columns did not. */
    private fun bump(
        row: ActionRow,
        version: Long,
    ) {
        if (version != row.version || !store.change(row.id, version, ActionChecks.OPEN, emptyMap())) {
            throw ConcurrentModificationException("action ${row.id}", version)
        }
    }

    /** A new date's problems: given, today or later, and not the date it has. */
    private fun dateProblems(
        row: ActionRow,
        newDate: LocalDate?,
    ): List<FieldProblem> =
        listOfNotNull(
            FieldProblem("newDate", "REQUIRED", "Choose the new date.").takeIf { newDate == null },
            FieldProblem("newDate", "DUE_IN_PAST", "Please enter a date on or after today.")
                .takeIf { newDate != null && newDate < org.today() },
            FieldProblem("newDate", "SAME_DATE", "The action is due on that date already.").takeIf {
                newDate != null &&
                    newDate == row.dueDate
            },
        )

    /** A decider but for their recusal: answered 403 `RECUSED`, not a plain 403. */
    private fun deciderButRecused(
        scene: ActionScene,
        mode: DueChange,
    ): Boolean {
        if (!scene.recused) return false
        val forum = scene.row.forumId
        return when (mode) {
            DueChange.SECRETARY_AGREES -> scene.viewer.secretary(forum)
            DueChange.CHAIR_AGREES -> directory.membersOn(forum).any { it.userId == scene.viewer.userId && it.role in CHAIRS }
            DueChange.OWNER_WITH_REASON -> false
        }
    }

    private fun policy(row: ActionRow): ActionsPolicy = policies.current(row.forumId)?.actions ?: ActionsPolicy()

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)

    companion object {
        const val PENDING_REQUEST = "PENDING_REQUEST"

        /** Under OWNER_WITH_REASON the owner changes the date, so there is nothing to ask. */
        const val OWNER_CHANGES = "OWNER_CHANGES"

        /** The owner of a committee that agrees dates asks for a new one instead of changing it. */
        const val ASK_FOR_IT = "ASK_FOR_IT"

        const val DUE_CHANGED = "DUE_CHANGED"
        private val CHAIRS = setOf(ForumRole.CHAIR, ForumRole.VICE_CHAIR)
    }
}
