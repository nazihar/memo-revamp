package com.memo.workflow

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
}

/** Why a task was created but cannot be acted on until a tenant admin reassigns it (design E01 §2, invariant 12). */
enum class BlockedReason { NO_CLEARANCE, INACTIVE }

/** How a task stopped being open, as published in [WorkflowTaskClosed]. */
enum class TaskClosure { COMPLETED, CANCELLED, REASSIGNED }

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
