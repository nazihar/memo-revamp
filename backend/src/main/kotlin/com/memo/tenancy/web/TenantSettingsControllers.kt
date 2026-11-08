package com.memo.tenancy.web

import com.memo.common.authz.Permission
import com.memo.common.authz.RequiresPermission
import com.memo.common.authz.SignedIn
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.FieldProblem
import com.memo.common.web.ProblemTypes
import com.memo.common.web.etagOf
import com.memo.common.web.ifMatchVersion
import com.memo.tenancy.TenantSettings
import com.memo.tenancy.TermGender
import com.memo.tenancy.TermKey
import com.memo.tenancy.TermOverride
import com.memo.tenancy.Terminology
import com.memo.tenancy.TermsRejectedException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.DateTimeException
import java.time.ZoneId

@Schema(name = "TenantPreferences", description = "The organisation's default language and time zone.")
data class TenantPreferencesBody(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "en or ar")
    val locale: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "IANA time zone")
    val timeZone: String,
)

@Schema(name = "TenantPreferencesView")
data class TenantPreferencesView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val preferences: TenantPreferencesBody,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Send back as If-Match")
    val version: Long,
)

@Schema(name = "Term")
data class TermBody(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "unit, position or memo")
    val term: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 40, description = "Singular, as used mid-sentence")
    val one: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 40, description = "Plural, as used mid-sentence")
    val other: String,
    @field:Schema(description = "masculine or feminine; required for Arabic")
    val gender: String? = null,
)

@Schema(name = "Terms", description = "The organisation's own words for this language; terms not listed use the defaults.")
data class TermsView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val locale: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val terms: List<TermBody>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Send back as If-Match")
    val version: Long,
)

@Schema(name = "TermsUpdate")
data class TermsUpdate(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val locale: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val terms: List<TermBody>,
)

/** Admin → Language and terms: default language and time zone (design E07 §4). */
@RestController
@RequiresPermission(Permission.TENANT_SETTINGS_MANAGE)
@RequestMapping("/api/v1/admin/preferences")
@Tag(name = "admin-settings")
class TenantPreferencesController(
    private val transactions: TenantTransactions,
    private val settings: TenantSettings,
) {
    @GetMapping
    @Operation(operationId = "getTenantPreferences", summary = "Default language and time zone")
    fun get(): ResponseEntity<TenantPreferencesView> = view(transactions.readInTenant(TenantContext.current()) { read() })

    @PutMapping
    @Operation(
        operationId = "updateTenantPreferences",
        summary = "Change the default language and time zone (If-Match)",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "OK",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = TenantPreferencesView::class))],
            ),
        ],
    )
    fun update(
        @RequestBody body: TenantPreferencesBody,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
    ): ResponseEntity<Any> {
        val expected = ifMatchVersion(ifMatch)
        val problems =
            buildList {
                if (body.locale !in Terminology.LOCALES) add(FieldProblem("locale", "INVALID", "Choose a language."))
                if (!validZone(body.timeZone)) add(FieldProblem("timeZone", "INVALID", "Choose a time zone."))
            }
        if (problems.isNotEmpty()) return ResponseEntity.badRequest().body(ProblemTypes.validation(problems))
        val saved =
            transactions.inTenant(TenantContext.current()) {
                if (read().version != expected) throw ConcurrentModificationException("preferences", expected)
                mapOf(LOCALE to body.locale, TIME_ZONE to body.timeZone).forEach { (key, value) ->
                    if (settings.get(key)?.value != value) settings.put(key, value)
                }
                read()
            }
        return ResponseEntity.ok().eTag(etagOf(saved.version)).body(saved)
    }

    private fun read(): TenantPreferencesView {
        val rows = listOfNotNull(settings.get(LOCALE), settings.get(TIME_ZONE))
        return TenantPreferencesView(
            TenantPreferencesBody(settings.get(LOCALE)?.value ?: DEFAULT_LOCALE, settings.get(TIME_ZONE)?.value ?: DEFAULT_ZONE),
            rows.sumOf { it.version + 1 },
        )
    }

    private fun view(view: TenantPreferencesView) =
        ResponseEntity
            .ok()
            .eTag(etagOf(view.version))
            .cacheControl(CacheControl.noStore())
            .body(view)

    private fun validZone(zone: String) =
        zone.length <= MAX_ZONE &&
            try {
                ZoneId.of(zone)
                true
            } catch (_: DateTimeException) {
                false
            }

    companion object {
        const val LOCALE = "locale.default"
        const val TIME_ZONE = "default.timezone"
        private const val DEFAULT_LOCALE = "en"
        private const val DEFAULT_ZONE = "UTC"
        private const val MAX_ZONE = 64
    }
}

