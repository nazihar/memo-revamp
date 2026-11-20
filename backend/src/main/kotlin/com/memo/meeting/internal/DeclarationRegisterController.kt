package com.memo.meeting.internal

import com.memo.common.authz.SignedIn
import com.memo.common.ratelimit.RateLimit
import com.memo.common.ratelimit.RateLimiter
import com.memo.common.tenant.TenantContext
import com.memo.meeting.DeclarationState
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
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

/**
 * The registers of declarations and the reminder (ADR-0062, design E04 §3): the meeting register for its officers, "Remind
 * them now" for its secretaries, and the register across committees with its Excel export (rate-limited and audited).
 */
@RestController
@Tag(name = "meeting")
class DeclarationRegisterController(
    private val register: DeclarationRegister,
    private val registers: DeclarationRegisters,
    private val workbook: DeclarationWorkbook,
    private val calls: MeetingCalls,
    private val limiter: RateLimiter,
) {
    @GetMapping("/api/v1/meetings/{meetingId}/declarations")
    @SignedIn
    @Operation(
        operationId = "getMeetingDeclarationRegister",
        summary = "The meeting's declarations by item, the review of the agenda and quorum warnings (its officers)",
    )
    fun meeting(
        @PathVariable meetingId: UUID,
    ): ResponseEntity<MeetingDeclarationRegisterResponse> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(calls.read { register.meeting(meetingId) })

    @PostMapping("/api/v1/meetings/{meetingId}/declarations/remind")
    @SignedIn
    @Operation(
        operationId = "remindDeclarations",
        summary = "Remind the members with items to review to declare their interests now, once per person and revision",
    )
    fun remind(
        @PathVariable meetingId: UUID,
    ): ResponseEntity<DeclarationReminderResponse> {
        actorOrNull()?.let { limiter.consume(RateLimit.AGENDA_WRITE_PER_USER, "${TenantContext.current()}:$it") }
        return ResponseEntity.ok().body(calls.write { register.remind(meetingId) })
    }

    @GetMapping("/api/v1/declarations")
    @SignedIn
    @Operation(
        operationId = "listDeclarations",
        summary = "The register of declarations across committees, newest first, 50 a page (forum.read, forums.manage, officers)",
    )
    fun list(
        @RequestParam(required = false) forumId: UUID?,
        @RequestParam(required = false) from: LocalDate?,
        @RequestParam(required = false) to: LocalDate?,
        @RequestParam(required = false) userId: UUID?,
        @RequestParam(required = false) state: DeclarationState?,
        @RequestParam(required = false) cursor: String?,
    ): ResponseEntity<DeclarationRegisterResponse> {
        val filter = RegisterFilter(forumId, from, to, userId, state)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(calls.read { registers.page(filter, cursor) })
    }

    @GetMapping("/api/v1/declarations/export")
    @SignedIn
    @Operation(
        operationId = "exportDeclarations",
        summary = "The register of declarations under the filters as an Excel workbook, at most 20,000 rows (audited; 5 an hour)",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "The workbook",
                content = [Content(mediaType = DeclarationWorkbook.MEDIA_TYPE, schema = Schema(type = "string", format = "binary"))],
            ),
            ApiResponse(responseCode = "429", description = "Too many exports"),
        ],
    )
    fun export(
        @RequestParam(required = false) forumId: UUID?,
        @RequestParam(required = false) from: LocalDate?,
        @RequestParam(required = false) to: LocalDate?,
        @RequestParam(required = false) userId: UUID?,
        @RequestParam(required = false) state: DeclarationState?,
        response: HttpServletResponse,
    ) {
        val filter = RegisterFilter(forumId, from, to, userId, state)
        val actor = calls.read { registers.scope() }.actor
        limiter.consume(RateLimit.DECLARATION_EXPORT_PER_USER, "${TenantContext.current()}:$actor")
        val rows = calls.write { registers.export(filter) }
        response.contentType = DeclarationWorkbook.MEDIA_TYPE
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
        workbook.write(rows, response.outputStream)
    }

    private companion object {
        const val FILE_NAME = "declarations.xlsx"
    }
}
