package com.memo.workflow.engine

import com.memo.workflow.ActionKind
import com.memo.workflow.definition.ParallelMode
import com.memo.workflow.definition.StepDef

/** Where a parallel step stands after a decision (design E03 §4.3). */
internal enum class Verdict { WAITING, APPROVED, REJECTED }

/**
 * The completion rule of parallel steps (design E03 §4.3): a step needs `all`, one (`any`) or `n` approvals; it is
 * approved as soon as it has them, and rejected as soon as it can no longer get them. Tasks moved to someone else
 * (reassigned, forwarded) do not count; their replacements do.
 */
internal object Parallel {
    fun verdict(
        step: Step,
        node: StepDef,
    ): Verdict {
        val slots = step.tasks.filter { it.state != TaskState.REASSIGNED && it.state != TaskState.TRANSFERRED }
        val approvals = slots.count { it.state == TaskState.COMPLETED && it.outcome == ActionKind.APPROVE }
        val open = slots.count { it.state == TaskState.OPEN }
        val needed =
            when (checkNotNull(node.mode)) {
                ParallelMode.ALL -> slots.size
                ParallelMode.ANY -> 1
                ParallelMode.N_OF_M -> checkNotNull(node.n)
            }
        return when {
            approvals >= needed -> Verdict.APPROVED
            approvals + open < needed -> Verdict.REJECTED
            else -> Verdict.WAITING
        }
    }
}
