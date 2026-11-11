package com.memo.workflow.internal

import com.memo.workflow.ActionKind
import com.memo.workflow.MyTask
import com.memo.workflow.StepRef
import com.memo.workflow.TaskRole
import com.memo.workflow.engine.StepKind
import com.memo.workflow.engine.StepState
import com.memo.workflow.engine.TaskKind
import com.memo.workflow.engine.TaskState
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Open tasks as their holders see them (design E03 §12): the role, whether it waits for an answer, the step's actions and
 * where it can be sent back to. Reads without locking, inside a tenant transaction.
 */
@Component
class TaskDescriptions(
    private val instances: InstanceStore,
) {
    /** The open task [user] holds on the subject, decisions first (design E03 §12). */
    fun myTask(
        subjectType: String,
        subjectId: UUID,
        user: UUID,
    ): MyTask? {
        val loaded = instances.instanceOfSubject(subjectType, subjectId)?.let { instances.load(it, lock = false) }
        val task =
            loaded
                ?.state
                ?.openTasks
                ?.filter { it.assignee == user }
                ?.minByOrNull { it.kind.ordinal }
        return task?.let { describe(loaded, it.id) }
    }

    fun task(taskId: UUID): MyTask? {
        val loaded = instances.instanceOfTask(taskId)?.let { instances.load(it, lock = false) }
        return loaded?.let { describe(it, taskId) }
    }

    private fun describe(
        loaded: LoadedInstance,
        taskId: UUID,
    ): MyTask? {
        val (step, task) = loaded.state.findTask(taskId)?.takeIf { it.second.state == TaskState.OPEN } ?: return null
        val node = loaded.definition.stepsById[step.nodeId]
        val positive = if (step.kind == StepKind.REVIEW) ActionKind.RECOMMEND else ActionKind.APPROVE
        val decides = task.kind == TaskKind.DECIDE
        val targets =
            loaded.state.round.steps
                .filter { it.state == StepState.DONE && it.ordinal < step.ordinal }
                .map { StepRef(it.id, it.name, it.assignee) }
        return MyTask(
            id = task.id,
            role = TaskRole.valueOf(task.kind.name),
            blocked = task.blocked,
            waiting = task.waiting,
            delegated = task.onBehalfOf != null,
            stepName = step.name,
            positive = positive,
            actions = if (decides) node?.offeredActions.orEmpty().map { ActionKind.valueOf(it.name) } else emptyList(),
            sendBackTargets = if (decides) targets else emptyList(),
        )
    }
}
