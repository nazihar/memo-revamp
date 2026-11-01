package com.memo.org.internal

import com.memo.common.authz.Permission
import com.memo.common.authz.RequiresPermission
import com.memo.common.authz.SignedIn
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.etagOf
import com.memo.common.web.ifMatchVersion
import com.memo.system.Idempotency
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.util.UUID

/** Reporting lines (design E04 §6) and the change history of units and positions. */
@RestController
@Tag(name = "org")
class ReportingLinesController(
    private val lines: ReportingLines,
    private val views: OrgViews,
    private val idempotency: Idempotency,
    private val transactions: TenantTransactions,
    private val json: JsonMapper,
) {
    @PostMapping("/api/v1/org/positions/{id}/reporting-lines")
    @RequiresPermission(Permission.ORG_MANAGE)
    @Operation(operationId = "createReportingLine", summary = "Make a position report to another from a date")
    fun create(
        @PathVariable id: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: ReportingLineRequest,
    ): ResponseEntity<ReportingLineResponse> {
        val created =
            write {
                val line =
                    idempotency.once("org.reporting_line.create", key, json.writeValueAsString(body) + id) {
                        lines.create(id, body.reportsToPositionId, Period(body.validFrom, body.validTo)).id.toString()
                    }
                views.reportingLine(UUID.fromString(line))
            }
        return ResponseEntity.created(URI.create("/api/v1/org/reporting-lines/${created.id}")).eTag(etagOf(created.version)).body(created)
    }

    @PutMapping("/api/v1/org/reporting-lines/{id}")
    @RequiresPermission(Permission.ORG_MANAGE)
    @Operation(operationId = "updateReportingLine", summary = "End a reporting line or correct its dates")
    fun update(
        @PathVariable id: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: PeriodRequest,
    ): ResponseEntity<ReportingLineResponse> {
        val version = ifMatchVersion(ifMatch)
        val updated =
            write {
                lines.changeDates(id, version, Period(body.validFrom, body.validTo))
                views.reportingLine(id)
            }
        return ResponseEntity.ok().eTag(etagOf(updated.version)).body(updated)
    }

    @PostMapping("/api/v1/org/reporting-lines/{id}/cancel")
    @RequiresPermission(Permission.ORG_MANAGE)
    @Operation(operationId = "cancelReportingLine", summary = "Cancel a reporting line recorded in error")
    fun cancel(
        @PathVariable id: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
    ): ResponseEntity<ReportingLineResponse> {
        val version = ifMatchVersion(ifMatch)
        val cancelled =
            write {
                lines.cancel(id, version)
                views.reportingLine(id)
            }
        return ResponseEntity.ok().eTag(etagOf(cancelled.version)).body(cancelled)
    }

    /** `audit.read`, or `org.manage` on the object: checked in [OrgViews.history]. */
    @GetMapping("/api/v1/org/history/{type}/{id}")
    @SignedIn
    @Operation(operationId = "getOrgHistory", summary = "Change history of a unit or position, newest first")
    fun history(
        @PathVariable type: String,
        @PathVariable id: UUID,
        @RequestParam(required = false, defaultValue = "50") limit: Int,
    ): ResponseEntity<List<HistoryEntryResponse>> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(transactions.readInTenant(TenantContext.current()) { views.history(type, id, limit) })

    private fun <T> write(block: () -> T): T = transactions.inTenant(TenantContext.current(), block)
}
