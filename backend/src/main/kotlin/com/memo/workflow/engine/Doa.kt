package com.memo.workflow.engine

import com.memo.workflow.definition.Assignee

/** One approval group of a DoA rule: [needed] approvals from the people its [members] resolve to (design E04 §4.3). */
data class DoaGroup(
    val needed: Int,
    val members: List<Assignee>,
)

/**
 * What the delegation of authority asks of an instance (design E04 §4.3, ADR-0040): the groups of the rule that matched,
 * in order, and what was matched ([record], kept with each group's step). No groups when no version is pinned or no rule
 * matches; the record then says why, and the step waits for an administrator.
 */
data class DoaMatch(
    val groups: List<DoaGroup>,
    val record: Map<String, Any?>,
) {
    companion object {
        const val NO_DOA = "NO_DOA"
        const val NO_RULE = "NO_RULE"

        /** No DoA version was in force when the instance started. */
        val NONE = DoaMatch(emptyList(), mapOf("reason" to NO_DOA))
    }
}
