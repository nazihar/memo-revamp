package com.memo.actions.internal

import com.memo.common.authz.Clearances
import com.memo.common.authz.Permission
import com.memo.common.authz.Policy
import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import com.memo.forum.ForumDirectory
import com.memo.memo.DraftOrigin
import com.memo.memo.MemoDraftFields
import com.memo.memo.MemoDraftRejectedException
import com.memo.memo.MemoDrafts
import com.memo.memo.MemoReaders
import com.memo.memo.SystemNoteKind
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** A follow-up memo raised from an action (flow AP): the memo type, and the subject and level when not the action's. */
data class RaiseMemoInput(
    val typeId: UUID,
    /** Null or blank: the action's title. */
    val subject: String? = null,
    /** Null: the action's level. */
    val classificationId: UUID? = null,
    /** The person confirmed a level lower than the action's ("This memo will be Internal; the action is Confidential"). */
    val confirmLowerLevel: Boolean = false,
)

/**
 * Follow-up memos (ADR-0067 D1, design E07 §1.11): the owner (the holder, the person or the position's holder) or a
 * contributor holding `memo.create` raises a draft memo from an open action through the memo module's [MemoDrafts]; the
 * owner, a contributor or a secretary not recused links a memo they may read. At most ten per action. Each is recorded as
 * a link and an entry on the action's timeline, under the action's lock, and audited; the memo is an ordinary memo from
 * then on, under the memo's own rules. The action is never closed by its memos.
 */
@Service
class ActionFollowUps(
    private val store: ActionStore,
    private val lines: ActionLines,
    private val memoLinks: ActionMemoStore,
    private val viewers: ActionViewers,
    private val access: ActionAccess,
    private val drafts: MemoDrafts,
    private val readers: MemoReaders,
    private val clearances: Clearances,
    private val policy: Policy,
    private val directory: ForumDirectory,
    private val effects: ActionMemoEffects,
    private val clock: Clock,
) {
    /** Raises a draft memo from [actionId] for the actor, its initiator; returns the memo's id. */
    fun raise(
        actionId: UUID,
        input: RaiseMemoInput,
    ): UUID {
        val (viewer, row) = visible(actionId)
        val contributors = lines.contributorsOf(row.id)
        forbidUnless(owns(viewer, row) || viewer.userId in contributors, reason = NOT_INVOLVED, detail = NOT_INVOLVED_DETAIL)
        forbidUnless(policy.allows(Permission.MEMO_CREATE), Permission.MEMO_CREATE)
        refuseIf(row.status !in RAISE_STATES, ActionRules.WRONG_STATE) { "A memo is raised from an open action only." }
        requireRoom(row.id)
        rejectIf(lowerLevel(row, input))
        val committee = directory.forum(row.forumId)?.name.orEmpty()
        val subject = input.subject?.trim()?.takeIf { it.isNotEmpty() } ?: row.title
        val origin = DraftOrigin(SystemNoteKind.RAISED_FROM_ACTION, "Raised from action ${row.reference} ($committee)")
        val memoId =
            try {
                drafts.create(MemoDraftFields(subject, input.typeId, input.classificationId ?: row.classificationId), origin)
            } catch (e: MemoDraftRejectedException) {
                throw ActionRejectedException(e.problems).apply { initCause(e) }
            }
        val now = now()
        memoLinks.insert(row.id, memoId, MemoLinkKind.RAISED, viewer.userId, now)
        val seq = lines.entry(row.id, MEMO_RAISED, viewer.userId, mapOf("memoId" to memoId.toString()), now)
        effects.raised(row, memoId, input.typeId, seq)
        return memoId
    }

    /** Links the memo [memoId], which the actor may read, to [actionId] as a follow-up. */
    fun link(
        actionId: UUID,
        memoId: UUID,
    ): ActionRow {
        val (viewer, row) = visible(actionId)
        val contributors = lines.contributorsOf(row.id)
        val officer = viewer.manages(row.forumId) && !access.recused(viewer, row.id)
        forbidUnless(owns(viewer, row) || viewer.userId in contributors || officer, reason = NOT_INVOLVED, detail = NOT_INVOLVED_DETAIL)
        refuseIf(row.status !in LINK_STATES, ActionRules.WRONG_STATE) { "A memo is linked to an open action only." }
        val memo = readers.readable(listOf(memoId))[memoId]
        rejectIf(FieldProblem("memoId", "NOT_READABLE", "Choose a memo you can open.").takeIf { memo == null })
        rejectIf(FieldProblem("memoId", "DISCARDED", "This memo was discarded.").takeIf { memo?.status == DISCARDED })
        refuseIf(memoLinks.of(row.id).any { it.memoId == memoId }, ALREADY_LINKED) { "This memo already follows up the action." }
        requireRoom(row.id)
        val now = now()
        memoLinks.insert(row.id, memoId, MemoLinkKind.LINKED, viewer.userId, now)
        val seq = lines.entry(row.id, MEMO_LINKED, viewer.userId, mapOf("memoId" to memoId.toString()), now)
        effects.linked(row, memoId, seq)
        return row
    }

    /** The action, locked, if the actor may see it (404 otherwise), with the actor as the rule sees them. */
    private fun visible(actionId: UUID): Pair<ActionViewer, ActionRow> {
        val viewer = viewers.actor()
        val row = store.lock(actionId)
        notFoundUnless(row != null && access.sees(viewer, row.id, row.forumId), "action")
        return viewer to checkNotNull(row)
    }

    private fun requireRoom(actionId: UUID) {
        rejectIf(
            FieldProblem("memos", "TOO_MANY", "An action has at most $MAX_MEMOS follow-up memos.")
                .takeIf { memoLinks.of(actionId).size >= MAX_MEMOS },
        )
    }

    /** A level below the action's needs the person's confirmation (flow AP). */
    private fun lowerLevel(
        row: ActionRow,
        input: RaiseMemoInput,
    ): FieldProblem? {
        val wanted = input.classificationId?.let(clearances::level) ?: return null
        val action = clearances.level(row.classificationId)
        val lower = action != null && wanted.rank < action.rank
        return FieldProblem(
            "classificationId",
            "LOWER_THAN_ACTION",
            "This memo would be ${wanted.name}; the action is ${action?.name}. Confirm the lower level.",
        ).takeIf { lower && !input.confirmLowerLevel }
    }

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)

    companion object {
        const val MAX_MEMOS = 10
        const val MEMO_RAISED = "MEMO_RAISED"
        const val MEMO_LINKED = "MEMO_LINKED"
        const val MEMO_DECIDED = "MEMO_DECIDED"
        const val ALREADY_LINKED = "ALREADY_LINKED"
        const val NOT_INVOLVED = "NOT_INVOLVED"
        private const val NOT_INVOLVED_DETAIL = "Only the action's owner and contributors raise its memos; secretaries also link them."
        private const val DISCARDED = "DISCARDED"

        /** A memo is raised while the owner works on the action; linked until it is verified. */
        val RAISE_STATES = setOf(ActionStatus.OPEN, ActionStatus.IN_PROGRESS)
        val LINK_STATES = setOf(ActionStatus.OPEN, ActionStatus.IN_PROGRESS, ActionStatus.DONE_PENDING)

        /** The owner: the holder of the owner's task, the person owner, or the holder of the owner position. */
        fun owns(
            viewer: ActionViewer,
            row: ActionRow,
        ): Boolean =
            row.holderUserId == viewer.userId ||
                row.owner?.userId == viewer.userId ||
                row.owner?.positionId?.let { it in viewer.positions } == true
    }
}
