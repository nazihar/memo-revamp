package com.memo.forum

import io.swagger.v3.oas.annotations.media.Schema

/** Who agrees a new due date the owner asks for (design E07 §1.7). */
enum class DueChange {
    /** A secretary not recused from the item agrees or declines (the default). */
    SECRETARY_AGREES,

    /** The chair agrees or declines (the vice-chair when the chair is recused or absent from office). */
    CHAIR_AGREES,

    /** The owner moves the date with a reason, and the secretaries are told. */
    OWNER_WITH_REASON,
}

/** Who verifies that an action marked as done is done (design E07 §1.9). Never the owner, the holder or a contributor. */
enum class ActionVerifier {
    /** The committee's secretaries in office who are not recused from the item (the default); then the chair. */
    SECRETARY,

    /** The chair, else the vice-chair; then the secretaries. */
    CHAIR,
}

/** Where a new action's due date starts (design E07 §1.13). */
enum class DefaultDue {
    /** The end of the next meeting's date; [ActionsPolicy.defaultDueWorkingDays] when no next meeting is scheduled. */
    NEXT_MEETING,

    /** [ActionsPolicy.defaultDueWorkingDays] working days after the action is raised. */
    WORKING_DAYS,
}

/**
 * A committee type's actions policy (ADR-0067, design E07 §1.13), part of each type version and reviewed with it. Unlike
 * the minutes policy it is read from the version in force today ([ForumPolicies.current]), not the meeting's pinned
 * version: an action lives across meetings, and its reminders follow the committee's current rules. A version stored
 * before it existed reads as the defaults.
 *
 * The rules a tenant cannot change are not here: reopening is possible for 90 days after closing or cancelling, and a
 * cancellation with the risk accepted is the chair's alone (design E07 §1.10). Reminders and escalation are the
 * workflow definition's ([definitionKey]), edited in the builder.
 */
data class ActionsPolicy(
    /** An open action turns amber this many working days before its due date: 1 to 60. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val amberWorkingDays: Int = DEFAULT_AMBER_DAYS,
    /** An open action without an update for this many working days shows "No recent update": 5 to 120, or 0 (off). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val staleWorkingDays: Int = DEFAULT_STALE_DAYS,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val dueChange: DueChange = DueChange.SECRETARY_AGREES,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val verifiedBy: ActionVerifier = ActionVerifier.SECRETARY,
    /** Marking an action as done needs at least one evidence file. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val evidenceRequired: Boolean = false,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val defaultDue: DefaultDue = DefaultDue.NEXT_MEETING,
    /** Working days for [DefaultDue.WORKING_DAYS], and for NEXT_MEETING when no next meeting is scheduled: 1 to 120. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val defaultDueWorkingDays: Int = DEFAULT_DUE_DAYS,
    /** Owners of open actions are asked for an update this many working days before the papers are due: 1 to 20, or 0 (off). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val updateRequestWorkingDays: Int = DEFAULT_UPDATE_REQUEST_DAYS,
    /** A published workflow definition bound to the subject `action-item` (ADR-0068); the built-in one by default. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val definitionKey: String = DEFAULT_DEFINITION,
) {
    companion object {
        /** The workflow subject type of action items (ADR-0067); definitions for actions are bound to it. */
        const val SUBJECT = "action-item"

        /** The built-in definition every tenant gets (design E07 §1.8). */
        const val DEFAULT_DEFINITION = "action-item"

        /** A rule given 0 working days is off. */
        const val OFF = 0

        const val DEFAULT_AMBER_DAYS = 10
        const val DEFAULT_STALE_DAYS = 20
        const val DEFAULT_DUE_DAYS = 20
        const val DEFAULT_UPDATE_REQUEST_DAYS = 5
        val AMBER_DAYS = 1..60
        val STALE_DAYS = 5..120
        val DUE_DAYS = 1..120
        val UPDATE_REQUEST_DAYS = 1..20
    }
}
