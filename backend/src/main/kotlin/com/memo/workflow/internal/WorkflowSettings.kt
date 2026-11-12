package com.memo.workflow.internal

import com.memo.tenancy.TenantSettings
import org.springframework.stereotype.Component

/** Typed reads of the tenant's workflow settings, clamped to their ranges like the security settings. In a tenant transaction. */
@Component
class WorkflowSettings(
    private val settings: TenantSettings,
) {
    /** Most approvers an initiator-defined route may have (design E01 §2, invariant 11). */
    fun maxApprovers(): Int =
        settings
            .get(MAX_APPROVERS)
            ?.value
            ?.toIntOrNull()
            ?.coerceIn(MAX_APPROVERS_RANGE) ?: MAX_APPROVERS_DEFAULT

    /** SOD-2: nobody approves twice in one round (design E04 §4.4); on unless the tenant switched it off. */
    fun oncePerRound(): Boolean = settings.get(ONCE_PER_ROUND)?.value?.toBooleanStrictOrNull() ?: true

    companion object {
        const val MAX_APPROVERS = "workflow.route.max"
        const val ONCE_PER_ROUND = "workflow.sod.once.per.round"
        private const val MAX_APPROVERS_DEFAULT = 20
        private val MAX_APPROVERS_RANGE = 1..50
    }
}