/** The organisation's terms for a language, as every screen needs them (ADR-0032). */
@RestController
@SignedIn
@RequestMapping("/api/v1/terms")
@Tag(name = "terms")
class TermsController(
    private val transactions: TenantTransactions,
    private val terminology: Terminology,
) {
    @GetMapping
    @Operation(operationId = "getTerms", summary = "The organisation's own words for a language (overrides only)")
    fun get(
        @RequestParam locale: String,
    ): ResponseEntity<TermsView> {
        if (locale !in Terminology.LOCALES) return ResponseEntity.notFound().build()
        val view = transactions.readInTenant(TenantContext.current()) { termsView(terminology, locale) }
        return ResponseEntity
            .ok()
            .eTag(etagOf(view.version))
            .cacheControl(CacheControl.noCache())
            .body(view)
    }
}

/** Admin → Language and terms: the organisation's words per language (ADR-0032). */
@RestController
@RequiresPermission(Permission.TENANT_SETTINGS_MANAGE)
@RequestMapping("/api/v1/admin/terms")
@Tag(name = "admin-settings")
class AdminTermsController(
    private val transactions: TenantTransactions,
    private val terminology: Terminology,
) {
    @PutMapping
    @Operation(
        operationId = "updateTerms",
        summary = "Replace a language's terms; terms left out go back to the defaults (If-Match)",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "OK",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = TermsView::class))],
            ),
        ],
    )
    fun update(
        @RequestBody body: TermsUpdate,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
    ): ResponseEntity<Any> {
        val expected = ifMatchVersion(ifMatch)
        val keys = body.terms.map { TermKey.ofKey(it.term) }
        val unknown =
            keys.withIndex().filter { it.value == null }.map {
                FieldProblem(
                    "terms[${it.index}].term",
                    "UNKNOWN",
                    "Unknown term.",
                )
            }
        val badGender =
            body.terms.withIndex().filter { it.value.gender != null && TermGender.ofKey(it.value.gender) == null }.map {
                FieldProblem("terms[${it.index}].gender", "INVALID", "Choose masculine or feminine.")
            }
        val problems =
            unknown + badGender +
                listOfNotNull(FieldProblem("locale", "INVALID", "Unsupported language.").takeIf { body.locale !in Terminology.LOCALES })
        if (problems.isNotEmpty()) return ResponseEntity.badRequest().body(ProblemTypes.validation(problems))
        val wanted =
            body.terms.mapIndexed { i, t ->
                TermOverride(keys[i]!!, t.one, t.other, TermGender.ofKey(t.gender).takeIf { body.locale in Terminology.GENDERED })
            }
        val view =
            transactions.inTenant(TenantContext.current()) {
                terminology.replace(body.locale, wanted, expected)
                termsView(terminology, body.locale)
            }
        return ResponseEntity.ok().eTag(etagOf(view.version)).body(view)
    }
}

/** Term validation problems as the forms' field errors. */
@RestControllerAdvice(assignableTypes = [AdminTermsController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class TermsProblems {
    @ExceptionHandler(TermsRejectedException::class)
    fun rejected(ex: TermsRejectedException): ResponseEntity<Any> =
        ResponseEntity.badRequest().body(
            ProblemTypes.validation(
                ex.problems.map { (field, code) -> FieldProblem(field, code, "Use 1 to 40 characters, without { } < > or #.") },
            ),
        )
}

private fun termsView(
    terminology: Terminology,
    locale: String,
) = TermsView(
    locale,
    terminology.overrides(locale).map { TermBody(it.term.key, it.one, it.other, it.gender?.key) },
    terminology.version(locale),
)
