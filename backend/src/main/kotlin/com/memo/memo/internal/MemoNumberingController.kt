package com.memo.memo.internal

import com.memo.common.authz.Permission
import com.memo.common.authz.RequiresPermission
import com.memo.common.authz.StepUpCheck
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.system.Idempotency
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** Where a counter starts: the first number it gives in [period] (and [scope], for a series counted per unit). */
data class NumberStartRequest(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val period: String,
    /** A unit id for a series counted per unit; empty otherwise. */
    val scope: String = "",
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val next: Long,
)

/** A numbering to preview with a type code and, for numbers with a unit, a unit code. */
data class NumberingPreviewRequest(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val numbering: Numbering,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val typeCode: String,
    val unitCode: String? = null,
)

/**
 * Memo numbering (ADR-0044, design E01 §3): series and counters, the register of a counter, and previews for type
 * administrators, reviewers and auditors; a counter's starting number by a reviewer (`memo.types.approve`) after a step-up,
 * once, before it gives any number.
 */
@RestController
@Tag(name = "memo")
class MemoNumberingController(
    private val admin: MemoNumberingAdmin,
    private val stepUp: StepUpCheck,
    private val idempotency: Idempotency,
    private val transactions: TenantTransactions,
    private val json: JsonMapper,
) {
    @GetMapping("/api/v1/admin/memo-numbering")
    @RequiresPermission(Permission.MEMO_TYPES_MANAGE, orAnyOf = [Permission.MEMO_TYPES_APPROVE, Permission.AUDIT_READ])
    @Operation(operationId = "listNumberSeries", summary = "Numbering series with the types numbering in them and their counters")
    fun series(): ResponseEntity<List<NumberSeriesView>> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(transactions.readInTenant(TenantContext.current()) { admin.series() })

    @GetMapping("/api/v1/admin/memo-numbering/{series}/register")
    @RequiresPermission(Permission.MEMO_TYPES_MANAGE, orAnyOf = [Permission.MEMO_TYPES_APPROVE, Permission.AUDIT_READ])
    @Operation(operationId = "getNumberRegister", summary = "The numbers of a counter in order, with the numbers no memo has")
    fun register(
        @PathVariable series: String,
        @RequestParam period: String,
        @RequestParam(required = false, defaultValue = "") scope: String,
        @RequestParam(required = false, defaultValue = "0") after: Long,
        @RequestParam(required = false, defaultValue = "50") limit: Int,
    ): ResponseEntity<NumberRegisterView> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(
                transactions.readInTenant(
                    TenantContext.current(),
                ) { admin.register(NumberKey(series, period, scope), after, limit, user()) },
            )

    @PostMapping("/api/v1/admin/memo-numbering/{series}/start")
    @RequiresPermission(Permission.MEMO_TYPES_APPROVE)
    @Operation(operationId = "startNumberCounter", summary = "Set the first number of a counter that has given none")
    fun start(
        @PathVariable series: String,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: NumberStartRequest,
    ): ResponseEntity<Void> {
        stepUp.require()
        transactions.inTenant(TenantContext.current()) {
            idempotency.once("memo.numbering.start", key, fingerprint(json.writeValueAsString(body) + series)) {
                admin.start(NumberKey(series, body.period.trim(), body.scope.trim()), body.next)
                series
            }
        }
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/api/v1/admin/memo-types/numbering/preview")
    @RequiresPermission(Permission.MEMO_TYPES_MANAGE, orAnyOf = [Permission.MEMO_TYPES_APPROVE])
    @Operation(operationId = "previewNumbering", summary = "Sample numbers for a numbering, or what is wrong with it")
    fun preview(
        @RequestBody body: NumberingPreviewRequest,
    ): NumberingPreview = transactions.readInTenant(TenantContext.current()) { admin.preview(body.numbering, body.typeCode, body.unitCode) }

    private fun user(): UUID = (ActorContext.current().actor as? Actor.User)?.userId ?: throw IllegalStateException("a signed-in reader")
}
