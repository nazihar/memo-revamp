package com.memo.minutes.internal

import com.memo.common.authz.ForbiddenException
import com.memo.common.authz.Permission
import com.memo.common.authz.Policy
import com.memo.forum.ForumDirectory
import com.memo.forum.ForumRole
import com.memo.identity.UserSummaries
import com.memo.meeting.LineAccess
import com.memo.meeting.MeetingAttendance
import com.memo.meeting.MeetingView
import com.memo.meeting.MeetingViewers
import com.memo.meeting.QuorumSnapshots
import com.memo.minutes.DecisionState
import org.springframework.stereotype.Component
import java.util.UUID

/** How much of an item's decision a viewer sees (design E06 §1.9). */
enum class DecisionSight {
    /** Everything: they see the item in full, or read an unrestricted item as `forum.read`. */
    FULL,

    /** Recused from the item, seeing its line: the outcome, the text, the conditions and the validity. */
    RECUSED_LINE,

    /** Nothing. */
    NONE,
}

/**
 * Who reads what of the decisions and notes (design E06 §1.9, ADR-0062): the only reader of the minutes tables for the
 * people the decisions are shown to (`MinutesAccessArchTest`). A person who sees an item in full sees its decision with
 * the vote and the names; one recused from the item who sees its line gets the recused view (the heading, their own
 * declaration elsewhere, and the decision without the vote, the names or the notes); anyone else nothing.
 */
