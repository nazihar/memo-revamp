package com.memo.minutes

import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.forum.OutcomeKind
import com.memo.meeting.MeetingItems
import com.memo.minutes.internal.ConsentDecisions
import com.memo.minutes.internal.DecisionInput
import com.memo.minutes.internal.DecisionRecorder
import com.memo.minutes.internal.DecisionStore
import com.memo.minutes.internal.DeferTarget
import com.memo.minutes.internal.Dissent
import com.memo.minutes.internal.ItemNotes
import com.memo.minutes.internal.ResolutionExtracts
import com.memo.minutes.internal.VoteMode
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/** A vote a seeder records with a decision: for, against, abstaining. */
data class SeedVote(
    val votesFor: Int,
    val votesAgainst: Int,
    val votesAbstain: Int = 0,
)

/**
 * A decision a seeder records (the demo tenant, Phase 4 E06 T12): the outcome at [decidedAt] (required once the meeting
 * is held), the resolution's [text] and [conditions], an optional vote, the members who asked that their dissent be
 * recorded with their reasons, "to the next meeting" for a deferral, and E05's override reason when the item is not
 * quorate.
 */
data class SeedDecision(
    val outcome: OutcomeKind,
    val decidedAt: Instant,
    val text: String? = null,
    val conditions: List<String> = emptyList(),
    val vote: SeedVote? = null,
    val dissent: Map<UUID, String?> = emptyMap(),
    val deferToNextMeeting: Boolean = false,
    val overrideReason: String? = null,
)

/**
 * Decisions, notes, resolutions and certified extracts for seeders (the demo tenant, Phase 4 E06 T12), through the same
 * services as the API, as the user bound to the current unit of work. Never called from requests. Each call is its own
 * tenant transaction. No step-up is asked for: a seeder acts outside a session, so the extracts it certifies record no
 * factors, and their files are left to the `minutes-extract` job. The minutes themselves are [MinutesSeeding]'s. The
 * seeder decides what already exists ("seeders only fill gaps").
 */
@Service
class DecisionSeeding(
    private val recorder: DecisionRecorder,
    private val consent: ConsentDecisions,
    private val decisions: DecisionStore,
    private val published: Decisions,
    private val notes: ItemNotes,
    private val extracts: ResolutionExtracts,
    private val resolutions: Resolutions,
    private val items: MeetingItems,
    private val transactions: TenantTransactions,
) {
    /** Whether [itemId] has a decision. */
    fun decided(itemId: UUID): Boolean = tx { decisions.decided(itemId) }

    /** Records [decision] on [itemId] of [meetingId]. */
    fun decide(
        meetingId: UUID,
        itemId: UUID,
        decision: SeedDecision,
    ) {
        tx {
            val vote = decision.vote
            recorder.record(
                meetingId,
                itemId,
                DecisionInput(
                    outcome = decision.outcome,
                    text = decision.text,
                    conditions = decision.conditions,
                    deferTo = DeferTarget(next = true).takeIf { decision.deferToNextMeeting },
                    voteMode = if (vote == null) VoteMode.CONSENSUS else VoteMode.VOTE,
                    votesFor = vote?.votesFor,
                    votesAgainst = vote?.votesAgainst,
                    votesAbstain = vote?.votesAbstain,
                    dissent = decision.dissent.map { (user, reason) -> Dissent(user, reason) },
                    decidedAt = decision.decidedAt,
                    overrideReason = decision.overrideReason,
                ),
                null,
            )
        }
    }

    /**
     * Decides the consent section of [meetingId]'s agenda at [decidedAt]; how many items it decided (0 when the agenda has
     * none).
     */
    fun decideConsent(
        meetingId: UUID,
        decidedAt: Instant,
    ): Int =
        tx {
            val section =
                items
                    .agenda(meetingId)
                    .items
                    .firstOrNull { it.consent }
                    ?.sectionId
            section?.let { consent.decide(meetingId, it, decidedAt, false).size } ?: 0
        }

    /** The secretary's notes on [itemId], the first version. */
    fun notes(
        meetingId: UUID,
        itemId: UUID,
        text: String,
    ) {
        tx { notes.save(meetingId, itemId, 0, text) }
    }

    /** [itemId]'s resolution number in force and its id; null when it has none. */
    fun resolution(itemId: UUID): Pair<String, UUID>? =
        tx { published.ofItem(itemId)?.let { resolutions.inForce(it.id) }?.let { it.number to it.id } }

    /** Whether [resolutionId] has an extract. */
    fun extracted(resolutionId: UUID): Boolean = tx { extracts.list(resolutionId).isNotEmpty() }

    /** The bound secretary issues a certified extract of [resolutionId] (no step-up: see the class). */
    fun issueExtract(resolutionId: UUID) {
        tx { extracts.issue(resolutionId) }
    }

    private fun <T> tx(block: () -> T): T = transactions.inTenant(TenantContext.current(), block)
}
