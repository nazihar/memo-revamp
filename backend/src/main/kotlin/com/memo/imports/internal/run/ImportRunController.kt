package com.memo.imports.internal.run

import com.memo.common.authz.Permission
import com.memo.common.authz.RequiresPermission
import com.memo.common.authz.StepUpCheck
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.etagOf
import com.memo.common.web.ifMatchVersion
import com.memo.imports.internal.ImportBatch
import com.memo.imports.internal.ImportBatchView
import com.memo.imports.internal.ImportViews
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Start an import (design E06 §10). */
data class ImportStartRequest(
    /** The READY rows the screen showed; the start is refused (409 `ROWS_CHANGED`) when the batch has another count. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val expectedRows: Int,
)

/**
 * The import of a validated batch (ADR-0055, design E06 §5.3, §10): start and resume with a fresh step-up, stop between
 * chunks, and finish a stopped one early. Holders of `memo.import` cleared to the batch's highest level only.
 */
@RestController
@RequestMapping("/api/v1/admin/imports/{id}")
@Tag(name = "imports")
class ImportRunController(
    private val control: ImportRunControl,
    private val views: ImportViews,
    private val transactions: TenantTransactions,
    private val stepUp: StepUpCheck,
) {
    @PostMapping("/start")
    @RequiresPermission(Permission.MEMO_IMPORT)
    @Operation(operationId = "startImport", summary = "Start the import of a validated batch (step-up; a job imports the rows)")
    fun start(
        @PathVariable id: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: ImportStartRequest,
    ): ResponseEntity<ImportBatchView> {
        stepUp.require()
        val version = ifMatchVersion(ifMatch)
        return accepted { control.start(id, version, body.expectedRows, stepUp.provenAt()) }
    }

    @PostMapping("/stop")
    @RequiresPermission(Permission.MEMO_IMPORT)
    @Operation(operationId = "stopImport", summary = "Stop a running import between two chunks of rows")
    fun stop(
        @PathVariable id: UUID,
    ): ResponseEntity<ImportBatchView> = accepted { control.stop(id) }

    @PostMapping("/resume")
    @RequiresPermission(Permission.MEMO_IMPORT)
    @Operation(operationId = "resumeImport", summary = "Resume a stopped import (step-up)")
    fun resume(
        @PathVariable id: UUID,
    ): ResponseEntity<ImportBatchView> {
        stepUp.require()
        return accepted { control.resume(id, stepUp.provenAt()) }
    }

    @PostMapping("/finish")
    @RequiresPermission(Permission.MEMO_IMPORT)
    @Operation(operationId = "finishImport", summary = "Finish a stopped import: the rows left are not started, and verification follows")
    fun finish(
        @PathVariable id: UUID,
    ): ResponseEntity<ImportBatchView> = accepted { control.finish(id) }

    /** 202 with the batch as it is now and its `ETag`: the job carries on with the work. */
    private fun accepted(change: () -> ImportBatch): ResponseEntity<ImportBatchView> {
        val view = transactions.inTenant(TenantContext.current()) { views.of(change()) }
        return ResponseEntity.status(HttpStatus.ACCEPTED).eTag(etagOf(view.version)).body(view)
    }
}
