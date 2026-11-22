package com.memo.minutes.internal

import com.memo.common.authz.ForbiddenException
import com.memo.common.authz.Permission
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.id.IdGenerator
import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import com.memo.forum.Outcome
import com.memo.forum.OutcomeKind
import com.memo.forum.SubmissionPurpose
import com.memo.meeting.AgendaItemKind
import com.memo.meeting.AgendaItemRef
import com.memo.meeting.MeetingStatus
import com.memo.meeting.MeetingView
import com.memo.meeting.MeetingViewers
import com.memo.meeting.Meetings
import com.memo.meeting.QuorumSnapshots
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Deciding a consent section in one action (ADR-0060, design E06 §1.1): every item still in the section of the latest
 * revision (not lifted for discussion, not decided yet) gets its default outcome (the first APPROVED kind for "For
 * decision", NOTED otherwise), one decision and one quorum snapshot per item (recusals differ per item) under a shared
 * batch id, all in one transaction. A secretary recused from any item of the section cannot (403 `RECUSED`); the items
 * are then corrected one by one.
 */
@Service
class ConsentDecisions(
    private val viewers: MeetingViewers,
    private val recorder: DecisionRecorder,
    private val snapshots: QuorumSnapshots,
    private val store: DecisionStore,
    private val reads: DecisionReads,
    private val stages: MinutesStages,
    private val effects: DecisionEffects,
    private val release: MinutesRelease,
    private val meetings: Meetings,
    private val previous: PreviousMinutes,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    /** Decides consent section [sectionId] of [meetingId]; the decisions written, in agenda order. */
    fun decide(
        meetingId: UUID,
        sectionId: UUID,
        decidedAt: Instant?,
        returnMinutesToDraft: Boolean,
    ): List<CurrentDecision> {
        val view = viewers.of(meetingId)
        val section = section(view, sectionId)
        val decided = store.lockAll(section.map { it.id }).map { it.itemId }.toSet()
        val remaining = section.filter { !it.lifted && it.id !in decided }
        // E03's lock order: the decision rows, the submissions', then the meeting's (its status current, as when recording).
        release.lockSubmissions(remaining.filter { it.kind == AgendaItemKind.SUBMISSION }.mapNotNull { it.submissionId })
        val status = meetings.locked(meetingId)?.status ?: view.meeting.status
        val sendBack = DecisionRules.window(status, stages.stage(meetingId), null, returnMinutesToDraft)
        refuseIf(remaining.isEmpty(), "NOTHING_TO_DECIDE") { "Every item of the section is decided or was lifted for discussion." }
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val planned = plan(view, status, remaining, decidedAt?.truncatedTo(ChronoUnit.MICROS), now)
        requireNoProblems(planned.flatMap { it.problems })
        val batch = ids.next()
        val written = planned.map { write(view, it, now, batch) }
        written.forEach { release.afterChange(it, null, status) }
        if (sendBack) remaining.forEach { stages.returnToDraft(meetingId, it.id) }
        effects.consentDecided(meetingId, sectionId, batch, remaining.map { it.id })
        return written
    }

    /**
     * The section's items, if the actor records decisions (403 otherwise), sees the section (404) and is recused from none
     * of its items (403 `RECUSED`); 409 `WRONG_STATE` unless it is a consent section.
     */
    private fun section(
        view: MeetingView,
        sectionId: UUID,
    ): List<AgendaItemRef> {
        if (!view.manages) throw ForbiddenException(Permission.FORUMS_MANAGE)
        val section = view.agenda.section(sectionId).filter { view.lines.containsKey(it.id) }
        if (section.isEmpty()) throw MinutesNotFoundException("section")
        section.forEach { recorder.requireFree(view, it) }
        refuseIf(section.any { !it.consent }, "WRONG_STATE") { "Only a consent section is decided in one action." }
        return section
    }

    /** Each item's time and default content, with what is wrong with them under `items/{n}/…`. */
    private fun plan(
        view: MeetingView,
        status: MeetingStatus,
        items: List<AgendaItemRef>,
        decidedAt: Instant?,
        now: Instant,
    ): List<Plan> {
        val outcomes = recorder.outcomes(view)
        return items.mapIndexed { i, item ->
            val (at, problem) =
                DecisionRules.decidedAt(
                    decidedAt,
                    recorder.lastStarted(view.meeting.id, item.id),
                    status,
                    view.meeting.startsAt to view.meeting.endsAt,
                    now,
                )
            val (content, problems) = DecisionChecks.content(default(item, outcomes), outcomes, view.meeting.date())
            Plan(item, at, content, (listOfNotNull(problem) + problems).map { FieldProblem("items/$i/${it.field}", it.code, it.message) })
        }
    }

    /** The item's decision with its own snapshot (409 `NOT_QUORATE` refuses the whole section). */
    private fun write(
        view: MeetingView,
        plan: Plan,
        now: Instant,
        batch: UUID,
    ): CurrentDecision {
        val at = checkNotNull(plan.at)
        val snapshot = snapshots.take(view.meeting.id, plan.item.id, at)
        val content = checkNotNull(plan.content)
        val entry =
            DecisionRecorder
                .entry(ids.next(), 1, content, snapshot, at, null, now, batch)
                .copy(approvesMinutesIds = previous.approvedBy(view.meeting.id, plan.item, content.outcome.kind))
        val head = head(view.meeting.id, view.meeting.forumId, plan.item, entry)
        if (!store.insert(head, entry)) throw ConcurrentModificationException("decision on item ${plan.item.id}", -1)
        val recorded = checkNotNull(reads.current(plan.item.id))
        effects.recorded(recorded, null, snapshot)
        previous.decided(recorded)
        return recorded
    }

    /** The item's default decision: approved as presented for "For decision", noted otherwise. */
    private fun default(
        item: AgendaItemRef,
        outcomes: List<Outcome>,
    ): DecisionInput {
        val kind = if (item.purpose == SubmissionPurpose.DECISION) OutcomeKind.APPROVED else OutcomeKind.NOTED
        val outcome = outcomes.firstOrNull { it.kind == kind }
        val text =
            when {
                outcome?.resolution != true -> null
                !item.resolutionSought.isNullOrBlank() -> item.resolutionSought.take(DecisionRules.MAX_TEXT)
                kind == OutcomeKind.APPROVED -> "Approved as presented."
                else -> "Noted."
            }
        return DecisionInput(outcome = kind, text = text)
    }

    private fun head(
        meetingId: UUID,
        forumId: UUID,
        item: AgendaItemRef,
        entry: EntryRow,
    ): DecisionRow {
        val submission = item.submissionId.takeIf { item.kind == AgendaItemKind.SUBMISSION }
        return DecisionRow(
            id = entry.decisionId,
            meetingId = meetingId,
            itemId = item.id,
            forumId = forumId,
            submissionId = submission,
            memoId = item.memoId.takeIf { submission != null },
            currentSeq = 1,
            releasedAt = null,
            releasedKind = null,
            finalAt = null,
            version = 0,
        )
    }
}

/** One item of the section as planned: when it was taken, its default content, and what is wrong with them. */
private data class Plan(
    val item: AgendaItemRef,
    val at: Instant?,
    val content: DecisionContent?,
    val problems: List<FieldProblem>,
)
