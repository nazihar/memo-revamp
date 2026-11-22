package com.memo.actions.internal

import com.memo.common.authz.SignedIn
import com.memo.system.Idempotency
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
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

/**
 * The action taken report under "Matters arising" (ADR-0067 C1, design E07 §1.12, §3, flow AO): read on screen and as a
 * PDF by the item's readers (each their rows by the one rule; every open audited), an action held back from the next
 * meeting's report, and "Ask owners for an update" (a secretary or `forums.manage`, once a day per committee). Writes need
 * an `Idempotency-Key` and share the agenda's per-person rate limit.
 */
@RestController
@Tag(name = "actions")
class ActionReportController(
    private val reads: ActionReportReads,
    private val holds: ActionReportHolds,
    private val requests: ActionUpdateRequests,
    private val calls: ActionCalls,
    private val idempotency: Idempotency,
    private val json: JsonMapper,
) {
    @GetMapping("/api/v1/meetings/{meetingId}/action-report")
    @SignedIn
    @Operation(
        operationId = "getActionReport",
        summary = "The action taken report of the meeting's agenda (the latest, or a revision), as the reader may see it",
    )
    fun report(
        @PathVariable meetingId: UUID,
        @RequestParam(required = false) revision: Int?,
    ): ResponseEntity<ActionReportResponse> = noStore(calls.write { reads.report(meetingId, revision) })

    @GetMapping("/api/v1/meetings/{meetingId}/action-report/pdf", produces = [MediaType.APPLICATION_PDF_VALUE])
    @SignedIn
    @Operation(operationId = "getActionReportPdf", summary = "The action taken report as a PDF of the reader's rows, to print")
    fun pdf(
        @PathVariable meetingId: UUID,
        @RequestParam(required = false) revision: Int?,
    ): ResponseEntity<ByteArray> {
        val bytes = calls.write { reads.pdf(meetingId, revision) }
        return ResponseEntity
            .ok()
            .contentType(MediaType.APPLICATION_PDF)
            .cacheControl(CacheControl.noStore().cachePrivate())
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition
                    .inline()
                    .filename("action-taken-report.pdf")
                    .build()
                    .toString(),
            ).header("X-Content-Type-Options", "nosniff")
            .header("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'; sandbox")
            .header("Cross-Origin-Resource-Policy", "same-origin")
            .body(bytes)
    }

    @PostMapping("/api/v1/actions/{actionId}/report-hold")
    @SignedIn
    @Operation(
        operationId = "holdActionFromReport",
        summary = "Hold an action back from the next meeting's action taken report, with a reason (secretaries)",
    )
    fun hold(
        @PathVariable actionId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: ReportHoldRequest,
    ): ResponseEntity<ReportHoldResponse> {
        calls.limit()
        val request = "$actionId\n${json.writeValueAsString(body)}"
        val held =
            calls.write {
                val meetingId =
                    idempotency.once("actions.report-hold", key, fingerprint(request)) { holds.hold(actionId, body).meetingId.toString() }
                holds.held(actionId, UUID.fromString(meetingId))
            }
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(held)
    }

    @PostMapping("/api/v1/forums/{forumId}/actions/ask-for-updates")
    @SignedIn
    @Operation(
        operationId = "askForActionUpdates",
        summary = "Ask the owners of open actions with no update since the last meeting for one (secretaries, once a day)",
    )
    fun askForUpdates(
        @PathVariable forumId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
    ): ResponseEntity<AskForUpdatesResponse> {
        calls.limit()
        val asked =
            calls.write {
                idempotency.once("actions.ask-for-updates", key, fingerprint("$forumId")) { requests.askNow(forumId).toString() }
            }
        return noStore(AskForUpdatesResponse(asked.toInt()))
    }
}
