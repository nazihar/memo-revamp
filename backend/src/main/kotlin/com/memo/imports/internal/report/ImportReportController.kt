package com.memo.imports.internal.report

import com.memo.common.authz.ForbiddenException
import com.memo.common.authz.Permission
import com.memo.common.authz.Policy
import com.memo.common.authz.RequiresPermission
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.etagOf
import com.memo.common.web.refuseIf
import com.memo.imports.internal.BatchState
import com.memo.imports.internal.ImportBatch
import com.memo.imports.internal.ImportBatchView
import com.memo.imports.internal.ImportBatches
import com.memo.imports.internal.ImportViews
import com.memo.imports.internal.close.ImportClosing
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/** Close or abandon a batch (design E06 §10). */
data class ImportCloseRequest(
    /** Why: required to close a completed batch, optional to abandon one; at most 500 characters. */
    val reason: String? = null,
)

/** A part of the report, or a source kept beside it; download it through `GET /files/{fileId}/versions/{versionId}/content`. */
data class ImportReportPartView(
    /** SUMMARY (PDF), DETAIL (XLSX), MANIFEST (text), or SOURCE_ with the table (WORKBOOK, MEMOS, FILES, APPROVALS). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val kind: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val fileId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val versionId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
    /** Lower-case hex SHA-256 of the part as stored. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val sha256: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val size: Long,
)

/** The reconciliation report of a completed (or since closed) batch (design E06 §8, §10). */
data class ImportReportView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val batchId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val state: BatchState,
    val completedAt: Instant?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val balanced: Boolean,
    /** The balance lines that do not add up (ROWS, FILES_LISTED, ORIGINALS_REGISTERED, ORIGINALS_STORED, RENDITIONS_PDF, ...). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val failing: List<String>,
    /** Every Files row has a received original, and every received original is accounted for. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val complete: Boolean,
    /** Every hash held at intake, at promotion and in the verification pass. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val valid: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val totals: ReportTotals,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val parts: List<ImportReportPartView>,
)

/**
 * A batch's report, and its closing (ADR-0055, design E06 §4.1, §8, §10). The report is for holders of `memo.import` or
 * `audit.read` cleared to the highest level the batch wrote (403 otherwise; its parts' downloads ask the same through
 * the `imports.record` purpose). Close and abandon are for holders of `memo.import`; neither takes `If-Match`, since
 * each acts from its own states only.
 */
@RestController
@RequestMapping("/api/v1/admin/imports/{id}")
@Tag(name = "imports")
class ImportReportController(
    private val batches: ImportBatches,
    private val closing: ImportClosing,
    private val views: ImportViews,
    private val policy: Policy,
    private val transactions: TenantTransactions,
    private val json: JsonMapper,
) {
    @GetMapping("/report")
    @RequiresPermission(Permission.MEMO_IMPORT, orAnyOf = [Permission.AUDIT_READ])
    @Operation(operationId = "getImportReport", summary = "A completed batch's reconciliation report and its parts")
    fun report(
        @PathVariable id: UUID,
    ): ResponseEntity<ImportReportView> {
        val view =
            transactions.readInTenant(TenantContext.current()) {
                val batch = batches.get(id)
                if (policy.clearanceRank() < (batch.maxRank ?: 0)) throw ForbiddenException(null)
                val stored = batch.end.report
                refuseIf(stored == null, "REPORT_NOT_READY") { "The report is made once the import is verified." }
                val report = json.readValue(checkNotNull(stored), StoredReport::class.java)
                ImportReportView(
                    batchId = batch.id,
                    state = batch.state,
                    completedAt = batch.end.completedAt,
                    balanced = report.balanced,
                    failing = report.failing,
                    complete = report.complete,
                    valid = report.valid,
                    totals = report.totals,
                    parts = report.parts.map { ImportReportPartView(it.kind, it.fileId, it.versionId, it.name, it.sha256, it.size) },
                )
            }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view)
    }

    @PostMapping("/close")
    @RequiresPermission(Permission.MEMO_IMPORT)
    @Operation(operationId = "closeImport", summary = "Close a completed batch (a reason is required); its staged copies are purged")
    fun close(
        @PathVariable id: UUID,
        @RequestBody body: ImportCloseRequest,
    ): ResponseEntity<ImportBatchView> = changed { closing.close(id, body.reason) }

    @PostMapping("/abandon")
    @RequiresPermission(Permission.MEMO_IMPORT)
    @Operation(operationId = "abandonImport", summary = "Abandon a batch whose import has not started; its staged copies are purged")
    fun abandon(
        @PathVariable id: UUID,
        @RequestBody(required = false) body: ImportCloseRequest?,
    ): ResponseEntity<ImportBatchView> = changed { closing.abandon(id, body?.reason) }

    private fun changed(change: () -> ImportBatch): ResponseEntity<ImportBatchView> {
        val view = transactions.inTenant(TenantContext.current()) { views.of(change()) }
        return ResponseEntity.ok().eTag(etagOf(view.version)).body(view)
    }
}
