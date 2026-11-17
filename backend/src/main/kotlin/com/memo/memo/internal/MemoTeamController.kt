package com.memo.memo.internal

import com.memo.common.authz.SignedIn
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

/**
 * The team view and the approvals dashboard (design E05 §5–§7; flows I and K). Signed-in users; the services decide the
 * scope (heads of a unit for its subtree, holders of the grants for theirs) and answer 403 outside it.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "memo")
class MemoTeamController(
    private val team: MemoTeam,
    private val dashboards: MemoDashboards,
    private val transactions: TenantTransactions,
) {
    @GetMapping("/inbox/team")
    @SignedIn
    @Operation(
        operationId = "listTeamTasks",
        summary = "Open tasks of the people in the units I oversee, by due time (heads and holders of inbox.team.read)",
    )
    fun team(
        @RequestParam(required = false) unit: UUID?,
        @RequestParam(defaultValue = "pending") status: String,
        @RequestParam(required = false) person: UUID?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "25") limit: Int,
    ): ResponseEntity<TeamPage> {
        require(status == "pending" || status == "overdue") { "status is pending or overdue" }
        val page =
            transactions.readInTenant(TenantContext.current()) {
                team.tasks(TeamFilter(unit, status == "overdue", person), cursor, limit)
            }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(page)
    }

    @GetMapping("/dashboards/approvals")
    @SignedIn
    @Operation(
        operationId = "approvalsDashboard",
        summary = "Pending by age, overdue, cycle time and throughput, for me or a unit I oversee (default: the last 12 weeks)",
    )
    fun approvals(
        @RequestParam(defaultValue = "me") scope: String,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ): ResponseEntity<ApprovalsDashboard> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(transactions.readInTenant(TenantContext.current()) { dashboards.approvals(scope, from, to) })
}
