package com.memo.audit.internal

import com.memo.audit.AuditExport
import com.memo.audit.AuditFilter
import com.memo.audit.AuditQuery
import com.memo.audit.AuditRecord
import com.memo.common.authz.Permission
import com.memo.common.authz.RequiresPermission
import com.memo.common.authz.StepUpCheck
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorNames
import com.memo.common.ratelimit.RateLimit
import com.memo.common.ratelimit.RateLimiter
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.FieldProblem
import com.memo.common.web.ProblemTypes
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID

@Schema(name = "AuditEvent", description = "One audit entry (ADR-0033).")
data class AuditEventResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val seq: Long,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val occurredAt: Instant,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "USER, SYSTEM, PLATFORM_OPERATOR or ANONYMOUS")
    val actorType: String,
    val actorId: String? = null,
    @field:Schema(description = "Display name when the actor is a user of this organisation")
    val actorName: String? = null,
    val actingAsId: String? = null,
    val clientIp: String? = null,
    val clientUserAgent: String? = null,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val action: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val objectType: String,
    val objectId: String? = null,
    val before: Map<String, Any?>? = null,
    val after: Map<String, Any?>? = null,
)

@Schema(name = "AuditPage")
data class AuditPageResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val items: List<AuditEventResponse>,
    @field:Schema(description = "Pass as cursor for older entries; absent on the last page")
    val nextCursor: Long? = null,
)

@Schema(name = "AuditExportRequest", description = "Leave the narrowing fields empty for a chain-continuous export.")
data class AuditExportRequest(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val from: Instant,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val to: Instant,
    val action: String? = null,
    val actorId: String? = null,
    val objectType: String? = null,
    val objectId: String? = null,
)

/** Shared by the tenant and platform viewers: parameters to a filter, records to responses. */
internal object AuditViews {
    val MAX_RANGE: Duration = Duration.ofDays(366)
    private val DEFAULT_RANGE: Duration = Duration.ofDays(7)
    private const val MAX_TEXT = 200

    /** The filter, or the field problems; `to` defaults to now and `from` to a week before it. */
    fun filter(
        clock: Clock,
        from: String?,
        to: String?,
        action: String?,
        actorId: String?,
        objectType: String?,
        objectId: String?,
    ): Pair<AuditFilter?, List<FieldProblem>> {
        val end = parse(to) ?: clock.instant()
        val start = parse(from) ?: end.minus(DEFAULT_RANGE)
        val problems =
            buildList {
                if (from != null && parse(from) == null) add(FieldProblem("from", "INVALID", "Use an ISO date-time."))
                if (to != null && parse(to) == null) add(FieldProblem("to", "INVALID", "Use an ISO date-time."))
                if (!start.isBefore(end)) add(FieldProblem("from", "AFTER_TO", "Start before the end."))
                if (Duration.between(start, end) > MAX_RANGE) add(FieldProblem("from", "RANGE_TOO_LONG", "At most one year."))
                listOf(action, actorId, objectType, objectId).filter { (it?.length ?: 0) > MAX_TEXT }.forEach { _ ->
                    add(FieldProblem("filter", "TOO_LONG", "Shorten the filter."))
                }
            }
        val filter =
            AuditFilter(start, end, action.blankToNull(), actorId.blankToNull(), objectType.blankToNull(), objectId.blankToNull())
        return (filter.takeIf { problems.isEmpty() }) to problems
    }

    fun response(
        record: AuditRecord,
        names: Map<UUID, String>,
    ) = AuditEventResponse(
        record.seq,
        record.occurredAt,
        record.actorType,
        record.actorId,
        record.actorId
            ?.let(::uuidOrNull)
            ?.let { names[it] }
            .takeIf { record.actorType == "USER" },
        record.actingAsId,
        record.clientIp,
        record.clientUserAgent,
        record.action,
        record.objectType,
        record.objectId,
        record.before,
        record.after,
    )

