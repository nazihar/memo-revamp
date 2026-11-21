package com.memo.workflow

import java.util.UUID

/**
 * Who a slot of a subject names (ADR-0068): a person, or a position the engine resolves to its holder and re-resolves
 * when the holder changes, exactly as a `position` assignee.
 */
sealed interface SlotHolder {
    data class Person(
        val userId: UUID,
    ) : SlotHolder

    data class Position(
        val positionId: UUID,
    ) : SlotHolder
}

/**
 * One reason a definition is refused (ADR-0038): where (a JSON pointer into the body, for example `/steps/0/assignee`),
 * a stable code and a sentence for the admin. Subjects return these from [WorkflowSubject.checkDefinition].
 */
data class DefinitionProblem(
    val pointer: String,
    val code: String,
    val message: String,
)

/**
 * A definition bound to a subject type, as that subject checks it (ADR-0068, [WorkflowSubject.checkDefinition]): its
 * steps in authored order, so step `i` is at the pointer `/steps/i`.
 */
data class SubjectDefinition(
    val subject: String,
    val start: String,
    val steps: List<SubjectDefinitionStep>,
) {
    fun step(id: String): SubjectDefinitionStep? = steps.firstOrNull { it.id == id }

    /** The pointer of the step [id] in the body, or of the steps when it has none. */
    fun pointer(id: String): String = steps.indexOfFirst { it.id == id }.let { if (it < 0) "/steps" else "/steps/$it" }
}

/**
 * A step of a [SubjectDefinition]: its [type] as authored (`review`, `approve`, `branch`, …); [human] when it holds
 * tasks; the [slot] its assignee names when it is a `subject` assignee; [dueFromSubject] when its SLA is
 * `from: "subject"`; the [actions] it offers (`APPROVE`, `RECOMMEND`, `RETURN`, …); and the steps it can lead to.
 */
data class SubjectDefinitionStep(
    val id: String,
    val type: String,
    val human: Boolean,
    val slot: String?,
    val dueFromSubject: Boolean,
    val actions: List<String>,
    val targets: List<String>,
)
