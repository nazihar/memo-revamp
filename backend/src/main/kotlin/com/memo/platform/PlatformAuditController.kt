package com.memo.platform

import com.memo.audit.AuditFilter
import com.memo.audit.AuditQuery
import com.memo.common.authz.OperatorOnly
import com.memo.common.tenant.PlatformTransactions
import com.memo.common.web.FieldProblem
import com.memo.common.web.ProblemTypes
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

@Schema(name = "PlatformAuditEvent", description = "One entry of the platform chain (ADR-0031, ADR-0033).")
data class PlatformAuditEventResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val seq: Long,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val occurredAt: Instant,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val actorType: String,
    val actorId: String? = null,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val action: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val objectType: String,
    val objectId: String? = null,
    val after: Map<String, Any?>? = null,
)

@Schema(name = "PlatformAuditPage")
data class PlatformAuditPageResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val items: List<PlatformAuditEventResponse>,
    val nextCursor: Long? = null,
)

/** The platform chain on the operator console (ADR-0031): what operators and platform jobs did, newest first. */
@RestController
@OperatorOnly
@RequestMapping("/api/v1/platform/audit")
@Tag(name = "platform-audit")
class PlatformAuditController(
    private val query: AuditQuery,
    private val platform: PlatformTransactions,
    private val clock: Clock,
) {
    @GetMapping
    @Operation(
        operationId = "queryPlatformAudit",
        summary = "Platform chain entries, newest first (default: the last 7 days)",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "OK",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = PlatformAuditPageResponse::class))],
            ),
        ],
    )
    fun page(
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) action: String?,
        @RequestParam(required = false) cursor: Long?,
        @RequestParam(required = false, defaultValue = "50") limit: Int,
    ): ResponseEntity<Any> {
        val end = parse(to) ?: clock.instant()
        val start = parse(from) ?: end.minus(DEFAULT_RANGE)
        if (!start.isBefore(end) || Duration.between(start, end) > MAX_RANGE || (action?.length ?: 0) > MAX_TEXT) {
            return ResponseEntity
                .badRequest()
                .body(ProblemTypes.validation(listOf(FieldProblem("from", "INVALID", "Choose a range of at most one year."))))
        }
        val size = limit.coerceIn(1, MAX_PAGE)
        val rows = platform.read { query.page(AuditFilter(start, end, action?.trim()?.ifEmpty { null }), cursor, size + 1) }
        val shown = rows.take(size)
        val page =
            PlatformAuditPageResponse(
                shown.map {
                    PlatformAuditEventResponse(
                        it.seq,
                        it.occurredAt,
                        it.actorType,
                        it.actorId,
                        it.action,
                        it.objectType,
                        it.objectId,
                        it.after,
                    )
                },
                shown.lastOrNull()?.seq?.takeIf { rows.size > size },
            )
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(page)
    }

    private fun parse(text: String?): Instant? =
        text?.let {
            try {
                Instant.parse(it)
            } catch (_: DateTimeParseException) {
                null
            }
        }

    private companion object {
        val DEFAULT_RANGE: Duration = Duration.ofDays(7)
        val MAX_RANGE: Duration = Duration.ofDays(366)
        const val MAX_PAGE = 200
        const val MAX_TEXT = 200
    }
}
