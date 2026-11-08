package com.memo.identity.admin

import com.memo.common.authz.Permission
import com.memo.common.authz.RequiresPermission
import com.memo.common.authz.StepUpCheck
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.FieldProblem
import com.memo.common.web.ProblemTypes
import com.memo.common.web.etagOf
import com.memo.common.web.ifMatchVersion
import com.memo.identity.SecurityLimit
import com.memo.identity.SecuritySettings
import com.memo.tenancy.TenantSettings
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Schema(name = "SecuritySettings", description = "The organisation's sign-in and lockout rules (design E07 §2).")
data class SecuritySettingsBody(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "all, or admins (administrators always need MFA)")
    val mfaRequired: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val idleMinutes: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val absoluteHours: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val stepUpMinutes: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val passwordMinLength: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Failures before each attempt has to wait")
    val throttleAfter: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Failures in a row that lock the account")
    val lockAfter: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val lockMinutes: Int,
)

@Schema(name = "SecuritySettingLimit")
data class SecuritySettingLimit(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val field: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val min: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val max: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val default: Int,
)

@Schema(name = "SecuritySettingsView")
data class SecuritySettingsView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val settings: SecuritySettingsBody,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val limits: List<SecuritySettingLimit>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Send back as If-Match")
    val version: Long,
)

/** Admin → Security (design E07 §4): read and change the tenant's security settings; changes need a fresh step-up. */
@RestController
@RequiresPermission(Permission.TENANT_SETTINGS_MANAGE)
@RequestMapping("/api/v1/admin/security-settings")
@Tag(name = "admin-settings")
class SecuritySettingsController(
    private val transactions: TenantTransactions,
    private val security: SecuritySettings,
    private val settings: TenantSettings,
    private val stepUp: StepUpCheck,
) {
    @GetMapping
    @Operation(operationId = "getSecuritySettings", summary = "Security settings with their allowed ranges")
    fun get(): ResponseEntity<SecuritySettingsView> = view(transactions.readInTenant(TenantContext.current()) { read() })

    @PutMapping
    @Operation(
        operationId = "updateSecuritySettings",
        summary = "Change security settings (If-Match, step-up)",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "OK",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = SecuritySettingsView::class))],
            ),
            ApiResponse(responseCode = "400", description = "A value is outside its range"),
            ApiResponse(responseCode = "401", description = "Step-up required"),
            ApiResponse(responseCode = "409", description = "Changed meanwhile"),
        ],
    )
    fun update(
        @RequestBody body: SecuritySettingsBody,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
    ): ResponseEntity<Any> {
        val expected = ifMatchVersion(ifMatch)
        stepUp.require()
        val problems = problems(body)
        if (problems.isNotEmpty()) return ResponseEntity.badRequest().body(ProblemTypes.validation(problems))
        val saved =
            transactions.inTenant(TenantContext.current()) {
                if (security.version() != expected) throw ConcurrentModificationException("security settings", expected)
                // Only values that differ from the effective ones (defaults included) are written: the audit shows real changes.
                val effective = values(read().settings)
                values(body).forEach { (key, value) -> if (effective[key] != value) settings.put(key, value) }
                read()
            }
        return ResponseEntity.ok().eTag(etagOf(saved.version)).body(saved)
    }

    private fun read(): SecuritySettingsView =
        SecuritySettingsView(
            SecuritySettingsBody(
                mfaRequired = security.mfaPolicy(),
                idleMinutes = security.int(SecurityLimit.IDLE_MINUTES),
                absoluteHours = security.int(SecurityLimit.ABSOLUTE_HOURS),
                stepUpMinutes = security.int(SecurityLimit.STEP_UP_MINUTES),
                passwordMinLength = security.int(SecurityLimit.PASSWORD_MIN_LENGTH),
                throttleAfter = security.int(SecurityLimit.THROTTLE_AFTER),
                lockAfter = security.int(SecurityLimit.LOCK_AFTER),
                lockMinutes = security.int(SecurityLimit.LOCK_MINUTES),
            ),
            FIELDS.map { (field, limit) -> SecuritySettingLimit(field, limit.range.first, limit.range.last, limit.default) },
            security.version(),
        )

    private fun view(view: SecuritySettingsView): ResponseEntity<SecuritySettingsView> =
        ResponseEntity
            .ok()
            .eTag(etagOf(view.version))
            .cacheControl(CacheControl.noStore())
            .body(view)

    private fun problems(body: SecuritySettingsBody): List<FieldProblem> =
        buildList {
            if (body.mfaRequired !in setOf(SecuritySettings.MFA_ALL, SecuritySettings.MFA_ADMINS_ONLY)) {
                add(FieldProblem("mfaRequired", "INVALID", "Choose who must use two-step sign-in."))
            }
            val numbers = numbers(body)
            FIELDS.forEach { (field, limit) ->
                if (numbers.getValue(field) !in limit.range) {
                    add(FieldProblem(field, "OUT_OF_RANGE", "Enter a number from ${limit.range.first} to ${limit.range.last}."))
                }
            }
            if (body.lockAfter <= body.throttleAfter) {
                add(FieldProblem("lockAfter", "NOT_ABOVE_THROTTLE", "Lock after more failures than the waiting starts."))
            }
        }

    private fun numbers(body: SecuritySettingsBody) =
        mapOf(
            "idleMinutes" to body.idleMinutes,
            "absoluteHours" to body.absoluteHours,
            "stepUpMinutes" to body.stepUpMinutes,
            "passwordMinLength" to body.passwordMinLength,
            "throttleAfter" to body.throttleAfter,
            "lockAfter" to body.lockAfter,
            "lockMinutes" to body.lockMinutes,
        )

    private fun values(body: SecuritySettingsBody): Map<String, String> =
        numbers(body).mapKeys { (field, _) -> FIELDS.getValue(field).key }.mapValues { it.value.toString() } +
            (SecuritySettings.MFA_REQUIRED to body.mfaRequired)

    private companion object {
        val FIELDS =
            linkedMapOf(
                "idleMinutes" to SecurityLimit.IDLE_MINUTES,
                "absoluteHours" to SecurityLimit.ABSOLUTE_HOURS,
                "stepUpMinutes" to SecurityLimit.STEP_UP_MINUTES,
                "passwordMinLength" to SecurityLimit.PASSWORD_MIN_LENGTH,
                "throttleAfter" to SecurityLimit.THROTTLE_AFTER,
                "lockAfter" to SecurityLimit.LOCK_AFTER,
                "lockMinutes" to SecurityLimit.LOCK_MINUTES,
            )
    }
}
