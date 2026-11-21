package com.memo.minutes.internal

import com.memo.common.authz.ForbiddenException
import com.memo.common.authz.Permission
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.id.IdGenerator
import com.memo.common.web.FieldProblem
import com.memo.common.web.PreconditionRequiredException
import com.memo.forum.ForumDirectory
import com.memo.forum.ForumPolicies
import com.memo.forum.ForumStatus
import com.memo.forum.Outcome
import com.memo.forum.OutcomeKind
import com.memo.meeting.AgendaItemKind
import com.memo.meeting.AgendaItemRef
import com.memo.meeting.MeetingAttendance
import com.memo.meeting.MeetingStatus
import com.memo.meeting.MeetingView
import com.memo.meeting.MeetingViewers
import com.memo.meeting.Meetings
import com.memo.meeting.QuorumSnapshot
import com.memo.meeting.QuorumSnapshots
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Where a decision is recorded: the actor on the meeting, the item, and the committee type's outcomes. */
data class DecisionScene(
    val view: MeetingView,
    val item: AgendaItemRef,
    val outcomes: List<Outcome>,
) {
    val meetingId: UUID get() = view.meeting.id

    /** The submission a SUBMISSION item decides; null for other items. */
    val submissionId: UUID? get() = item.submissionId.takeIf { item.kind == AgendaItemKind.SUBMISSION }
}

/**
 * Recording and correcting an item's decision (ADR-0065, design E06 §1.1): a secretary of the committee or `forums.manage`,
 * not recused from the item (403 `RECUSED`), while the meeting is in progress or held and the minutes not approved. The
 * decision row is locked first, then the quorum snapshot is taken in the same transaction (it locks the meeting row:
 * submission rows, the meeting, then the agenda). The people named are checked against that snapshot; "took no part" is
 * derived from it. A correction is a new entry with a reason and keeps the earlier snapshot unless the time changes or a
 * new one is asked for; once the memo has moved on its outcome is fixed.
 */
