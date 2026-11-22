package com.memo.actions.internal

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.forum.ForumDirectory
import com.memo.memo.MemoOriginKind
import com.memo.memo.MemoOriginLabel
import com.memo.memo.MemoOrigins
import com.memo.memo.MemoReaders
import com.memo.workflow.WorkflowInstanceEnded
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * A follow-up memo decided (design E07 §1.11), in the engine's transaction: its memo workflow ended approved, rejected,
 * withdrawn or cancelled. Each action it follows up gets a MEMO_DECIDED entry (the memo and its outcome), audited, and
 * the holder of an open action is told: "The follow-up memo was approved. You can now mark the action as done." The
 * action is never closed by it: the owner marks it done and someone else verifies it.
 */
@Component
class ActionMemoListener(
    private val store: ActionStore,
    private val lines: ActionLines,
    private val memoLinks: ActionMemoStore,
    private val effects: ActionMemoEffects,
    private val clock: Clock,
) {
    @EventListener
    fun onMemoEnded(event: WorkflowInstanceEnded) {
        if (event.subjectType != MemoReaders.SUBJECT_TYPE) return
        val actions =
            memoLinks
                .ofMemo(event.subjectId)
                .map { it.actionId }
                .distinct()
                .sorted()
        if (actions.isEmpty()) return
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val outcome = event.status.name
        // In id order, so two memos decided at once lock their actions in the same order.
        actions.forEach { actionId ->
            val row = store.lock(actionId) ?: return@forEach
            val details = mapOf("memoId" to event.subjectId.toString(), "outcome" to outcome)
            val seq = lines.entry(row.id, ActionFollowUps.MEMO_DECIDED, signedIn(), details, now)
            effects.decided(row, event.subjectId, outcome, seq)
        }
    }
}

/**
 * The memo module's [MemoOrigins] port (design E07 §1.11): the actions a memo follows up, for the memo view. A memo raised
 * from an action shows "Raised from action BRC A14/2026" to every reader of the memo (its note sheet says it too), with
 * the action's id only for those who see the action (ActionAccess's rule); a memo linked to an action shows it only to
 * those who see the action, so linking never tells a memo's readers of an action they may not know of.
 */
@Component
class ActionMemoOrigins(
    private val store: ActionStore,
    private val memoLinks: ActionMemoStore,
    private val viewers: ActionViewers,
    private val access: ActionAccess,
    private val directory: ForumDirectory,
) : MemoOrigins {
    override fun labels(memoId: UUID): List<MemoOriginLabel> {
        val links = memoLinks.ofMemo(memoId)
        if (links.isEmpty()) return emptyList()
        val viewer = signedIn()?.let { viewers.actor() }
        return links.mapNotNull { link ->
            val row = store.find(link.actionId)
            val reference = row?.reference ?: return@mapNotNull null
            val sees = viewer != null && access.sees(viewer, row.id, row.forumId)
            MemoOriginLabel(
                kind = if (link.kind == MemoLinkKind.RAISED) MemoOriginKind.RAISED_FROM else MemoOriginKind.LINKED_TO,
                recordType = RECORD_TYPE,
                reference = reference,
                owner = directory.forum(row.forumId)?.name.orEmpty(),
                recordId = row.id.takeIf { sees },
            ).takeIf { sees || link.kind == MemoLinkKind.RAISED }
        }
    }

    private companion object {
        const val RECORD_TYPE = "ACTION"
    }
}

/** The signed-in user, or null for the system or no context. */
private fun signedIn(): UUID? = (ActorContext.currentOrNull()?.actor as? Actor.User)?.userId
