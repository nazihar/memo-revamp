package com.memo.testsupport

import com.memo.workflow.ActionKind
import com.memo.workflow.BlockedReason
import com.memo.workflow.WorkflowActionTaken
import com.memo.workflow.WorkflowSubject
import com.memo.workflow.WorkflowTaskClosed
import com.memo.workflow.WorkflowTaskCreated
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A test-only workflow subject (design E01 §1), so the engine's service can be exercised before its first real subject
 * (the memo, T4). Tests mark people ineligible, change the snapshot, veto actions or pause inside one.
 */
@Component
class TestWorkflowSubject : WorkflowSubject {
    override val type = TYPE

    val ineligible = ConcurrentHashMap<UUID, BlockedReason>()
    val documentVersion = ConcurrentHashMap<UUID, Int>()
    val vetoed: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    /** Called inside every action before it is written; a test can block here to hold the instance lock. */
    @Volatile
    var onAction: (UUID) -> Unit = {}

    override fun ineligible(
        subjectId: UUID,
        users: Collection<UUID>,
    ): Map<UUID, BlockedReason> = ineligible.filterKeys { it in users }

    override fun snapshot(subjectId: UUID): Map<String, Any?> = mapOf("documentVersion" to (documentVersion[subjectId] ?: 1))

    override fun beforeAction(
        subjectId: UUID,
        kind: ActionKind,
    ) {
        onAction(subjectId)
        check(subjectId !in vetoed) { "vetoed by the test subject" }
    }

    companion object {
        const val TYPE = "test.subject"
    }
}

/** Records the workflow events published in committed and rolled-back transactions alike (assert per subject id). */
@Component
class WorkflowEventRecorder {
    val events = CopyOnWriteArrayList<Any>()

    @EventListener
    fun on(event: WorkflowActionTaken) {
        events += event
    }

    @EventListener
    fun on(event: WorkflowTaskCreated) {
        events += event
    }

    @EventListener
    fun on(event: WorkflowTaskClosed) {
        events += event
    }

    fun of(subjectId: UUID): List<Any> =
        events.filter {
            when (it) {
                is WorkflowActionTaken -> it.subjectId == subjectId
                is WorkflowTaskCreated -> it.subjectId == subjectId
                is WorkflowTaskClosed -> it.subjectId == subjectId
                else -> false
            }
        }
}
