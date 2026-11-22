package com.memo.minutes.internal

import com.memo.common.authz.SignedIn
import com.memo.common.authz.StepUpCheck
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.system.Idempotency
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Corrections after approval (design E06 §3, §1.6.5, flow AG4): the list as each reader of the approved minutes reads it,
 * a secretary's proposal, and the approver's answer with a fresh step-up. Every change takes `Idempotency-Key`.
 */
@RestController
@Tag(name = "minutes")
class MinutesCorrectionsController(
    private val corrections: MinutesCorrectionsService,
    private val stepUp: StepUpCheck,
    private val idempotency: Idempotency,
    private val transactions: TenantTransactions,
) {
    @GetMapping("/api/v1/meetings/{meetingId}/minutes/corrections")
    @SignedIn
    @Operation(operationId = "listMinutesCorrections", summary = "The corrections to the approved minutes, as the viewer reads them")
    fun list(
        @PathVariable meetingId: UUID,
    ): ResponseEntity<MinutesCorrectionsResponse> =
        noStore(transactions.readInTenant(TenantContext.current()) { corrections.list(meetingId) })

    @PostMapping("/api/v1/meetings/{meetingId}/minutes/corrections")
    @SignedIn
    @Operation(
        operationId = "proposeMinutesCorrection",
        summary = "Propose a correction to the approved minutes (a secretary not recused from the item); the chair approves it",
    )
    fun propose(
        @PathVariable meetingId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: MinutesCorrectionInput,
    ): ResponseEntity<MinutesCorrectionResponse> {
        val request = "$meetingId\n${body.itemId}\n${body.text}\n${body.reason}"
        val answer =
            transactions.inTenant(TenantContext.current()) {
                val id =
                    idempotency.once("minutes.correction.propose", key, fingerprint(request)) {
                        corrections.propose(meetingId, body).id.toString()
                    }
                corrections.list(meetingId).corrections.single { it.id.toString() == id }
            }
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(answer)
    }

    @PostMapping("/api/v1/meetings/{meetingId}/minutes/corrections/{correctionId}/decide")
    @SignedIn
    @Operation(
        operationId = "decideMinutesCorrection",
        summary = "Approve or decline a proposed correction (the chair, with a fresh step-up): approval makes a new record version",
    )
    fun decide(
        @PathVariable meetingId: UUID,
        @PathVariable correctionId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: MinutesCorrectionDecisionRequest,
    ): ResponseEntity<MinutesCorrectionResponse> {
        // 404 and 403 before the step-up, so a refusal never asks for one.
        transactions.readInTenant(TenantContext.current()) { corrections.requireApprover(meetingId, correctionId) }
        stepUp.require()
        val answer =
            transactions.inTenant(TenantContext.current()) {
                idempotency.once("minutes.correction.decide", key, fingerprint("$meetingId\n$correctionId\n${body.decision}")) {
                    corrections.decide(meetingId, correctionId, body.decision, body.note).id.toString()
                }
                corrections.list(meetingId).corrections.single { it.id == correctionId }
            }
        return noStore(answer)
    }

    private fun <T : Any> noStore(body: T): ResponseEntity<T> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)
}

/**
 * Certified resolution extracts (design E06 §3, §1.6.6, flow AC3): issue one (a secretary not recused from the item, with a
 * fresh step-up; 202, the `minutes-extract` job makes it), the resolution's extracts, and one extract's PDF/A (an
 * attachment, each open audited `minutes.extract.opened`; 202 with `Retry-After` while it is made).
 */
@RestController
@Tag(name = "minutes")
class ResolutionExtractsController(
    private val extracts: ResolutionExtracts,
    private val streams: MinutesFileStreams,
    private val stepUp: StepUpCheck,
    private val idempotency: Idempotency,
    private val transactions: TenantTransactions,
) {
    @PostMapping("/api/v1/resolutions/{resolutionId}/extracts")
    @SignedIn
    @Operation(
        operationId = "issueResolutionExtract",
        summary = "Issue a certified extract of a resolution (a secretary not recused from the item, with a fresh step-up)",
    )
    fun issue(
        @PathVariable resolutionId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
    ): ResponseEntity<ResolutionExtractResponse> {
        // Who may and whether it can be issued (404, 403, 409) come first, then the step-up.
        transactions.readInTenant(TenantContext.current()) { extracts.requireIssuer(resolutionId) }
        stepUp.require()
        val answer =
            transactions.inTenant(TenantContext.current()) {
                val id =
                    idempotency.once("minutes.extract.issue", key, fingerprint("$resolutionId")) {
                        extracts.issue(resolutionId).id.toString()
                    }
                extracts.list(resolutionId).single { it.id.toString() == id }
            }
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore()).body(answer)
    }

    @GetMapping("/api/v1/resolutions/{resolutionId}/extracts")
    @SignedIn
    @Operation(operationId = "listResolutionExtracts", summary = "A resolution's certified extracts, the latest first")
    fun list(
        @PathVariable resolutionId: UUID,
    ): ResponseEntity<List<ResolutionExtractResponse>> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(transactions.readInTenant(TenantContext.current()) { extracts.list(resolutionId) })

    @GetMapping("/api/v1/resolutions/{resolutionId}/extracts/{extractId}/content")
    @SignedIn
    @Operation(
        operationId = "getResolutionExtract",
        summary = "A certified extract as PDF/A (an attachment; supports Range); 202 while it is being made",
    )
    fun content(
        @PathVariable resolutionId: UUID,
        @PathVariable extractId: UUID,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        val row = transactions.inTenant(TenantContext.current()) { extracts.serve(resolutionId, extractId) }
        streams.extract(row, request, response)
    }
}
