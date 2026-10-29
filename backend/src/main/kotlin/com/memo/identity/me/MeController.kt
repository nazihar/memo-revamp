package com.memo.identity.me

import com.memo.common.authz.SignedIn
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.FieldProblem
import com.memo.common.web.ProblemTypes
import com.memo.identity.PasswordRejectedException
import com.memo.identity.PasswordReset
import com.memo.identity.StepUp
import com.memo.identity.UserAccounts
import com.memo.identity.UserId
import com.memo.identity.mfa.MfaService
import com.memo.identity.password.PasswordProblems
import com.memo.identity.session.SessionPrincipal
import com.memo.identity.session.SessionRevocation
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@Schema(name = "Me")
data class MeResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val email: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val displayName: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val tenantAdmin: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val mfaEnrolled: Boolean,
    val lastLoginAt: Instant? = null,
)

@Schema(name = "ChangePasswordRequest")
data class ChangePasswordRequest(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 1024, format = "password")
    val currentPassword: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 1024, format = "password")
    val newPassword: String,
)

@Schema(name = "RecoveryCodes")
data class RecoveryCodesResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Shown once; the previous codes stop working")
    val recoveryCodes: List<String>,
)

@Schema(name = "MySession")
data class MySessionResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Opaque handle (never the session id)")
    val id: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val current: Boolean,
    val ipAddress: String? = null,
    val userAgent: String? = null,
    val signedInAt: Instant? = null,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val lastActiveAt: Instant,
)

/** The signed-in user's own account (design E03 §3). Password and recovery-code changes need a step-up (ADR-0024). */
@RestController
@SignedIn
@RequestMapping("/api/v1/me")
@Tag(name = "me")
class MeController(
    private val accounts: UserAccounts,
    private val mfa: MfaService,
    private val transactions: TenantTransactions,
    private val stepUp: StepUp,
    private val sessions: SessionRevocation,
    private val reset: PasswordReset,
) {
    @GetMapping
    @Operation(operationId = "getMe", summary = "My account")
    fun me(
        @AuthenticationPrincipal principal: SessionPrincipal,
    ): ResponseEntity<MeResponse> =
        inTenant {
            val account = requireNotNull(accounts.find(UserId(principal.userId)))
            val body =
                MeResponse(
                    account.id.value,
                    account.email,
                    account.displayName,
                    accounts.isTenantAdmin(account.id),
                    mfa.isEnrolled(account.id),
                    account.lastLoginAt,
                )
            ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)
        }

    @PutMapping("/password")
    @Operation(
        operationId = "changeMyPassword",
        summary = "Change my password",
        description = "Needs a recent step-up. Signs out every other session of the account.",
        responses = [
            ApiResponse(responseCode = "204", description = "Changed", content = [Content()]),
            ApiResponse(
                responseCode = "400",
                description =
                    "Current password wrong, or the new one breaks the policy " +
                        "(errors[].code: TOO_SHORT, TOO_LONG, BREACHED, CONTAINS_IDENTITY, INCORRECT)",
                content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = ProblemDetail::class))],
            ),
        ],
    )
    fun changePassword(
        @AuthenticationPrincipal principal: SessionPrincipal,
        @RequestBody body: ChangePasswordRequest,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        stepUp.require()
        val problem =
            inTenant {
                val id = UserId(principal.userId)
                when {
                    !accounts.passwordMatches(id, body.currentPassword.take(MAX_PASSWORD)) -> {
                        ProblemTypes.validation(
                            listOf(FieldProblem("currentPassword", "INCORRECT", "The current password is not correct.")),
                        )
                    }

                    else -> {
                        try {
                            reset.notifyPasswordChanged(accounts.setPassword(id, body.newPassword.take(MAX_PASSWORD + 1)))
                            null
                        } catch (e: PasswordRejectedException) {
                            ProblemTypes.validation(PasswordProblems.of("newPassword", e.violations))
                        }
                    }
                }
            }
        if (problem != null) return ResponseEntity.badRequest().body(problem)
        sessions.revokeAll(principal.indexName, keepSessionId = request.getSession(false)?.id)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/recovery-codes")
    @Operation(
        operationId = "regenerateMyRecoveryCodes",
        summary = "New recovery codes",
        description = "Needs a recent step-up.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "New codes",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = RecoveryCodesResponse::class))],
            ),
            ApiResponse(responseCode = "409", description = "MFA is not set up", content = [Content()]),
        ],
    )
    fun regenerateRecoveryCodes(
        @AuthenticationPrincipal principal: SessionPrincipal,
    ): ResponseEntity<Any> {
        stepUp.require()
        return inTenant {
            val id = UserId(principal.userId)
            if (mfa.isEnrolled(id)) {
                ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(RecoveryCodesResponse(mfa.regenerateRecoveryCodes(id)))
            } else {
                ResponseEntity
                    .status(
                        HttpStatus.CONFLICT,
                    ).body(ProblemTypes.of(HttpStatus.CONFLICT, ProblemTypes.CONFLICT, "MFA is not set up."))
            }
        }
    }

    @GetMapping("/sessions")
    @Operation(
        operationId = "listMySessions",
        summary = "My sessions",
        description = "Every signed-in device of my account; the current one is marked.",
    )
    fun mySessions(
        @AuthenticationPrincipal principal: SessionPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<List<MySessionResponse>> {
        val current = request.getSession(false)?.id?.let { SessionRevocation.handle(it) }
        val body =
            sessions
                .list(principal.indexName)
                .sortedByDescending { it.lastAccessedAt }
                .map {
                    MySessionResponse(
                        it.handle,
                        it.handle == current,
                        it.client?.ip,
                        it.client?.userAgent,
                        it.client?.signedInAt,
                        it.lastAccessedAt,
                    )
                }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)
    }

    @DeleteMapping("/sessions/{id}")
    @Operation(
        operationId = "revokeMySession",
        summary = "Sign out one of my sessions",
        responses = [
            ApiResponse(
                responseCode = "204",
                description = "Signed out",
            ), ApiResponse(responseCode = "404", description = "No such session"),
        ],
    )
    fun revokeSession(
        @AuthenticationPrincipal principal: SessionPrincipal,
        @PathVariable id: String,
    ): ResponseEntity<Void> =
        if (sessions.revoke(principal.indexName, id)) ResponseEntity.noContent().build() else ResponseEntity.notFound().build()

    private fun <T> inTenant(block: () -> T): T = transactions.inTenant(TenantContext.current(), block)

    private companion object {
        const val MAX_PASSWORD = 1024
    }
}
