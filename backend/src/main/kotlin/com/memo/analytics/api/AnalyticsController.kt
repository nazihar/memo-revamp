package com.memo.analytics.api

import com.memo.analytics.export.AttendanceWorkbook
import com.memo.common.authz.SignedIn
import com.memo.common.ratelimit.RateLimit
import com.memo.common.ratelimit.RateLimiter
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.CacheControl
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

/**
 * Meeting analytics (ADR-0070, design E08 §2, §3; flows AU–AW): the overview per committee, a committee's detail, and its
 * attendance export for the annual report (officers and oversight; rate-limited per person and audited). Computed live
 * per viewer; nothing is cached (`Cache-Control: no-store`).
 */
@RestController
@Tag(name = "analytics")
class AnalyticsController(
    private val analytics: CommitteeAnalytics,
    private val workbook: AttendanceWorkbook,
    private val transactions: TenantTransactions,
    private val limiter: RateLimiter,
) {
    @GetMapping("/api/v1/analytics/committees")
    @SignedIn
    @Operation(
        operationId = "getCommitteeAnalytics",
        summary = "The five measures of each committee in the viewer's scope over a period (default the last 12 months)",
    )
    fun summary(
        @RequestParam(required = false) from: LocalDate?,
        @RequestParam(required = false) to: LocalDate?,
        @RequestParam(required = false) forumIds: List<UUID>?,
    ): ResponseEntity<AnalyticsSummaryResponse> = noStore(read { analytics.summary(from, to, forumIds.orEmpty().toSet()) })

    @GetMapping("/api/v1/analytics/committees/{forumId}")
    @SignedIn
    @Operation(
        operationId = "getCommitteeAnalyticsDetail",
        summary = "A committee's measures in detail: attendance per member, overdue actions by owner, timing by quarter and memo type",
    )
    fun detail(
        @PathVariable forumId: UUID,
        @RequestParam(required = false) from: LocalDate?,
        @RequestParam(required = false) to: LocalDate?,
    ): ResponseEntity<CommitteeDetailResponse> = noStore(read { analytics.detail(forumId, from, to) })

    @GetMapping("/api/v1/analytics/committees/{forumId}/attendance/export")
    @SignedIn
    @Operation(
        operationId = "exportCommitteeAttendance",
        summary = "A committee's attendance per seat holder as an Excel workbook, for the annual report (audited; 5 an hour)",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "The workbook",
                content = [Content(mediaType = AttendanceWorkbook.MEDIA_TYPE, schema = Schema(type = "string", format = "binary"))],
            ),
            ApiResponse(responseCode = "429", description = "Too many exports"),
        ],
    )
    fun export(
        @PathVariable forumId: UUID,
        @RequestParam(required = false) from: LocalDate?,
        @RequestParam(required = false) to: LocalDate?,
        response: HttpServletResponse,
    ) {
        // Who may export and the period are checked before the limit is counted: a refused request spends nothing.
        val actor = read { analytics.exporter(forumId, from, to) }
        limiter.consume(RateLimit.ANALYTICS_EXPORT_PER_USER, "${TenantContext.current()}:$actor")
        val export = transactions.inTenant(TenantContext.current()) { analytics.export(forumId, from, to) }
        response.contentType = AttendanceWorkbook.MEDIA_TYPE
        response.setHeader(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition
                .attachment()
                .filename(FILE_NAME)
                .build()
                .toString(),
        )
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        response.setHeader("X-Content-Type-Options", "nosniff")
        workbook.write(export, response.outputStream)
    }

    private fun <T : Any> read(block: () -> T): T = transactions.readInTenant(TenantContext.current(), block)

    private fun <T : Any> noStore(body: T): ResponseEntity<T> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)

    private companion object {
        const val FILE_NAME = "attendance.xlsx"
    }
}