@Service
class DecisionRecorder(
    private val viewers: MeetingViewers,
    private val snapshots: QuorumSnapshots,
    private val attendance: MeetingAttendance,
    private val policies: ForumPolicies,
    private val directory: ForumDirectory,
    private val meetings: Meetings,
    private val store: DecisionStore,
    private val reads: DecisionReads,
    private val stages: MinutesStages,
    private val effects: DecisionEffects,
    private val release: MinutesRelease,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    /** Records or corrects the decision on [itemId]; [version] is the decision's (If-Match), required once it exists. */
    fun record(
        meetingId: UUID,
        itemId: UUID,
        input: DecisionInput,
        version: Long?,
    ): CurrentDecision {
        val scene = scene(meetingId, itemId)
        val existing = store.lock(itemId)
        val current = existing?.let { reads.current(itemId) }
        if (existing != null) {
            if (version == null) throw PreconditionRequiredException()
            if (version != existing.version) throw ConcurrentModificationException("decision ${existing.id}", version)
        }
        // E03's lock order: the decision row, the submission's, then the meeting's (here, so its status is current: a meeting
        // marked as held meanwhile numbers and releases this decision at once; the snapshot takes the same lock).
        release.lockSubmissions(listOfNotNull(scene.submissionId))
        val status = meetings.locked(meetingId)?.status ?: scene.view.meeting.status
        val sendBack = DecisionRules.window(status, stages.stage(meetingId), existing, input.returnMinutesToDraft)
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val (content, contentProblems) = DecisionChecks.content(input, scene.outcomes, scene.view.meeting.date())
        val (reason, reasonProblem) = if (current == null) null to null else DecisionRules.reason(input.reason)
        val (decidedAt, timeProblem) =
            DecisionRules.decidedAt(
                input.decidedAt?.truncatedTo(ChronoUnit.MICROS),
                current?.entry?.decidedAt ?: lastStarted(meetingId, itemId),
                status,
                scene.view.meeting.startsAt to scene.view.meeting.endsAt,
                now,
            )
        requireNoProblems(contentProblems + listOfNotNull(reasonProblem, timeProblem) + targets(scene, input))
        val checked = checkNotNull(content)
        DecisionRules.released(existing, current?.entry, checked.outcome.kind)
        val snapshot = snapshot(scene, current, input, checkNotNull(decidedAt))
        requireNoProblems(DecisionRules.people(checked, snapshot.inputs) + ratifies(scene, input, snapshot, existing?.id))
        val entry =
            entry(
                decisionId = existing?.id ?: ids.next(),
                seq = (current?.entry?.seq ?: 0) + 1,
                content = checked,
                snapshot = snapshot,
                decidedAt = decidedAt,
                reason = reason,
                at = now,
            ).copy(ratifiesDecisionId = input.ratifiesDecisionId)
        write(scene, existing, entry)
        if (sendBack) stages.returnToDraft(meetingId, itemId)
        val recorded = checkNotNull(reads.current(itemId))
        effects.recorded(recorded, current?.entry, snapshot)
        release.afterChange(recorded, current?.entry, status)
        return checkNotNull(reads.current(itemId))
    }

    /** The meeting as the actor runs it and the item to decide: 404 unseen, 403 not a recorder, 403 `RECUSED`. */
    fun scene(
        meetingId: UUID,
        itemId: UUID,
    ): DecisionScene {
        val view = viewers.of(meetingId)
        if (!view.manages) throw ForbiddenException(Permission.FORUMS_MANAGE)
        val item = view.agenda.item(itemId)?.takeIf { view.lines.containsKey(it.id) } ?: throw MinutesNotFoundException("item")
        requireFree(view, item)
        return DecisionScene(view, item, outcomes(view))
    }

    /** The committee type's outcomes, from the version pinned on the meeting. */
    fun outcomes(view: MeetingView): List<Outcome> {
        val forum = directory.forum(view.meeting.forumId) ?: throw MinutesNotFoundException("forum")
        return policies.version(forum.typeId, view.meeting.typeVersion)?.outcomes.orEmpty()
    }

    /** A recorder recused from [item] is 403 `RECUSED` (ADR-0062); one not cleared for it does not see it (404). */
    fun requireFree(
        view: MeetingView,
        item: AgendaItemRef,
    ) {
        if (item.id in view.recused) {
            throw ForbiddenException(null, "RECUSED", "You declared an interest in this item: another officer records its decision.")
        }
        if (!view.seesItem(item.id)) throw MinutesNotFoundException("item")
    }

    /** When [itemId] was last started ("Now: item n", design E05 §1.6); null when it never was. */
    fun lastStarted(
        meetingId: UUID,
        itemId: UUID,
    ): Instant? =
        attendance
            .of(meetingId)
            ?.itemsInProgress
            ?.lastOrNull { it.itemId == itemId }
            ?.startedAt

    /**
     * The snapshot the entry keeps: the earlier one for a correction at the same time without a new check or an override,
     * else one taken now (E05: inside this transaction; 409 `NOT_QUORATE` or the override).
     */
    private fun snapshot(
        scene: DecisionScene,
        current: CurrentDecision?,
        input: DecisionInput,
        decidedAt: Instant,
    ): QuorumSnapshot {
        val keep =
            current != null && !input.recheckQuorum && input.overrideReason == null && decidedAt == current.entry.decidedAt
        val kept = if (keep) snapshots.find(checkNotNull(current).entry.snapshotId) else null
        return kept ?: snapshots.take(scene.meetingId, scene.item.id, decidedAt, input.overrideReason)
    }

    /** A deferral's meeting (the committee's, later, not cancelled) and a referral's committee (active, another one). */
    private fun targets(
        scene: DecisionScene,
        input: DecisionInput,
    ): List<FieldProblem> {
        val meeting = scene.view.meeting
        val deferred = input.deferTo?.meetingId?.takeIf { input.outcome == OutcomeKind.DEFERRED }
        val referred = input.referToForumId?.takeIf { input.outcome == OutcomeKind.REFERRED }
        val later = deferred?.let { meetings.find(it) }?.takeIf { it.forumId == meeting.forumId && it.startsAt.isAfter(meeting.startsAt) }
        val forum = referred?.let { directory.forum(it) }?.takeIf { it.status == ForumStatus.ACTIVE }
        return listOfNotNull(
            FieldProblem("deferTo/meetingId", "unknown", "Choose a later meeting of this committee.")
                .takeIf { deferred != null && (later == null || later.status == MeetingStatus.CANCELLED) },
            FieldProblem("referToForumId", "unknown", "No such active committee.").takeIf { referred != null && forum == null },
            FieldProblem("referToForumId", "SAME_FORUM", "Refer it to another committee.").takeIf { referred == meeting.forumId },
        )
    }

    /**
     * A decision ratifying one taken without a quorum (design E06 §1.1): quorate itself, APPROVED or NOTED, and naming a
     * decision of the same committee that is to be ratified and not ratified by another decision yet.
     */
    private fun ratifies(
        scene: DecisionScene,
        input: DecisionInput,
        snapshot: QuorumSnapshot,
        self: UUID?,
    ): List<FieldProblem> {
        val id = input.ratifiesDecisionId ?: return emptyList()
        val target = reads.currentById(id)?.takeIf { ratifiable(it, scene, self) }
        return listOfNotNull(
            FieldProblem("ratifiesDecisionId", "unknown", "No such decision to ratify.").takeIf { target == null },
            FieldProblem("ratifiesDecisionId", "RATIFY_NOT_QUORATE", "Only a quorate decision ratifies one.").takeIf { !snapshot.quorate },
            FieldProblem("ratifiesDecisionId", "OUTCOME", "A ratification approves or notes the decision.")
                .takeIf { input.outcome != OutcomeKind.APPROVED && input.outcome != OutcomeKind.NOTED },
        )
    }

    /** [target] is the committee's, on another item, to be ratified, and no decision but [self] ratifies it. */
    private fun ratifiable(
        target: CurrentDecision,
        scene: DecisionScene,
        self: UUID?,
    ): Boolean {
        val others = reads.ratifiers(listOf(target.decision.id))[target.decision.id].orEmpty() - setOfNotNull(self)
        val ours = target.decision.forumId == scene.view.meeting.forumId && target.decision.itemId != scene.item.id
        return ours && target.entry.ratificationRequired && others.isEmpty()
    }

    private fun write(
        scene: DecisionScene,
        existing: DecisionRow?,
        entry: EntryRow,
    ) {
        val written =
            if (existing == null) {
                val submission = scene.item.submissionId.takeIf { scene.item.kind == AgendaItemKind.SUBMISSION }
                val head =
                    DecisionRow(
                        id = entry.decisionId,
                        meetingId = scene.meetingId,
                        itemId = scene.item.id,
                        forumId = scene.view.meeting.forumId,
                        submissionId = submission,
                        memoId = scene.item.memoId.takeIf { submission != null },
                        currentSeq = entry.seq,
                        releasedAt = null,
                        releasedKind = null,
                        finalAt = null,
                        version = 0,
                    )
                store.insert(head, entry)
            } else {
                store.append(existing, existing.version, entry)
            }
        if (!written) throw ConcurrentModificationException("decision on item ${scene.item.id}", existing?.version ?: -1)
    }

    companion object {
        /** The entry of [content] taken with [snapshot]: "took no part" derived from it. */
        fun entry(
            decisionId: UUID,
            seq: Int,
            content: DecisionContent,
            snapshot: QuorumSnapshot,
            decidedAt: Instant,
            reason: String?,
            at: Instant,
            consentBatchId: UUID? = null,
        ) = EntryRow(
            decisionId = decisionId,
            seq = seq,
            outcomeKind = content.outcome.kind,
            outcomeLabel = content.outcome.label,
            resolution = content.outcome.resolution,
            text = content.text,
            conditions = content.conditions,
            validUntil = content.validUntil,
            deferTo = content.deferTo,
            referToForumId = content.referToForumId,
            referReason = content.referReason,
            voteMode = content.voteMode,
            votesFor = content.votesFor,
            votesAgainst = content.votesAgainst,
            votesAbstain = content.votesAbstain,
            dissent = content.dissent,
            abstentions = content.abstentions,
            tookNoPart = DecisionRules.tookNoPart(snapshot.inputs),
            snapshotId = snapshot.id,
            ratificationRequired = snapshot.ratificationRequired,
            ratifiesDecisionId = null,
            decidedAt = decidedAt,
            consentBatchId = consentBatchId,
            correction = seq > 1,
            reason = reason,
            recordedBy = checkNotNull(actorOrNull()) { "a decision is recorded by a signed-in user" },
            recordedAt = at,
        )
    }
}

/** The meeting's date in its zone. */
internal fun com.memo.meeting.MeetingRef.date() = startsAt.atZone(timeZone).toLocalDate()
