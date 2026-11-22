package com.memo.actions.internal

import com.memo.common.authz.Permission
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.forum.ActionsPolicy
import com.memo.forum.ForumCodes
import com.memo.forum.ForumPolicies
import com.memo.meeting.MeetingViewers
import com.memo.org.OrgDirectory
import com.memo.workflow.Workflows
import com.memo.workflow.WorkingDays
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** A draft the actor may issue, at the version they last saw it. */
data class IssuableDraft(
    val id: UUID,
    val version: Long,
)

/**
 * Issuing drafts (ADR-0067, design E07 §1.2): owner and due date required (422 `REQUIRED`), the people checked again, the
 * reference taken from the committee's counter for the year (gapless, under the counter row's lock), the workflow
 * started on the definition the committee type names today, and the holder of the owner's task told.
 */
@Service
class ActionIssuing(
    private val store: ActionStore,
    private val lines: ActionLines,
    private val tracking: ActionTracking,
    private val scenes: ActionScenes,
    private val people: ActionPeople,
    private val meetingViewers: MeetingViewers,
    private val policies: ForumPolicies,
    private val codes: ForumCodes,
    private val org: OrgDirectory,
    private val workingDays: WorkingDays,
    private val workflows: Workflows,
    private val effects: ActionItemEffects,
    private val clock: Clock,
) {
    /** Issues the draft [actionId], seen at [version] (any version when null). */
    fun issue(
        actionId: UUID,
        version: Long?,
    ): ActionRow {
        val row = scenes.officer(actionId)
        ActionRules.requireDraft(row.status)
        val today = org.today()
        val ownerInput = row.owner?.let { OwnerInput(it.kind, it.userId, it.positionId) }
        requireNoProblems(
            ActionRules.issuable(row.owner, row.dueDate, today) +
                people.problems(ownerInput, lines.contributorsOf(row.id), row.classificationId, row.itemId),
        )
        val due = checkNotNull(row.dueDate)
        val actions = policies.current(row.forumId)?.actions ?: ActionsPolicy()
        val year = today.year
        val number = store.nextNumber(row.forumId, year)
        val reference = ActionReferences.reference(codes.lock(row.forumId), number, year)
        val stale = actions.staleWorkingDays.takeIf { it != ActionsPolicy.OFF }?.let { workingDays.after(today, it) }
        val amber = workingDays.before(due, actions.amberWorkingDays)
        val issue = ActionStore.Issue(year, number, reference, due, amber, actor(), now(), stale)
        val current = version ?: row.version
        if (current != row.version || !store.issue(row.id, current, issue)) {
            throw ConcurrentModificationException("action $actionId", current)
        }
        lines.entry(row.id, "ISSUED", actor(), mapOf("reference" to reference, "dueDate" to due.toString()), now())
        val started = workflows.start(ActionsPolicy.SUBJECT, row.id, emptyList(), definitionKey = actions.definitionKey)
        tracking.setInstance(row.id, started.instanceId)
        val issued = checkNotNull(store.find(row.id))
        effects.issued(issued, actions.definitionKey)
        return issued
    }

    /**
     * The drafts of [meetingId] the actor may issue (`POST /meetings/{id}/actions/issue`): all of them for a secretary or
     * `forums.manage`, but those of items they are recused from or do not see in full, which another secretary issues.
     */
    fun issuable(meetingId: UUID): List<IssuableDraft> {
        val view = meetingViewers.of(meetingId)
        forbidUnless(view.manages, Permission.FORUMS_MANAGE)
        return store
            .draftsOf(meetingId)
            .filter { draft ->
                val item = draft.itemId
                item == null || (item !in view.recused && view.seesItem(item))
            }.map { IssuableDraft(it.id, it.version) }
    }

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)
}
