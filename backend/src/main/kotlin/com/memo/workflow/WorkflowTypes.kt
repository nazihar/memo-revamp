package com.memo.workflow

import com.fasterxml.jackson.annotation.JsonIgnore
import java.util.UUID

/** Where a workflow instance is (ADR-0035). RUNNING while a round is open; RETURNED waits for the initiator. */
enum class InstanceStatus {
    RUNNING,
    RETURNED,
    APPROVED,
    REJECTED,
    WITHDRAWN,

    /** Stopped by an administrator with a reason (design E03 §4.4). */
    CANCELLED,
    ;

    val terminal: Boolean get() = this == APPROVED || this == REJECTED || this == WITHDRAWN || this == CANCELLED
}

/**
 * Everything that can happen to an instance; one `workflow_action` row each (ADR-0035, design E03 §4.4). ROUTE, NOTIFY,
 * ESCALATE_TO_FORUM and CARRY are recorded by the engine itself, after the action that led to them.
 */
enum class ActionKind {
    SUBMIT,
    APPROVE,
    RETURN,
    REJECT,
    WITHDRAW,
    RESUBMIT,
    REASSIGN,
    RECOMMEND,
    ROUTE,
    NOTIFY,
    ESCALATE_TO_FORUM,
    SEND_BACK,
    FORWARD,
    DELEGATE,
    ASK,
    ANSWER,
    REFER,
    OPINION,
    RECALL,
    CANCEL,
    MIGRATE,
    CARRY,

    /** A candidate took a step shared by several people (design E04 §4.2); [RELEASE] gives it back. */
    CLAIM,
    RELEASE,

    /** The holder declared an interest and stood aside (SOD-4, design E04 §4.4). */
    RECUSE,

    /** An overdue task escalated by the workflow (design E05 §4.3). */
    ESCALATE,
}

/**
 * Why a task was created but cannot be acted on until a tenant admin reassigns it (design E01 §2, invariant 12): the holder
 * cannot act, or nobody was found for the step (`UNRESOLVED`, design E04 §4.1).
 */
enum class BlockedReason { NO_CLEARANCE, INACTIVE, UNRESOLVED }

/** How a task stopped being open, as published in [WorkflowTaskClosed]. */
enum class TaskClosure { COMPLETED, CANCELLED, REASSIGNED, TRANSFERRED }

/** The type of an item attribute that definition conditions may read (ADR-0039), as JSON names it. */
enum class AttributeType(
    val json: String,
) {
    STRING("string"),
    INT("int"),
    DOUBLE("double"),
    BOOL("bool"),
    TIMESTAMP("timestamp"),
    ;

    companion object {
        fun of(json: String): AttributeType = entries.first { it.json == json }
    }
}

/** How a group on an initiator's route decides: every member approves, or any one of them (design E04 §4.7). */
enum class GroupMode { ALL, ANY }

/**
 * One entry of an initiator's route (design E04 §4.7): a person ([userId]), a position ([positionId], its holder when
 * the entry is reached), or a [group] of people and positions deciding together. Exactly one is set.
 */
data class RouteEntry(
    val userId: UUID? = null,
    val positionId: UUID? = null,
    val group: RouteGroup? = null,
) {
    /** The people the entry names directly: the person, or the people in the group. */
    @get:JsonIgnore
    val people: List<UUID> get() = listOfNotNull(userId) + group?.members?.mapNotNull { it.userId }.orEmpty()

    /** The positions the entry names directly. */
    @get:JsonIgnore
    val positions: List<UUID> get() = listOfNotNull(positionId) + group?.members?.mapNotNull { it.positionId }.orEmpty()

    /** A person, a position, or a group of 2 or more people and positions (no groups within groups). */
    @get:JsonIgnore
    val wellFormed: Boolean
        get() =
            listOfNotNull(userId, positionId, group).size == 1 &&
                group?.let { g ->
                    g.members.size >= 2 &&
                        g.members.all { it.group == null && listOfNotNull(it.userId, it.positionId).size == 1 }
                } !=
                false

    companion object {
        fun person(userId: UUID) = RouteEntry(userId = userId)
    }
}

data class RouteGroup(
    val mode: GroupMode,
    val members: List<RouteEntry>,
)

/** One approval group as the DoA would apply it: [needed] approvals from [people] (design E04 §4.3). */
data class DoaPreviewGroup(
    val needed: Int,
    val people: List<UUID>,
)

/**
 * Who the DoA would ask to approve an item (design E04 §4.3, §4.7): the version and the rule that matched (its
 * position, 0 first), and its groups in order; or why none applies ([reason] `NO_DOA` or `NO_RULE`).
 */
data class DoaPreview(
    val version: Int?,
    val rule: Int?,
    val ruleName: String?,
    val reason: String?,
    val groups: List<DoaPreviewGroup>,
)

/** A DoA group a route does not cover (design E04 §4.7): it reaches [covered] of the [needed] approvals among [people]. */
data class DoaShortfall(
    val group: Int,
    val needed: Int,
    val covered: Int,
    val people: List<UUID>,
)
