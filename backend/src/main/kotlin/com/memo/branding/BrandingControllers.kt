package com.memo.branding

import com.memo.common.authz.Permission
import com.memo.common.authz.PublicEndpoint
import com.memo.common.authz.RequiresPermission
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.FieldProblem
import com.memo.common.web.ProblemTypes
import com.memo.common.web.etagOf
import com.memo.common.web.ifMatchVersion
import com.memo.files.FileContents
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.Duration
import java.util.Locale
import java.util.UUID

@Schema(name = "Branding", description = "How the organisation's pages look: name, colours and logo (design E07 §6).")
data class BrandingResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val organisation: String,
    @field:Schema(description = "#rrggbb for the light theme; absent: the default")
    val colourLight: String? = null,
    @field:Schema(description = "#rrggbb for the dark theme; absent: the default")
    val colourDark: String? = null,
    @field:Schema(description = "Present when a logo is set; changes when the logo changes (use in the image URL)")
    val logoVersion: String? = null,
    @field:Schema(description = "Admin view only: the logo file, for replacing it")
    val logoFileId: UUID? = null,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val version: Long,
)

@Schema(name = "BrandingUpdate")
data class BrandingUpdate(
    val colourLight: String? = null,
    val colourDark: String? = null,
    @field:Schema(description = "A file uploaded with purpose tenant.logo; absent removes the logo")
    val logoFileId: UUID? = null,
)

/** Branding for every page, the sign-in page included (no session needed on the tenant's host). */
@RestController
@PublicEndpoint
@RequestMapping("/api/v1/branding")
@Tag(name = "branding")
class BrandingController(
    private val transactions: TenantTransactions,
    private val branding: Branding,
    private val contents: FileContents,
) {
    @GetMapping
    @Operation(operationId = "getBranding", summary = "The organisation's name, colours and logo version")
    fun get(): ResponseEntity<BrandingResponse> {
        val view = transactions.readInTenant(TenantContext.current()) { branding.view() }
        val logo = view.logoFileId?.let { contents.current(it, TenantLogoPurpose.KEY) }
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noCache())
            .body(
                BrandingResponse(
                    view.organisation,
                    view.colourLight,
                    view.colourDark,
                    logo?.sha256?.take(VERSION_CHARS),
                    null,
                    view.version,
                ),
            )
    }

    @GetMapping("/logo")
    @Operation(
        operationId = "getBrandingLogo",
        summary = "The organisation's logo",
        responses = [
            ApiResponse(responseCode = "200", description = "PNG, JPEG or WebP", content = [Content(mediaType = "image/*")]),
            ApiResponse(responseCode = "404", description = "No logo"),
        ],
    )
    fun logo(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        val fileId = transactions.readInTenant(TenantContext.current()) { branding.view().logoFileId }
        val version = fileId?.let { contents.current(it, TenantLogoPurpose.KEY) }
        if (version == null) {
            response.status = HttpServletResponse.SC_NOT_FOUND
            return
        }
        val etag = "\"${version.sha256.take(VERSION_CHARS)}\""
        response.setHeader(HttpHeaders.ETAG, etag)
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.maxAge(CACHE_FOR).cachePublic().headerValue)
        response.setHeader("X-Content-Type-Options", "nosniff")
        response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'; sandbox")
        if (request.getHeader(HttpHeaders.IF_NONE_MATCH)?.split(',')?.any { it.trim() == etag } == true) {
            response.status = HttpServletResponse.SC_NOT_MODIFIED
            return
        }
        response.contentType = version.mediaType.lowercase(Locale.ROOT)
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "inline")
        response.setContentLengthLong(version.sizeBytes)
        contents.write(version, response.outputStream)
    }

    private companion object {
        const val VERSION_CHARS = 16
        val CACHE_FOR: Duration = Duration.ofMinutes(5)
    }
}

/** Admin → Branding (design E07 §4, §6). */
@RestController
@RequiresPermission(Permission.TENANT_SETTINGS_MANAGE)
@RequestMapping("/api/v1/admin/branding")
@Tag(name = "admin-settings")
class AdminBrandingController(
    private val transactions: TenantTransactions,
    private val branding: Branding,
) {
    @GetMapping
    @Operation(operationId = "getAdminBranding", summary = "Colours and logo file, for editing")
    fun get(): ResponseEntity<BrandingResponse> = respond(transactions.readInTenant(TenantContext.current()) { branding.view() })

    @PutMapping
    @Operation(
        operationId = "updateBranding",
        summary = "Change colours and logo (If-Match); colours must keep their contrast",
        responses = [
            ApiResponse(
                responseCode = "400",
                description = "Not a colour, too little contrast (with the ratio), or not a logo file",
            ),
        ],
    )
    fun update(
        @RequestBody body: BrandingUpdate,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
    ): ResponseEntity<BrandingResponse> {
        val expected = ifMatchVersion(ifMatch)
        val saved =
            transactions.inTenant(TenantContext.current()) {
                if (branding.view().version != expected) throw ConcurrentModificationException("branding", expected)
                branding.update(body.colourLight, body.colourDark, body.logoFileId)
            }
        return respond(saved)
    }

    private fun respond(view: BrandingView) =
        ResponseEntity
            .ok()
            .eTag(etagOf(view.version))
            .cacheControl(CacheControl.noStore())
            .body(BrandingResponse(view.organisation, view.colourLight, view.colourDark, null, view.logoFileId, view.version))
}

/** Branding refusals as field problems; a contrast problem carries the measured ratio for the form. */
@RestControllerAdvice(assignableTypes = [AdminBrandingController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class BrandingProblems {
    @ExceptionHandler(BrandingRejectedException::class)
    fun rejected(ex: BrandingRejectedException): ResponseEntity<Any> {
        val problems =
            ex.invalidFields.map { FieldProblem(it, "INVALID", "Use a #rrggbb colour or an uploaded logo.") } +
                ex.problems.map {
                    FieldProblem(
                        it.field,
                        "LOW_CONTRAST",
                        "Contrast %.2f:1 against %s; at least %.1f:1 is needed.".format(Locale.ROOT, it.ratio, it.against, it.minimum),
                    )
                }
        return ResponseEntity.badRequest().body(ProblemTypes.validation(problems))
    }
}