    fun uuidOrNull(text: String): UUID? = runCatching { UUID.fromString(text) }.getOrNull()

    private fun parse(text: String?): Instant? =
        text?.let {
            try {
                Instant.parse(it)
            } catch (_: DateTimeParseException) {
                null
            }
        }

    private fun String?.blankToNull() = this?.trim()?.takeIf { it.isNotEmpty() }
}

/** Admin → Audit (ADR-0033): the tenant's chain, newest first, and the verifiable export. */
@RestController
@RequiresPermission(Permission.AUDIT_READ)
@RequestMapping("/api/v1/admin/audit")
@Tag(name = "audit")
class AuditQueryController(
    private val query: AuditQuery,
    private val export: AuditExport,
    private val transactions: TenantTransactions,
    private val names: ObjectProvider<ActorNames>,
    private val limiter: RateLimiter,
    private val stepUp: StepUpCheck,
    private val clock: Clock,
) {
    @GetMapping
    @Operation(
        operationId = "queryAudit",
        summary = "Audit entries, newest first (default: the last 7 days; at most a year)",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "OK",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = AuditPageResponse::class))],
            ),
        ],
    )
    fun page(
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) action: String?,
        @RequestParam(required = false) actorId: String?,
        @RequestParam(required = false) objectType: String?,
        @RequestParam(required = false) objectId: String?,
        @RequestParam(required = false) cursor: Long?,
        @RequestParam(required = false, defaultValue = "50") limit: Int,
    ): ResponseEntity<Any> {
        limiter.consume(
            RateLimit.AUDIT_QUERY_PER_USER,
            ActorContext
                .current()
                .actor.id
                .orEmpty(),
        )
        val (filter, problems) = AuditViews.filter(clock, from, to, action, actorId, objectType, objectId)
        if (filter == null) return ResponseEntity.badRequest().body(ProblemTypes.validation(problems))
        val page =
            transactions.readInTenant(TenantContext.current()) {
                val rows = query.page(filter, cursor, limit + 1)
                val shown = rows.take(limit.coerceIn(1, MAX_PAGE))
                val users = shown.filter { it.actorType == "USER" }.mapNotNull { it.actorId?.let(AuditViews::uuidOrNull) }.toSet()
                val known = names.ifAvailable?.names(users).orEmpty()
                AuditPageResponse(shown.map { AuditViews.response(it, known) }, shown.lastOrNull()?.seq?.takeIf { rows.size > shown.size })
            }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(page)
    }

    @PostMapping("/export")
    @Operation(
        operationId = "exportAudit",
        summary = "Verifiable export: ZIP with events, manifest and verification steps (step-up; 5 per hour)",
        responses = [
            ApiResponse(responseCode = "200", description = "The ZIP", content = [Content(mediaType = "application/zip")]),
            ApiResponse(responseCode = "400", description = "Invalid range"),
            ApiResponse(responseCode = "401", description = "Step-up required"),
            ApiResponse(responseCode = "429", description = "Too many exports"),
        ],
    )
    fun export(
        @RequestBody body: AuditExportRequest,
        response: HttpServletResponse,
    ): ResponseEntity<Any>? {
        stepUp.require()
        val (filter, problems) =
            AuditViews.filter(clock, body.from.toString(), body.to.toString(), body.action, body.actorId, body.objectType, body.objectId)
        if (filter == null) return ResponseEntity.badRequest().body(ProblemTypes.validation(problems))
        limiter.consume(RateLimit.AUDIT_EXPORT_PER_TENANT, TenantContext.current().toString())
        response.contentType = "application/zip"
        response.setHeader("Content-Disposition", "attachment; filename=\"audit-export.zip\"")
        response.setHeader("Cache-Control", "no-store")
        export.write(filter, UUID.fromString(ActorContext.current().actor.id), response.outputStream)
        return null
    }

    private companion object {
        const val MAX_PAGE = 200
    }
}
