package com.memo.minutes

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.forum.MinutesApproval
import com.memo.minutes.internal.CommentKind
import com.memo.minutes.internal.DraftTask
import com.memo.minutes.internal.MinutesCommentStore
import com.memo.minutes.internal.MinutesComments
import com.memo.minutes.internal.MinutesConfirmation
import com.memo.minutes.internal.MinutesDrafting
import com.memo.minutes.internal.MinutesDrafts
import com.memo.minutes.internal.MinutesReview
import com.memo.minutes.internal.MinutesStep
import com.memo.minutes.internal.MinutesStore
import com.memo.minutes.internal.MinutesStructureChecks
import com.memo.minutes.internal.ReviewDecision
import com.memo.minutes.internal.WorkState
import org.springframework.stereotype.Service
import java.util.UUID

/** Where a meeting's minutes stand, for seeders: their stage's name ("DRAFT" … "APPROVED") and how they are approved. */
data class SeedMinutes(
    val stage: String,
    val approval: MinutesApproval,
    val drafted: Boolean,
    /** The person reviewing the draft, once it was sent to them. */
    val reviewerId: UUID?,
)

/**
 * The minutes' path for seeders (the demo tenant, Phase 4 E06 T12), through the same services as the API, as the user
 * bound to the current unit of work. Never called from requests. Each call is its own tenant transaction, like the API's
 * (the checks the controllers run before a transaction run here too), except that no step-up is asked for: a seeder acts
 * outside a session, so the approvals it makes record no factors. The draft is made in the caller's thread (as the
 * `minutes-draft` job would), because the next steps need it; the copies and the record are left to their jobs. Decisions
 * and extracts are [DecisionSeeding]'s. The seeder decides what already exists ("seeders only fill gaps").
 */
@Service
class MinutesSeeding(
    private val drafts: MinutesDrafts,
    private val drafting: MinutesDrafting,
    private val checks: MinutesStructureChecks,
    private val store: MinutesStore,
    private val review: MinutesReview,
    private val comments: MinutesComments,
    private val commentStore: MinutesCommentStore,
    private val confirmation: MinutesConfirmation,
    private val transactions: TenantTransactions,
) {
    /** Where [meetingId]'s minutes stand; null before they exist (a meeting held before E06 gets them at its draft). */
    fun minutes(meetingId: UUID): SeedMinutes? =
        tx { store.find(meetingId)?.let { SeedMinutes(it.state.name, it.policy.approval, it.fileId != null, it.reviewerId) } }

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

    /** The bound reader has no comments, unless they already answered or commented; whether it was recorded now. */
    fun noComments(meetingId: UUID): Boolean = answer(meetingId, CommentKind.NO_COMMENTS, null, null)

    /**
     * The bound reader suggests [text] on [itemId] (in general when null), unless they already answered or commented;
     * whether it was recorded now.
     */
    fun comment(
        meetingId: UUID,
        itemId: UUID?,
        text: String,
    ): Boolean = answer(meetingId, CommentKind.SUGGESTION, itemId, text)

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

    private fun answer(
        meetingId: UUID,
        kind: CommentKind,
        itemId: UUID?,
        text: String?,
    ): Boolean =
        tx {
            val user = (ActorContext.current().actor as? Actor.User)?.userId
            val first = user == null || commentStore.of(meetingId, user).isEmpty()
            if (first) comments.comment(meetingId, kind, itemId, text)
            first
        }

    private fun version(meetingId: UUID): Long = checkNotNull(store.find(meetingId)) { "minutes of $meetingId" }.version

    private fun <T> tx(block: () -> T): T = transactions.inTenant(TenantContext.current(), block)
}
