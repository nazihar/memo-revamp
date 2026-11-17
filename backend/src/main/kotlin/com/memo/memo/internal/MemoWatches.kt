package com.memo.memo.internal

import com.memo.audit.AuditLog
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.web.Cursors
import com.memo.memo.MemoNotePosted
import com.memo.workflow.ActionKind
import com.memo.workflow.WorkflowActionTaken
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/** Whether the signed-in person follows a memo, and why. */
data class WatchStatus(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val watching: Boolean,
    val reason: WatchReason?,
)

/** A followed memo on the Watching tab (flow J). */
data class WatchingItem(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val memoId: UUID,
    val reference: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val subject: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val reason: WatchReason,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val since: Instant,
)

data class WatchingPage(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val items: List<WatchingItem>,
    val nextCursor: String?,
)

/**
 * Following memos (design E05 §4, flow J): readers follow and unfollow by hand (audited), the Watching list shows what
 * the person may still read, and their settings say whether they follow automatically and how they get email. Inside a
 * tenant transaction.
 */
@Service
class MemoWatches(
    private val memos: Memos,
    private val store: MemoWatchStore,
    private val readable: MemoReadable,
    private val lists: MemoListStore,
    private val audit: AuditLog,
) {
    fun status(memoId: UUID): WatchStatus {
        memos.get(memoId)
        val reason = store.reason(memoId, currentUser())
        return WatchStatus(reason != null, reason)
    }

    fun watch(memoId: UUID): WatchStatus {
        memos.get(memoId)
        if (store.add(memoId, currentUser(), WatchReason.MANUAL)) {
            audit.record("memo.watch.added", Memos.OBJECT_TYPE, memoId.toString())
        }
        return status(memoId)
    }

    fun unwatch(memoId: UUID) {
        memos.get(memoId)
        if (store.remove(memoId, currentUser())) audit.record("memo.watch.removed", Memos.OBJECT_TYPE, memoId.toString())
    }

    fun watching(
        cursor: String?,
        limit: Int,
    ): WatchingPage {
        val user = currentUser()
        val size = limit.coerceIn(1, MAX_PAGE)
        val rows = store.watching(user, readable.condition(user), cursor?.let(Cursors::decode), size + 1)
        val shown = rows.take(size)
        val summaries = lists.summaries(shown.map { it.memoId })
        val items =
            shown.mapNotNull { row ->
                summaries[row.memoId]?.let { WatchingItem(row.memoId, it.reference, it.title, row.reason, row.createdAt) }
            }
        return WatchingPage(items, shown.lastOrNull()?.takeIf { rows.size > size }?.let { Cursors.encode(it.createdAt, it.memoId) })
    }

    fun settings(): WatchSettings = store.settings(currentUser())

    fun saveSettings(settings: WatchSettings): WatchSettings {
        val user = currentUser()
        store.saveSettings(user, settings)
        audit.record(
            "memo.watch.settings_changed",
            "user",
            user.toString(),
            after = mapOf("autoWatch" to settings.autoWatch, "email" to settings.email.name),
        )
        return settings
    }

    private fun currentUser(): UUID =
        checkNotNull((ActorContext.current().actor as? Actor.User)?.userId) { "following needs a signed-in user" }

    private companion object {
        const val MAX_PAGE = 100
    }
}

/**
 * What followers hear about (design E05 §4): a step decided, a note posted, the memo closed. The person who acted
 * follows the memo from then on (their setting allows) and is never told of their own action. Runs in the change's
 * transaction.
 */
@Component
class MemoWatchNews(
    private val store: MemoWatchStore,
    private val events: MemoWatchEventStore,
    private val notes: MemoNoteStore,
) {
    @EventListener
    fun onAction(event: WorkflowActionTaken) {
        if (event.subjectType != MemoWorkflowSubject.TYPE || event.kind !in DECISIONS) return
        val actor = actor()
        actor?.let { store.auto(event.subjectId, it, WatchReason.ACTED) }
        happened(event.subjectId, WatchEventKind.STEP_DECIDED, actor)
    }

    @EventListener
    fun onNote(event: MemoNotePosted) {
        val author = notes.of(event.memoId).firstOrNull { it.id == event.noteId }?.authorId
        happened(event.memoId, WatchEventKind.NOTE_ADDED, author)
    }

    /** [kind] happened to [memoId] by [actor] (null: the system): every follower but the actor hears of it. */
    fun happened(
        memoId: UUID,
        kind: WatchEventKind,
        actor: UUID? = actor(),
    ) {
        events.events(memoId, store.watchers(memoId).filter { it != actor }, kind)
    }

    private fun actor(): UUID? = (ActorContext.currentOrNull()?.actor as? Actor.User)?.userId

    private companion object {
        val DECISIONS =
            setOf(
                ActionKind.APPROVE,
                ActionKind.RETURN,
                ActionKind.REJECT,
                ActionKind.RECOMMEND,
                ActionKind.SEND_BACK,
                ActionKind.FORWARD,
                ActionKind.RESUBMIT,
                ActionKind.WITHDRAW,
                ActionKind.RECALL,
                ActionKind.CANCEL,
            )
    }
}
