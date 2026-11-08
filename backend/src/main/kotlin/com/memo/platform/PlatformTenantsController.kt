package com.memo.platform

import com.memo.common.authz.OperatorOnly
import com.memo.common.tenant.TenantId
import com.memo.common.web.FieldProblem
import com.memo.common.web.ProblemTypes
import com.memo.common.web.etagOf
import com.memo.common.web.ifMatchVersion
import com.memo.tenancy.SlugRejectedException
import com.memo.tenancy.TenantStatus
import com.memo.tenancy.TenantSummary
import com.memo.tenancy.UnknownTenantException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

@Schema(name = "PlatformTenant", description = "A tenant's registry entry: metadata only, never tenant data (ADR-0031).")
data class PlatformTenantResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val slug: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val name: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val status: TenantStatus,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val version: Long,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val createdAt: Instant,
    val statusChangedAt: Instant? = null,
    @field:Schema(description = "Whether the tenant has an active administrator; only on the single-tenant view")
    val hasAdmin: Boolean? = null,
)

@Schema(name = "PlatformTenantPage")
data class PlatformTenantPage(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val items: List<PlatformTenantResponse>,
    @field:Schema(description = "Pass as cursor for the next page; absent on the last page")
    val nextCursor: String? = null,
)

@Schema(name = "PlatformTenantCreation")
data class PlatformTenantCreation(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Host label: <slug>.<base domain>", maxLength = 63)
    val slug: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 200)
    val name: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Default language: en or ar")
    val locale: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "IANA time zone, for example Asia/Dhaka")
    val timeZone: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 320)
    val adminEmail: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 200)
    val adminName: String,
)

@Schema(name = "PlatformTenantRename")
data class PlatformTenantRename(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 200)
    val name: String,
)

@Schema(name = "PlatformFirstAdmin")
data class PlatformFirstAdmin(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 320)
    val email: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 200)
    val displayName: String,
)

/** Tenants on the operator console (ADR-0031, design E07 §4). Changes that cut access or reach into a tenant need step-up. */
@RestController
@OperatorOnly
@RequestMapping("/api/v1/platform/tenants")
@Tag(name = "platform-tenants")
class PlatformTenantsController(
    private val tenants: PlatformTenants,
) {
    @GetMapping
    @Operation(operationId = "listPlatformTenants", summary = "Tenants by slug, optionally filtered by name or slug")
    fun list(
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false, defaultValue = "50") limit: Int,
    ): ResponseEntity<PlatformTenantPage> {
        val size = limit.coerceIn(1, MAX_PAGE)
        val rows = tenants.page(cursor, q?.take(MAX_QUERY), size + 1)
        val page = PlatformTenantPage(rows.take(size).map { it.toResponse() }, rows.getOrNull(size)?.let { rows[size - 1].slug })
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(page)
    }

    @PostMapping
    @Operation(
        operationId = "createPlatformTenant",
        summary = "Create a tenant and invite its first administrator",
        responses = [
            ApiResponse(
                responseCode = "201",
                description = "Created; the invitation mail is queued",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = PlatformTenantResponse::class))],
            ),
            ApiResponse(responseCode = "400", description = "Invalid or reserved slug, name, language, time zone or email"),
            ApiResponse(responseCode = "401", description = "Step-up required"),
        ],
    )
    fun create(
        @RequestBody body: PlatformTenantCreation,
    ): ResponseEntity<Any> {
        val problems = problems(body)
        if (problems.isNotEmpty()) return ResponseEntity.badRequest().body(ProblemTypes.validation(problems))
        val created =
            tenants.create(
                NewTenant(body.slug.trim(), body.name.trim(), body.locale, body.timeZone, body.adminEmail.trim(), body.adminName.trim()),
            )
        return ResponseEntity.status(HttpStatus.CREATED).eTag(etagOf(created.version)).body(created.toResponse())
    }

    @GetMapping("/{id}")
    @Operation(operationId = "getPlatformTenant", summary = "One tenant's registry entry")
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<PlatformTenantResponse> {
        val tenant = tenants.get(TenantId(id)) ?: return ResponseEntity.notFound().build()
        return ResponseEntity
            .ok()
            .eTag(etagOf(tenant.version))
            .cacheControl(CacheControl.noStore())
            .body(tenant.toResponse(hasAdmin = tenants.hasAdmin(tenant.id)))
    }

    @PatchMapping("/{id}")
    @Operation(
        operationId = "renamePlatformTenant",
        summary = "Rename a tenant (If-Match)",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "OK",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = PlatformTenantResponse::class))],
            ),
        ],
    )
    fun rename(
        @PathVariable id: UUID,
        @RequestBody body: PlatformTenantRename,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
    ): ResponseEntity<Any> {
        if (body.name.isBlank() || body.name.trim().length > MAX_NAME) {
            return ResponseEntity.badRequest().body(ProblemTypes.validation(listOf(FieldProblem("name", "INVALID", "Enter a name."))))
        }
        return changed(tenants.rename(TenantId(id), body.name, ifMatchVersion(ifMatch)))
    }

    @PostMapping("/{id}/suspend")
    @Operation(
        operationId = "suspendPlatformTenant",
        summary = "Suspend: the host refuses every request, sessions end, jobs wait",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "OK",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = PlatformTenantResponse::class))],
            ),
        ],
    )
    fun suspend(
        @PathVariable id: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
    ): ResponseEntity<Any> = changed(tenants.suspend(TenantId(id), ifMatchVersion(ifMatch)))

    @PostMapping("/{id}/resume")
    @Operation(
        operationId = "resumePlatformTenant",
        summary = "Resume a suspended tenant",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "OK",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = PlatformTenantResponse::class))],
            ),
        ],
    )
    fun resume(
        @PathVariable id: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
    ): ResponseEntity<Any> = changed(tenants.resume(TenantId(id), ifMatchVersion(ifMatch)))

    @PostMapping("/{id}/first-admin")
    @Operation(
        operationId = "invitePlatformTenantFirstAdmin",
        summary = "Invite (or re-invite) the tenant's first administrator",
        responses = [
            ApiResponse(responseCode = "204", description = "Invitation queued"),
            ApiResponse(responseCode = "409", description = "The tenant already has an active administrator"),
        ],
    )
    fun inviteFirstAdmin(
        @PathVariable id: UUID,
        @RequestBody body: PlatformFirstAdmin,
    ): ResponseEntity<Any> {
        if (!plausibleEmail(body.email) || body.displayName.isBlank() || body.displayName.length > MAX_NAME) {
            return ResponseEntity
                .badRequest()
                .body(ProblemTypes.validation(listOf(FieldProblem("email", "INVALID", "Enter a name and an email address."))))
        }
        tenants.inviteFirstAdmin(TenantId(id), body.email.trim(), body.displayName.trim())
        return ResponseEntity.noContent().build()
    }

    private fun changed(tenant: TenantSummary): ResponseEntity<Any> =
        ResponseEntity.ok().eTag(etagOf(tenant.version)).body(tenant.toResponse())
}

