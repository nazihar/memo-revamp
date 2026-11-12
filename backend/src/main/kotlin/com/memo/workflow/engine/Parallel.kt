package com.memo.workflow.engine

import com.memo.workflow.ActionKind
import com.memo.workflow.definition.ParallelMode
import com.memo.workflow.definition.StepDef

/** Where a parallel step stands after a decision (design E03 §4.3). */
internal enum class Verdict { WAITING, APPROVED, REJECTED }

/**
 * The completion rule of steps decided by several people (design E03 §4.3, E04 §4.1): a parallel step needs `all`, one
 * (`any`) or `n` approvals; a step resolved to several candidates needs its [Step.needed]. A step is approved as soon as
 * it has them, and rejected as soon as it can no longer get them. Tasks moved to someone else (reassigned, forwarded) do
 * not count; their replacements do.
 */
internal object Parallel {
    fun verdict(
        step: Step,
        node: StepDef,
    ): Verdict {
        val slots = step.tasks.filter { it.state != TaskState.REASSIGNED && it.state != TaskState.TRANSFERRED }
        val approvals = slots.count { it.state == TaskState.COMPLETED && it.outcome in POSITIVE }
        val open = slots.count { it.state == TaskState.OPEN }
        val needed = step.needed ?: needed(node, slots.size)
        return when {
            approvals >= needed -> Verdict.APPROVED
            approvals + open < needed -> Verdict.REJECTED
            else -> Verdict.WAITING
        }
    }

    /** The approvals a parallel [node] needs from [candidates] people. */
    fun needed(
        node: StepDef,
        candidates: Int,
    ): Int =
        when (checkNotNull(node.mode)) {
            ParallelMode.ALL -> candidates
            ParallelMode.ANY -> 1
            ParallelMode.N_OF_M -> checkNotNull(node.n)
        }

    private val POSITIVE = setOf(ActionKind.APPROVE, ActionKind.RECOMMEND)
}
