package com.memo.memo.internal.legacy

import com.memo.common.authz.Permission
import com.memo.common.authz.RequiresPermission
import com.memo.common.authz.StepUpCheck
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.etagOf
import com.memo.memo.internal.MemoView
import com.memo.memo.internal.MemoViews
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** A correction of an imported memo's legacy facts or subject (design E06 §11). */
data class LegacyCorrectionRequest(
    /** Any field but CLASSIFICATION, which is raised through reclassify. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val field: LegacyCorrectionField,
    /** The corrected value: text, or a date as `yyyy-mm-dd`; empty only to clear a decision date the status does not need. */
    val value: String? = null,
    /** Why (1 to 500 characters); kept with the correction and in the audit trail. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val reason: String,
)

/** A raise of an imported memo's classification (never lower; the reader must be cleared to the new level). */
data class LegacyReclassifyRequest(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val classificationId: UUID,
    /** Why (1 to 500 characters). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val reason: String,
)

/**
 * Corrections and classification raises of imported memos (ADR-0055, design E06 §10, §11): holders of `memo.import` who
 * read the memo (else 404), with a fresh step-up. A memo raised here is 409 `NOT_IMPORTED`.
 */
@RestController
@Tag(name = "memo")
class LegacyMemoController(
    private val corrections: LegacyCorrections,
    private val views: MemoViews,
    private val transactions: TenantTransactions,
    private val stepUp: StepUpCheck,
) {
    @PostMapping("/api/v1/memos/{id}/legacy/corrections")
    @RequiresPermission(Permission.MEMO_IMPORT)
    @Operation(operationId = "correctLegacyMemo", summary = "Append a correction to an imported memo, with a reason (step-up)")
    fun correct(
        @PathVariable id: UUID,
        @RequestBody body: LegacyCorrectionRequest,
    ): ResponseEntity<MemoView> {
        stepUp.require()
        val view =
            transactions.inTenant(TenantContext.current()) {
                views.of(corrections.correct(id, body.field, body.value, body.reason))
            }
        return ResponseEntity.ok().eTag(etagOf(view.version)).body(view)
    }

    @PostMapping("/api/v1/memos/{id}/legacy/reclassify")
    @RequiresPermission(Permission.MEMO_IMPORT)
    @Operation(operationId = "reclassifyLegacyMemo", summary = "Raise an imported memo's classification, with a reason (step-up)")
    fun reclassify(
        @PathVariable id: UUID,
        @RequestBody body: LegacyReclassifyRequest,
    ): ResponseEntity<MemoView> {
        stepUp.require()
        val view =
            transactions.inTenant(TenantContext.current()) {
                views.of(corrections.reclassify(id, body.classificationId, body.reason))
            }
        return ResponseEntity.ok().eTag(etagOf(view.version)).body(view)
    }
}
