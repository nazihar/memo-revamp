package com.memo.workflow.definition

import com.memo.workflow.AttributeType
import java.util.UUID

/** The steps a definition can have (ADR-0038); [human] steps hold tasks, the others act and move on. */
enum class StepType(
    val json: String,
    val human: Boolean,
) {
    ROUTE("route", true),
    REVIEW("review", true),
    APPROVE("approve", true),
    PARALLEL("parallel", true),
    BRANCH("branch", false),
    NOTIFY("notify", false),
    SUBWORKFLOW("subworkflow", false),
    ESCALATE_TO_FORUM("escalate-to-forum", false),

    /** Schema only until Phase 5: a definition with it is refused at validation. */
    SIGN("sign", true),
    ;

    companion object {
        fun of(json: String): StepType = entries.first { it.json == json }
    }
}

/** What a step lets its holder do (design E03 §4.4). The positive action follows the step type. */
enum class StepAction { APPROVE, RECOMMEND, RETURN, REJECT, SEND_BACK, FORWARD, DELEGATE, ASK, REFER }

enum class ResubmitPolicy(
    val json: String,
) {
    RESTART("restart"),
    RESUME("resume"),
    SKIP_UNCHANGED("skip-unchanged"),
    ;

    companion object {
        fun of(json: String): ResubmitPolicy = entries.first { it.json == json }
    }
}

enum class ParallelMode(
    val json: String,
) {
    ALL("all"),
    ANY("any"),
    N_OF_M("n-of-m"),
    ;

    companion object {
        fun of(json: String): ParallelMode = entries.first { it.json == json }
    }
}

enum class AssigneeKind(
    val json: String,
) {
    PERSON("person"),
    INITIATOR("initiator"),
    ;

    companion object {
        fun of(json: String): AssigneeKind = entries.first { it.json == json }
    }
}

/** Who a step goes to. E03 knows a named person and the initiator (notify only); E04 adds resolvers. */
data class Assignee(
    val kind: AssigneeKind,
    val userId: UUID? = null,
)

data class Attribute(
    val name: String,
    val type: AttributeType,
)

data class Branch(
    val condition: String,
    val next: String,
)

/** Due time of a step (ISO-8601 duration), validated now and acted on in E05. */
data class Sla(
    val due: String,
    val workingTime: Boolean? = null,
)

/** One step as authored. Which fields are set depends on [type]; the JSON Schema enforces the combinations. */
data class StepDef(
    val id: String,
    val type: StepType,
    val name: String? = null,
    val next: String? = null,
    val assignee: Assignee? = null,
    val assignees: List<Assignee> = emptyList(),
    val mode: ParallelMode? = null,
    val n: Int? = null,
    val actions: List<StepAction>? = null,
    val remarks: List<StepAction>? = null,
    val branches: List<Branch> = emptyList(),
    val otherwise: String? = null,
    val recipients: List<Assignee> = emptyList(),
    val message: String? = null,
    val definition: String? = null,
    val forum: String? = null,
    val sla: Sla? = null,
) {
    /** Where this step can lead: `next`, or every branch and `otherwise`. */
    val targets: List<String> get() = listOfNotNull(next) + branches.map { it.next } + listOfNotNull(otherwise)

    /** The actions offered: the authored list, or the E01 set for the step type. */
    val offeredActions: List<StepAction>
        get() = actions ?: defaultActions(type)

    companion object {
        fun defaultActions(type: StepType): List<StepAction> =
            when (type) {
                StepType.REVIEW -> listOf(StepAction.RECOMMEND, StepAction.RETURN)
                StepType.ROUTE, StepType.APPROVE, StepType.PARALLEL -> listOf(StepAction.APPROVE, StepAction.RETURN, StepAction.REJECT)
                else -> emptyList()
            }
    }
}

/** A workflow definition, schema version 2 (ADR-0038). [END] is the implicit final step. */
data class Definition(
    val attributes: List<Attribute> = emptyList(),
    val resubmit: ResubmitPolicy? = null,
    val start: String,
    val steps: List<StepDef>,
) {
    val stepsById: Map<String, StepDef> by lazy { steps.associateBy { it.id } }

    val resubmitPolicy: ResubmitPolicy get() = resubmit ?: ResubmitPolicy.RESTART

    fun step(id: String): StepDef = stepsById[id] ?: throw IllegalArgumentException("no step '$id'")

    companion object {
        const val SCHEMA_VERSION = 2
        const val END = "end"
    }
}
