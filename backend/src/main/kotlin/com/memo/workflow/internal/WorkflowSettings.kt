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

    companion object {
        const val MAX_APPROVERS = "workflow.route.max"
        private const val MAX_APPROVERS_DEFAULT = 20
        private val MAX_APPROVERS_RANGE = 1..50
    }
}
