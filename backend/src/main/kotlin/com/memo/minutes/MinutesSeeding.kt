package com.memo.minutes

import com.memo.common.context.ActorContext
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.forum.MinutesApproval
import com.memo.forum.OutcomeKind
import com.memo.meeting.MeetingItems
import com.memo.minutes.internal.CommentKind
import com.memo.minutes.internal.ConsentDecisions
import com.memo.minutes.internal.DecisionInput
import com.memo.minutes.internal.DecisionReads
import com.memo.minutes.internal.DecisionRecorder
import com.memo.minutes.internal.DecisionStore
import com.memo.minutes.internal.DeferTarget
import com.memo.minutes.internal.Dissent
import com.memo.minutes.internal.DraftTask
import com.memo.minutes.internal.ItemNotes
import com.memo.minutes.internal.MinutesCommentStore
import com.memo.minutes.internal.MinutesComments
import com.memo.minutes.internal.MinutesConfirmation
import com.memo.minutes.internal.MinutesDrafting
import com.memo.minutes.internal.MinutesDrafts
import com.memo.minutes.internal.MinutesReview
import com.memo.minutes.internal.MinutesStep
import com.memo.minutes.internal.MinutesStore
import com.memo.minutes.internal.MinutesStructureChecks
import com.memo.minutes.internal.ResolutionExtracts
import com.memo.minutes.internal.ReviewDecision
import com.memo.minutes.internal.VoteMode
import com.memo.minutes.internal.WorkState
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

/** Where a meeting's minutes stand, for seeders: their stage's name ("DRAFT" … "APPROVED") and how they are approved. */
data class SeedMinutes(
    val stage: String,
    val approval: MinutesApproval,
    val drafted: Boolean,
)

/**
 * Decisions, minutes and extracts for seeders (the demo tenant, Phase 4 E06 T12), through the same services as the API, as
 * the user bound to the current unit of work. Never called from requests. Each call is its own tenant transaction, like
 * the API's (the checks the controllers run before a transaction run here too), except that no step-up is asked for: a
 * seeder acts outside a session, so the approvals and certifications it makes record no factors. The draft is made in the
 * caller's thread (as the `minutes-draft` job would), because the next steps need it; the copies, the record and the
 * extract's file are left to their jobs. The seeder decides what already exists ("seeders only fill gaps").
 */
@Service
class MinutesSeeding(
    private val recorder: DecisionRecorder,
    private val consent: ConsentDecisions,
    private val decisions: DecisionStore,
    private val reads: DecisionReads,
    private val notes: ItemNotes,
    private val drafts: MinutesDrafts,
    private val drafting: MinutesDrafting,
    private val checks: MinutesStructureChecks,
    private val store: MinutesStore,
    private val review: MinutesReview,
    private val comments: MinutesComments,
    private val commentStore: MinutesCommentStore,
    private val confirmation: MinutesConfirmation,
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

    /** Decides the consent section [sectionId] of [meetingId] at [decidedAt]; how many items it decided. */
    fun decideConsent(
        meetingId: UUID,
        sectionId: UUID,
        decidedAt: Instant,
    ): Int = tx { consent.decide(meetingId, sectionId, decidedAt, false).size }

    /** The consent section of [meetingId]'s agenda, if it has one. */
    fun consentSection(meetingId: UUID): UUID? =
        tx {
            items
                .agenda(meetingId)
                .items
                .firstOrNull { it.consent }
                ?.sectionId
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
        tx { reads.current(itemId)?.let { resolutions.inForce(it.decision.id) }?.let { it.number to it.id } }

    /** Where [meetingId]'s minutes stand; null before they exist (a meeting held before E06 gets them at its draft). */
    fun minutes(meetingId: UUID): SeedMinutes? =
        tx { store.find(meetingId)?.let { SeedMinutes(it.state.name, it.policy.approval, it.fileId != null) } }

    /**
     * Makes the whole draft from the committee's template, as the `minutes-draft` job would, and checks its structure;
     * whether it is there and sound.
     */
    fun draft(meetingId: UUID): Boolean {
        val row = tx { drafts.request(meetingId, null, false) }
        // The job's own run (as the system, storing the file as the requester); the scheduled one then finds nothing to do.
        ActorContext.asSystem("minutes-seeding") {
            drafting.run(DraftTask(TenantContext.current().value, meetingId, row.workSeq))
        }
        val made = tx { store.find(meetingId) }
        return made?.fileId != null && made.workState != WorkState.FAILED && checks.check(meetingId)?.ok == true
    }

    /** The person reviewing [meetingId]'s draft, once it was sent to them. */
    fun reviewer(meetingId: UUID): UUID? = tx { store.find(meetingId)?.reviewerId }

    /** Sends the draft to the chair (or vice-chair) for review. */
    fun sendForReview(meetingId: UUID) {
        checks.requireReady(meetingId, MinutesStep.REVIEW)
        tx { review.sendForReview(meetingId, version(meetingId)) }
    }

    /** The bound reviewer agrees the draft: it is circulated (nothing is left to accept). */
    fun agree(meetingId: UUID) {
        val ready = checks.blocker(meetingId, MinutesStep.CIRCULATE) == null
        tx { review.review(meetingId, version(meetingId), ReviewDecision.AGREE, null, null, ready) }
    }

    /** Whether [userId] answered or commented on [meetingId]'s minutes. */
    fun answered(
        meetingId: UUID,
        userId: UUID,
    ): Boolean = tx { commentStore.of(meetingId, userId).isNotEmpty() }

    /** The bound reader has no comments. */
    fun noComments(meetingId: UUID) {
        tx { comments.comment(meetingId, CommentKind.NO_COMMENTS, null, null) }
    }

    /** The bound reader suggests [text] on [itemId] (in general when null). */
    fun comment(
        meetingId: UUID,
        itemId: UUID?,
        text: String,
    ) {
        tx { comments.comment(meetingId, CommentKind.SUGGESTION, itemId, text) }
    }

    /** By circulation: the bound secretary ends the comment period early with [reason]; the minutes go to the chair. */
    fun endCirculation(
        meetingId: UUID,
        reason: String,
    ) {
        tx { review.endCirculation(meetingId, version(meetingId), reason) }
    }

    /** The bound confirmer approves the minutes (no step-up: see the class); the record is asked for. */
    fun confirm(meetingId: UUID) {
        tx { confirmation.requireConfirmer(meetingId) }
        checks.requireReady(meetingId, MinutesStep.CIRCULATE)
        tx { confirmation.confirm(meetingId, version(meetingId)) }
    }

    /** Whether [resolutionId] has an extract. */
    fun extracted(resolutionId: UUID): Boolean = tx { extracts.list(resolutionId).isNotEmpty() }

    /** The bound secretary issues a certified extract of [resolutionId] (no step-up: see the class); its file is the job's. */
    fun issueExtract(resolutionId: UUID) {
        tx { extracts.issue(resolutionId) }
    }

    private fun version(meetingId: UUID): Long = checkNotNull(store.find(meetingId)) { "minutes of $meetingId" }.version

    private fun <T> tx(block: () -> T): T = transactions.inTenant(TenantContext.current(), block)
}
