package com.memo.workflow

/** Where a workflow instance is (ADR-0035). RUNNING while a round is open; RETURNED waits for the initiator. */
enum class InstanceStatus {
    RUNNING,
    RETURNED,
    APPROVED,
    REJECTED,
    WITHDRAWN,
    ;

    val terminal: Boolean get() = this == APPROVED || this == REJECTED || this == WITHDRAWN
}

/** Everything that can happen to an instance; one `workflow_action` row each (ADR-0035). */
enum class ActionKind { SUBMIT, APPROVE, RETURN, REJECT, WITHDRAW, RESUBMIT, REASSIGN }

/** Why a task was created but cannot be acted on until a tenant admin reassigns it (design E01 §2, invariant 12). */
enum class BlockedReason { NO_CLEARANCE, INACTIVE }
