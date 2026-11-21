package com.memo.minutes.internal

import com.memo.common.authz.SignedIn
import com.memo.common.authz.StepUpCheck
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.minutes.ResolutionState
import com.memo.system.Idempotency
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Resolutions (ADR-0065, design E06 §1.3, §3, flows AC1–AC2): a committee's register in number order with its check, one
 * resolution, and the starting number of a period (go-live), set once by `forums.manage` with a fresh step-up.
 */
@RestController
@Tag(name = "minutes")
class ResolutionsController(
    private val access: ResolutionAccess,
    private val transactions: TenantTransactions,
    private val idempotency: Idempotency,
    private val stepUp: StepUpCheck,
) {
    @GetMapping("/api/v1/forums/{forumId}/resolutions")
    @SignedIn
    @Operation(operationId = "getResolutionRegister", summary = "A committee's resolutions in number order, the latest first")
    fun register(
        @PathVariable forumId: UUID,
        @RequestParam(required = false) period: String?,
        @RequestParam(required = false) state: ResolutionState?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false, defaultValue = "50") limit: Int,
    ): ResponseEntity<ResolutionRegisterResponse> =
        noStore(read { access.register(forumId, period?.trim(), state, cursor?.toLongOrNull(), limit) })

    @GetMapping("/api/v1/forums/{forumId}/resolutions/check")
    @SignedIn
    @Operation(operationId = "checkResolutionRegister", summary = "Whether every number of a committee's period is accounted for")
    fun check(
        @PathVariable forumId: UUID,
        @RequestParam(required = false) period: String?,
    ): ResponseEntity<ResolutionCheckResponse> = noStore(read { access.check(forumId, period?.trim()) })

    @GetMapping("/api/v1/resolutions/{resolutionId}")
    @SignedIn
    @Operation(operationId = "getResolution", summary = "One resolution as the viewer may see it")
    fun resolution(
        @PathVariable resolutionId: UUID,
    ): ResponseEntity<ResolutionResponse> = noStore(read { access.resolution(resolutionId) })

    @PutMapping("/api/v1/forums/{forumId}/resolution-counters/{period}")
    @SignedIn
    @Operation(
        operationId = "startResolutionCounter",
        summary = "Set the first resolution number of a committee's period before any is given (forums.manage, step-up)",
    )
    fun start(
        @PathVariable forumId: UUID,
        @PathVariable period: String,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: ResolutionCounterRequest,
    ): ResponseEntity<Void> {
        // Who may comes first (404, 403), then the step-up, so a refusal never asks for one.
        read { access.requireManager(forumId) }
        stepUp.require()
        transactions.inTenant(TenantContext.current()) {
            idempotency.once("minutes.resolution.counter", key, fingerprint("$forumId\n$period\n${body.next}")) {
                access.start(forumId, period.trim(), body.next)
                period
            }
        }
        return ResponseEntity.noContent().build()
    }

    private fun <T : Any> read(block: () -> T): T = transactions.readInTenant(TenantContext.current(), block)

    private fun <T : Any> noStore(body: T): ResponseEntity<T> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)
}