@Component
class MinutesAccess(
    private val viewers: MeetingViewers,
    private val reads: DecisionReads,
    private val resolutions: ResolutionStore,
    private val attendance: MeetingAttendance,
    private val snapshots: QuorumSnapshots,
    private val users: UserSummaries,
    private val directory: ForumDirectory,
    private val policy: Policy,
) {
    /** [meetingId]'s decisions as the actor may see them (404 for a meeting they do not see). */
    fun decisions(meetingId: UUID): MeetingDecisionsResponse {
        val view = viewers.of(meetingId)
        val shown =
            reads.currentOf(meetingId).mapNotNull { d ->
                sight(view, d.decision.itemId).takeIf { it != DecisionSight.NONE }?.let {
                    d to
                        it
                }
            }
        val ratified = reads.ratifiers(shown.filter { it.first.entry.ratificationRequired }.map { it.first.decision.id }).keys
        val corrected = if (view.manages) attendance.of(meetingId)?.lastCorrectedAt else null
        val names = users.names(shown.filter { it.second == DecisionSight.FULL }.flatMap { (d, _) -> named(d.entry, view.officer) }.toSet())
        val numbers = resolutions.inForce(shown.map { it.first.decision.id })
        val decisions =
            shown.map { (d, sight) ->
                val drifted = corrected?.takeIf { it.isAfter(d.entry.recordedAt) }?.let { snapshots.drifted(d.entry.snapshotId)?.drifted }
                response(view, d, sight, d.decision.id in ratified, names, drifted, numbers[d.decision.id])
            }
        return MeetingDecisionsResponse(meetingId, decisions)
    }

    /** The current decision on [itemId] as the actor may see it; 404 when they may not or there is none. */
    fun decision(
        meetingId: UUID,
        itemId: UUID,
    ): DecisionResponse = decisions(meetingId).decisions.firstOrNull { it.itemId == itemId } ?: throw MinutesNotFoundException("decision")

    /**
     * The history of [itemId]'s decision: the committee's officers and `forum.read` who see the decision in full (a
     * recused person does not: 404); the audience 403.
     */
    fun history(
        meetingId: UUID,
        itemId: UUID,
    ): DecisionHistoryResponse {
        val view = viewers.of(meetingId)
        val current =
            reads.current(itemId)?.takeIf { sight(view, itemId) == DecisionSight.FULL } ?: throw MinutesNotFoundException("decision")
        if (!view.officer && !view.reader) throw ForbiddenException(Permission.FORUM_READ)
        val entries = reads.entries(current.decision.id)
        val names = users.names(entries.flatMap { named(it, officer = true) }.toSet())
        return DecisionHistoryResponse(current.decision.id, itemId, entries.map { entry(it, names) })
    }

    /** The notes as [ItemNotes] read them, with the saver's name. */
    fun note(
        itemId: UUID,
        row: NoteRow?,
    ): ItemNoteResponse {
        val name = row?.let { users.names(setOf(it.savedBy)) }.orEmpty()
        return ItemNoteResponse(
            itemId,
            row?.seq ?: 0,
            row?.text.orEmpty(),
            row?.let {
                DecisionPerson(it.savedBy, name[it.savedBy].orEmpty())
            },
            row?.savedAt,
        )
    }

    /**
     * [forumId]'s decisions taken without a quorum and not ratified yet, for its secretaries and `forums.manage`: ids,
     * numbers and outcomes of the meetings they see, never an item's title or text.
     */
    fun toBeRatified(forumId: UUID): List<ToBeRatifiedResponse> {
        val forum = directory.forum(forumId) ?: throw MinutesNotFoundException("forum")
        requireSecretary(forum.id)
        val open = reads.toBeRatified(forum.id)
        val views =
            open
                .map { it.decision.meetingId }
                .distinct()
                .mapNotNull { viewers.find(it) }
                .associateBy { it.meeting.id }
        return open.mapNotNull { d ->
            views[d.decision.meetingId]?.let { view ->
                ToBeRatifiedResponse(
                    d.decision.id,
                    view.meeting.id,
                    view.meeting.label,
                    d.decision.itemId,
                    view.agenda.item(d.decision.itemId)?.number,
                    d.entry.outcomeLabel,
                    d.entry.decidedAt,
                )
            }
        }
    }

    /** A secretary of [forumId] or `forums.manage`: 403 for someone else who sees the committee, 404 for anyone else. */
    private fun requireSecretary(forumId: UUID) {
        val role = actorOrNull()?.let { directory.rolesOf(it)[forumId] }
        if (policy.allows(Permission.FORUMS_MANAGE) || role == ForumRole.SECRETARY) return
        if (role == null && !policy.allows(Permission.FORUM_READ)) throw MinutesNotFoundException("forum")
        throw ForbiddenException(Permission.FORUMS_MANAGE)
    }

    /** How much of [itemId]'s decision [view]'s viewer sees (design E06 §1.9). */
    fun sight(
        view: MeetingView,
        itemId: UUID,
    ): DecisionSight {
        val item = view.agenda.item(itemId)
        val line = view.lines[itemId]
        // Recused, but one the item is for: unrestricted, or restricted to them (an alternate is not named on the member's items).
        val named = item != null && item.restrictedTo?.contains(view.userId) != false
        val recused = itemId in view.recused && named
        val unrestricted = item != null && !item.restricted && !item.titleHidden
        val titled = line == LineAccess.TITLE && itemId !in view.recused
        val reads = view.reader && titled && unrestricted
        return when {
            view.seesItem(itemId) -> DecisionSight.FULL
            recused && line == LineAccess.TITLE -> DecisionSight.RECUSED_LINE
            reads -> DecisionSight.FULL
            else -> DecisionSight.NONE
        }
    }

    private fun response(
        view: MeetingView,
        d: CurrentDecision,
        sight: DecisionSight,
        ratified: Boolean,
        names: Map<UUID, String>,
        drifted: Boolean?,
        number: ResolutionRow?,
    ): DecisionResponse {
        val e = d.entry
        val reduced =
            DecisionResponse(
                id = d.decision.id,
                itemId = d.decision.itemId,
                itemNumber = view.agenda.item(d.decision.itemId)?.number,
                version = d.decision.version,
                seq = e.seq,
                state = DecisionState.of(d.decision.releasedAt, d.decision.finalAt, number != null),
                outcome = e.outcomeKind,
                outcomeLabel = e.outcomeLabel,
                resolution = e.resolution,
                text = e.text,
                conditions = e.conditions,
                validUntil = e.validUntil,
                decidedAt = e.decidedAt,
                ratificationRequired = e.ratificationRequired,
                toBeRatified = e.ratificationRequired && !ratified,
                viewerRecused = d.decision.itemId in view.recused,
                resolutionNumber = number?.number,
                resolutionId = number?.id,
            )
        if (sight != DecisionSight.FULL) return reduced
        val person = { id: UUID -> DecisionPerson(id, names[id].orEmpty()) }
        return reduced.copy(
            deferTo = e.deferTo,
            referToForumId = e.referToForumId,
            referReason = e.referReason,
            vote = DecisionVoteResponse(e.voteMode, e.votesFor, e.votesAgainst, e.votesAbstain),
            dissent = e.dissent.map { DecisionDissentResponse(person(it.userId), it.reason) },
            abstentions = e.abstentions.map(person),
            tookNoPart = e.tookNoPart.map(person),
            ratifiesDecisionId = e.ratifiesDecisionId,
            consentBatchId = e.consentBatchId,
            snapshotId = e.snapshotId,
            recordedBy = e.recordedBy.takeIf { view.officer }?.let(person),
            recordedAt = e.recordedAt.takeIf { view.officer },
            drifted = drifted.takeIf { view.manages },
        )
    }

    private fun entry(
        e: EntryRow,
        names: Map<UUID, String>,
    ): DecisionEntryResponse {
        val person = { id: UUID -> DecisionPerson(id, names[id].orEmpty()) }
        return DecisionEntryResponse(
            seq = e.seq,
            outcome = e.outcomeKind,
            outcomeLabel = e.outcomeLabel,
            resolution = e.resolution,
            text = e.text,
            conditions = e.conditions,
            validUntil = e.validUntil,
            deferTo = e.deferTo,
            referToForumId = e.referToForumId,
            referReason = e.referReason,
            vote = DecisionVoteResponse(e.voteMode, e.votesFor, e.votesAgainst, e.votesAbstain),
            dissent = e.dissent.map { DecisionDissentResponse(person(it.userId), it.reason) },
            abstentions = e.abstentions.map(person),
            tookNoPart = e.tookNoPart.map(person),
            snapshotId = e.snapshotId,
            ratificationRequired = e.ratificationRequired,
            ratifiesDecisionId = e.ratifiesDecisionId,
            decidedAt = e.decidedAt,
            consentBatchId = e.consentBatchId,
            correction = e.correction,
            reason = e.reason,
            recordedBy = person(e.recordedBy),
            recordedAt = e.recordedAt,
        )
    }
}

/** The people an entry names (and its recorder, for officers). */
private fun named(
    e: EntryRow,
    officer: Boolean,
): List<UUID> = e.dissent.map { it.userId } + e.abstentions + e.tookNoPart + listOfNotNull(e.recordedBy.takeIf { officer })
