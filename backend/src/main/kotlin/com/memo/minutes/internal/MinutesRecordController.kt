package com.memo.minutes.internal

import com.memo.common.authz.SignedIn
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.files.FileServing
import com.memo.files.OpenAudit
import com.memo.files.ServeOutcome
import com.memo.files.ServedContent
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Streams the minutes' files to the one reader the minutes' own rule chose (ADR-0061's `FileServing`, which skips the
 * purpose's `mayRead`: the copies and records are never served by the generic files endpoints): the draft's copy, or the
 * approved minutes' record or the reader's approved copy, the open audited once per session (`minutes.copy.opened`); 202
 * with `Retry-After` while the file is being made.
 */
@Component
class MinutesFileStreams(
    private val serving: FileServing,
) {
    /** The draft's copy of [lookup] for [reader] ([previewAs]: the reader an officer previews). */
    fun copy(
        reader: MinutesReader,
        lookup: CopyLookup,
        previewAs: UUID?,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        val copy = (lookup as? CopyLookup.Ready)?.copy ?: return building(response)
        val details =
            mapOf(
                "stage" to copy.stage.name,
                "setKey" to copy.setKey,
                "sourceVersionId" to copy.sourceVersionId.toString(),
                "previewAs" to previewAs?.toString(),
            )
        stream(checkNotNull(copy.fileVersionId), reader.row.meetingId, details, request, response)
    }

    /** The approved minutes' record or the reader's approved copy; null [served]: being made. */
    fun record(
        served: RecordServe?,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        if (served == null) return building(response)
        stream(served.fileVersionId, served.meetingId, served.details, request, response)
    }

    private fun stream(
        versionId: UUID,
        meetingId: UUID,
        details: Map<String, Any?>,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        val audit = OpenAudit("minutes.copy.opened", MinutesDrafts.OBJECT_TYPE, meetingId.toString(), details)
        val outcome = serving.serve(versionId, ServedContent.ORIGINAL, request, response, audit)
        if (outcome != ServeOutcome.SERVED) throw MinutesNotFoundException("copy")
    }

    private fun building(response: HttpServletResponse) {
        response.status = HttpStatus.ACCEPTED.value()
        response.setHeader("Retry-After", RETRY_AFTER_SECONDS.toString())
        response.setHeader("Cache-Control", "no-store")
    }

    private companion object {
        const val RETRY_AFTER_SECONDS = 10
    }
}

/**
 * The approved minutes' record (design E06 §3, ADR-0066, E06 T7): the record or the reader's approved copy (PDF/A), the
 * versions as the reader reads them, and the check of a version (the approval's hashes and chain, the approved document
 * and the reader's file re-hashed from storage).
 */
@RestController
@Tag(name = "minutes")
class MinutesRecordController(
    private val recordReading: MinutesRecordReading,
    private val streams: MinutesFileStreams,
    private val transactions: TenantTransactions,
) {
    @GetMapping("/api/v1/meetings/{meetingId}/minutes/record")
    @SignedIn
    @Operation(
        operationId = "getMinutesRecord",
        summary =
            "The approved minutes' record as the viewer reads it (PDF/A, supports Range): the record itself, or their approved " +
                "copy; the current version or ?version=; 202 while their copy is being made, 409 RECORD_PENDING until it is made",
    )
    fun record(
        @PathVariable meetingId: UUID,
        @RequestParam(required = false) version: Int?,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        val served = transactions.inTenant(TenantContext.current()) { recordReading.serve(meetingId, version) }
        streams.record(served, request, response)
    }

    @GetMapping("/api/v1/meetings/{meetingId}/minutes/records")
    @SignedIn
    @Operation(operationId = "listMinutesRecords", summary = "The approved minutes' record versions as the viewer reads them")
    fun records(
        @PathVariable meetingId: UUID,
    ): ResponseEntity<List<MinutesRecordResponse>> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(transactions.inTenant(TenantContext.current()) { recordReading.versions(meetingId) })

    @PostMapping("/api/v1/meetings/{meetingId}/minutes/records/{version}/check")
    @SignedIn
    @Operation(
        operationId = "checkMinutesRecord",
        summary = "Recompute a record version's approval hashes and chain, and re-hash the approved document and the viewer's file",
    )
    fun checkRecord(
        @PathVariable meetingId: UUID,
        @PathVariable version: Int,
    ): ResponseEntity<MinutesRecordCheckResponse> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(recordReading.check(meetingId, version))
}
