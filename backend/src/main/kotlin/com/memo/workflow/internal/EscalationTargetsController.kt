package com.memo.workflow.internal

import com.memo.common.authz.Permission
import com.memo.common.authz.RequiresPermission
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.workflow.EscalationTarget
import com.memo.workflow.EscalationTargets
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** The committees an `escalate-to-forum` step may name, for the builder's picker (ADR-0056, design E01 §3). */
@RestController
@Tag(name = "workflow")
class EscalationTargetsController(
    private val targets: List<EscalationTargets>,
    private val transactions: TenantTransactions,
) {
    @GetMapping("/api/v1/admin/workflow/escalation-targets")
    @RequiresPermission(Permission.WORKFLOW_DEFINITIONS_MANAGE, orAnyOf = [Permission.WORKFLOW_DEFINITIONS_APPROVE])
    @Operation(operationId = "listEscalationTargets", summary = "Active committees a workflow step can escalate to")
    fun list(): List<EscalationTarget> =
        transactions.readInTenant(TenantContext.current()) { targets.flatMap { it.list() }.sortedBy { it.name } }
}