/** The controller's own problems as Problem Details; ahead of the global advice, whose catch-all would win otherwise. */
@RestControllerAdvice(assignableTypes = [PlatformTenantsController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class PlatformTenantsProblems {
    @ExceptionHandler(SlugRejectedException::class)
    fun slugRejected(ex: SlugRejectedException): ResponseEntity<Any> =
        ResponseEntity.badRequest().body(
            ProblemTypes.validation(listOf(FieldProblem("slug", ex.problem.name, SLUG_MESSAGES.getValue(ex.problem.name)))),
        )

    @ExceptionHandler(UnknownTenantException::class)
    fun unknown(): ResponseEntity<Any> = ResponseEntity.notFound().build()

    @ExceptionHandler(TenantHasAdminException::class)
    fun hasAdmin(): ResponseEntity<Any> =
        ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(ProblemTypes.of(HttpStatus.CONFLICT, ProblemTypes.CONFLICT, "The organisation already has an active administrator."))
}

private fun problems(body: PlatformTenantCreation): List<FieldProblem> =
    buildList {
        if (body.name.isBlank() || body.name.trim().length > MAX_NAME) add(FieldProblem("name", "INVALID", "Enter a name."))
        if (body.locale !in LOCALES) add(FieldProblem("locale", "INVALID", "Choose a language."))
        if (!validZone(body.timeZone)) add(FieldProblem("timeZone", "INVALID", "Choose a time zone."))
        if (!plausibleEmail(body.adminEmail)) add(FieldProblem("adminEmail", "INVALID", "Enter an email address."))
        if (body.adminName.isBlank() || body.adminName.length > MAX_NAME) add(FieldProblem("adminName", "INVALID", "Enter a name."))
    }

private fun validZone(zone: String) =
    zone.length <= MAX_ZONE &&
        try {
            ZoneId.of(zone)
            true
        } catch (_: DateTimeException) {
            false
        }

/** A rough shape check; the invitation proves the address. */
private fun plausibleEmail(email: String): Boolean {
    val trimmed = email.trim()
    val at = trimmed.indexOf('@')
    return trimmed.length in MIN_EMAIL..MAX_EMAIL && at > 0 && at == trimmed.lastIndexOf('@') && at < trimmed.length - 1
}

internal fun TenantSummary.toResponse(hasAdmin: Boolean? = null) =
    PlatformTenantResponse(id.value, slug, name, status, version, createdAt, statusChangedAt, hasAdmin)

private const val MAX_PAGE = 200
private const val MAX_QUERY = 100
private const val MAX_NAME = 200
private const val MAX_ZONE = 64
private const val MIN_EMAIL = 3
private const val MAX_EMAIL = 320
private val LOCALES = setOf("en", "ar")
private val SLUG_MESSAGES =
    mapOf(
        "FORMAT" to "Use 3 to 63 lower-case letters, digits and hyphens, not starting or ending with a hyphen.",
        "RESERVED" to "This address is reserved.",
        "TAKEN" to "Another organisation uses this address.",
    )
