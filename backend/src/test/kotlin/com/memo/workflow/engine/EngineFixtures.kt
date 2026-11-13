package com.memo.workflow.engine

import com.memo.workflow.definition.Assignee
import com.memo.workflow.definition.AssigneeKind
import com.memo.workflow.definition.StepAction
import org.assertj.core.api.Assertions.assertThat
import java.util.UUID

/** Helpers shared by the engine tests of the E03–E05 commands. */
internal object EngineFixtures {
    /** Every action a step can offer its holder, for definitions that allow them all. */
    val ALL_ACTIONS =
        listOf(
            StepAction.APPROVE,
            StepAction.RETURN,
            StepAction.REJECT,
            StepAction.SEND_BACK,
            StepAction.FORWARD,
            StepAction.DELEGATE,
            StepAction.ASK,
            StepAction.REFER,
        )

    /** Ids handed out in order, distinct from the people of a test (who use `UUID(0, n)`). */
    fun ids(): () -> UUID {
        var next = 1_000L
        return { UUID(1, next++) }
    }

    fun named(person: UUID): Assignee = Assignee(AssigneeKind.PERSON, userId = person)
}

/** The outcome of an accepted command; fails the test when it was refused. */
internal fun Decision.accepted(): Outcome {
    assertThat(this).isInstanceOf(Decision.Accepted::class.java)
    return (this as Decision.Accepted).outcome
}

/** Why the command was refused, or null when it was accepted. */
internal fun Decision.refusal(): Refusal? = (this as? Decision.Refused)?.reason

/** The open task [person] holds (decision or consultation). */
internal fun InstanceState.openTaskOf(person: UUID): Task = openTasks.single { it.assignee == person }

/** The step of the round that holds [taskId]. */
internal fun InstanceState.stepOf(taskId: UUID): Step = round.steps.single { step -> step.tasks.any { it.id == taskId } }
