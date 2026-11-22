package com.memo.search.internal.decision

import com.memo.common.authz.SignedIn
import com.memo.common.context.ActorContext
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
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The decision register across committees (ADR-0069, design E08 §3; flow AS): committees' officers and members (their
 * committees), `forums.manage` and `forum.read` (every committee); 403 for anyone else. Searches are POST bodies (words
 * never in a URL), rate-limited per person (`DECISION_SEARCH_PER_USER`, 30 a minute, before any transaction) and audited.
 * Problems of the search package: 422 `validation` with `errors` (`TEXT_TOO_LONG`, `TOO_MANY_TERMS`, `FORUM_OUT_OF_SCOPE`,
 * `RANGE`, `SORT_NEEDS_ONE_FORUM`, `TOO_MANY`, `cursor INVALID`), 503 when a search takes too long. The export (E08 T3,
 * flow AT) is for officers and oversight: who and the filters are checked before `REGISTER_EXPORT_PER_USER` (5 an hour)
 * is counted, then the rows are read and audited in one transaction, and the file is written once it has committed.
 */
@RestController
@RequestMapping("/api/v1/decision-register")
@Tag(name = "search")
class DecisionRegisterController(
    private val register: DecisionRegister,
    private val exports: DecisionExports,
    private val workbook: RegisterWorkbook,
    private val pdf: RegisterPdf,
    private val transactions: TenantTransactions,
    private val limiter: RateLimiter,
) {
    @GetMapping("/scope")
    @SignedIn
    @Operation(
        operationId = "getDecisionRegisterScope",
        summary = "The committees, committee types, outcomes and memo types the viewer may filter the register by",
    )
    fun scope(): ResponseEntity<RegisterScopeView> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(transactions.readInTenant(TenantContext.current()) { register.scope() })

    @PostMapping("/search")
    @SignedIn
    @Operation(
        operationId = "searchDecisionRegister",
        summary = "Decisions and resolutions the viewer may see, in full or as restricted lines, with totals and facets",
    )
    fun search(
        @RequestBody body: DecisionSearchRequest,
    ): ResponseEntity<DecisionSearchView> {
        val actor =
            ActorContext
                .current()
                .actor.id
                .orEmpty()
        limiter.consume(RateLimit.DECISION_SEARCH_PER_USER, "${TenantContext.current()}:$actor")
        val page = transactions.inTenant(TenantContext.current()) { register.search(body) }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(page)
    }

    @PostMapping("/export")
    @SignedIn
    @Operation(
        operationId = "exportDecisionRegister",
        summary =
            "The register under the filters as a spreadsheet (at most 20,000 rows) or a PDF (2,000), with the number check " +
                "(officers and oversight; audited; 5 an hour)",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "The spreadsheet or the PDF",
                content = [
                    Content(mediaType = RegisterWorkbook.MEDIA_TYPE, schema = Schema(type = "string", format = "binary")),
                    Content(mediaType = MediaType.APPLICATION_PDF_VALUE, schema = Schema(type = "string", format = "binary")),
                ],
            ),
            ApiResponse(responseCode = "422", description = "The filters, or more rows than the format holds (TOO_MANY_ROWS)"),
            ApiResponse(responseCode = "429", description = "Too many exports"),
        ],
    )
    fun export(
        @RequestBody body: DecisionExportRequest,
        response: HttpServletResponse,
    ) {
        val tenant = TenantContext.current()
        // Who may export, and the filters, are checked before the limit is counted: a refused request spends nothing.
        val actor = transactions.readInTenant(tenant) { exports.exporter(body) }
        limiter.consume(RateLimit.REGISTER_EXPORT_PER_USER, "$tenant:$actor")
        val export = transactions.inTenant(tenant) { exports.export(body) }
        val (type, name) =
            when (body.format) {
                RegisterExportFormat.XLSX -> RegisterWorkbook.MEDIA_TYPE to "decision-register.xlsx"
                RegisterExportFormat.PDF -> MediaType.APPLICATION_PDF_VALUE to "decision-register.pdf"
            }
        response.contentType = type
        response.setHeader(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition
                .attachment()
                .filename(name)
                .build()
                .toString(),
        )
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        response.setHeader("X-Content-Type-Options", "nosniff")
        when (body.format) {
            RegisterExportFormat.XLSX -> workbook.write(export, response.outputStream)
            RegisterExportFormat.PDF -> pdf.write(export, response.outputStream)
        }
    }
}
