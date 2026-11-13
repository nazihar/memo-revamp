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

    /** The approval groups of the delegation of authority, one after the other (design E04 §4.3). */
    DOA("doa", true),
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

    /** The holder of a position (design E04 §2). */
    POSITION("position"),

    /** The holder of the position the initiator's position reports to, [Assignee.levels] up. */
    SUPERVISOR("supervisor"),

    /** The holder of a unit's head position: the initiator's unit or [Assignee.unit], [Assignee.up] levels above. */
    UNIT_HEAD("unitHead"),

    /** The holders of a role in a unit (the initiator's or [Assignee.unit]) or above it. */
    ROLE("role"),

    /** The members of a queue (people, and the holders of member positions), [Assignee.queue] by key. */
    QUEUE("queue"),
    ;

    companion object {
        fun of(json: String): AssigneeKind = entries.first { it.json == json }
    }
}

/**
 * Who a step goes to (design E04 §2): a named person, the initiator (notify only), or a resolver over the organisation.
 * Which fields are set depends on [kind]; the JSON Schema enforces the combinations.
 */
data class Assignee(
    val kind: AssigneeKind,
    val userId: UUID? = null,
    val positionId: UUID? = null,
    /** Reporting lines up, for [AssigneeKind.SUPERVISOR]. */
    val levels: Int? = null,
    /** A unit id, or null for the initiator's unit ([AssigneeKind.UNIT_HEAD], [AssigneeKind.ROLE]). */
    val unit: UUID? = null,
    /** Units above, for [AssigneeKind.UNIT_HEAD]. */
    val up: Int? = null,
    val roleId: UUID? = null,
    /** A queue key, for [AssigneeKind.QUEUE]. */
    val queue: String? = null,
)

data class Attribute(
    val name: String,
    val type: AttributeType,
)

data class Branch(
    val condition: String,
    val next: String,
)

/** How far a step's holder may forward it (design E04 §4.7): within their unit (and below), their legal entity, or to anyone. */
enum class ForwardWithin(
    val json: String,
) {
    UNIT("unit"),
    LEGAL_ENTITY("legalEntity"),
    ANYONE("anyone"),
    ;

    companion object {
        fun of(json: String): ForwardWithin = entries.first { it.json == json }
    }
}

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
    /** Who gets the step when [assignee] resolves to nobody (design E04 §4.1); without it the step waits for an administrator. */
    val fallback: Assignee? = null,
    /** How far its holder may forward it; anyone when null (E03's behaviour). */
    val forward: ForwardWithin? = null,
) {
    /** Where this step can lead: `next`, or every branch and `otherwise`. */
    val targets: List<String> get() = listOfNotNull(next) + branches.map { it.next } + listOfNotNull(otherwise)

    /** The actions offered: the authored list, or the E01 set for the step type. */
    val offeredActions: List<StepAction>
        get() = actions ?: defaultActions(type)

    companion object {
        fun defaultActions(type: StepType): List<StepAction> =
            when (type) {
                StepType.REVIEW -> {
                    listOf(StepAction.RECOMMEND, StepAction.RETURN)
                }

                StepType.ROUTE, StepType.APPROVE, StepType.PARALLEL, StepType.DOA -> {
                    listOf(
                        StepAction.APPROVE,
                        StepAction.RETURN,
                        StepAction.REJECT,
                    )
                }

                else -> {
                    emptyList()
                }
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
