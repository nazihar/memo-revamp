package com.memo.actions.internal

import com.memo.common.authz.SignedIn
import com.memo.common.ratelimit.RateLimit
import com.memo.common.ratelimit.RateLimiter
import com.memo.common.tenant.TenantContext
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletResponse
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
 * The committee's action tracker and its export (design E07 §3, flow AN; [ActionTracker]): rows by the one rule, totals
 * per colour from the same predicate, keyset pages of 50; the export is rate-limited per person and audited.
 */
@RestController
@Tag(name = "actions")
class ActionTrackerController(
    private val tracker: ActionTracker,
    private val workbook: ActionTrackerWorkbook,
    private val calls: ActionCalls,
    private val limiter: RateLimiter,
) {
    @GetMapping("/api/v1/forums/{forumId}/actions")
    @SignedIn
    @Operation(
        operationId = "getForumActions",
        summary = "The committee's action tracker: filtered rows the viewer may see, totals per colour, 50 a page",
    )
    @Suppress("LongParameterList")
    fun page(
        @PathVariable forumId: UUID,
        @RequestParam(required = false) status: List<ActionStatus>?,
        @RequestParam(required = false) rag: RagColour?,
        @RequestParam(required = false) owner: UUID?,
        @RequestParam(required = false) overdue: Boolean?,
        @RequestParam(required = false) dueFrom: LocalDate?,
        @RequestParam(required = false) dueTo: LocalDate?,
        @RequestParam(required = false) meetingId: UUID?,
        @RequestParam(required = false) itemId: UUID?,
        @RequestParam(required = false) source: ActionSource?,
        @RequestParam(required = false) text: String?,
        @RequestParam(required = false) sort: TrackerSort?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false, defaultValue = "50") limit: Int,
    ): ResponseEntity<ForumActionsResponse> {
        val filter = filter(status, rag, owner, overdue, dueFrom, dueTo, meetingId, itemId, source, text, sort)
        return noStore(calls.read { tracker.page(forumId, filter, cursor, limit) })
    }

    @GetMapping("/api/v1/forums/{forumId}/actions/export")
    @SignedIn
    @Operation(
        operationId = "exportForumActions",
        summary = "The committee's action tracker under the filters as an Excel workbook, at most 5,000 rows (audited; 10 an hour)",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "The workbook",
                content = [Content(mediaType = ActionTrackerWorkbook.MEDIA_TYPE, schema = Schema(type = "string", format = "binary"))],
            ),
            ApiResponse(responseCode = "429", description = "Too many exports"),
        ],
    )
    @Suppress("LongParameterList")
    fun export(
        @PathVariable forumId: UUID,
        @RequestParam(required = false) status: List<ActionStatus>?,
        @RequestParam(required = false) rag: RagColour?,
        @RequestParam(required = false) owner: UUID?,
        @RequestParam(required = false) overdue: Boolean?,
        @RequestParam(required = false) dueFrom: LocalDate?,
        @RequestParam(required = false) dueTo: LocalDate?,
        @RequestParam(required = false) meetingId: UUID?,
        @RequestParam(required = false) itemId: UUID?,
        @RequestParam(required = false) source: ActionSource?,
        @RequestParam(required = false) text: String?,
        @RequestParam(required = false) sort: TrackerSort?,
        response: HttpServletResponse,
    ) {
        val filter = filter(status, rag, owner, overdue, dueFrom, dueTo, meetingId, itemId, source, text, sort)
        // Who may export, and the filter, are checked before the limit is counted: a refused request spends nothing.
        val actor = calls.read { tracker.exporter(forumId, filter) }
        limiter.consume(RateLimit.ACTION_EXPORT_PER_USER, "${TenantContext.current()}:$actor")
        val export = calls.write { tracker.export(forumId, filter) }
        response.contentType = ActionTrackerWorkbook.MEDIA_TYPE
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

    @Suppress("LongParameterList")
    private fun filter(
        status: List<ActionStatus>?,
        rag: RagColour?,
        owner: UUID?,
        overdue: Boolean?,
        dueFrom: LocalDate?,
        dueTo: LocalDate?,
        meetingId: UUID?,
        itemId: UUID?,
        source: ActionSource?,
        text: String?,
        sort: TrackerSort?,
    ) = TrackerFilter(
        statuses = status.orEmpty().toSet(),
        rag = rag,
        owner = owner,
        overdue = overdue == true,
        dueFrom = dueFrom,
        dueTo = dueTo,
        meetingId = meetingId,
        itemId = itemId,
        source = source,
        text = text,
        sort = sort ?: TrackerSort.DUE,
    )

    private companion object {
        const val FILE_NAME = "actions.xlsx"
    }
}
