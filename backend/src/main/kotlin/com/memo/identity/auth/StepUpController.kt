package com.memo.identity.auth

import com.memo.audit.AuditLog
import com.memo.common.ratelimit.RateLimit
import com.memo.common.ratelimit.RateLimiter
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.ProblemTypes
import com.memo.identity.StepUp
import com.memo.identity.UserAccounts
import com.memo.identity.UserId
import com.memo.identity.mfa.MfaService
import com.memo.identity.session.Factor
import com.memo.identity.session.SessionPrincipal
import com.memo.identity.session.SessionStore
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock

@Schema(name = "StepUpRequest", description = "A TOTP or recovery code for users with MFA; the current password otherwise.")
data class StepUpRequest(
    @field:Schema(maxLength = 32)
    val code: String? = null,
    @field:Schema(maxLength = 1024, format = "password")
    val password: String? = null,
)

/** Re-confirms the user before a sensitive action (ADR-0024); the client retries the refused request after this. */
@RestController
@RequestMapping("/api/v1/auth/step-up")
@Tag(name = "auth")
class StepUpController(
    private val mfa: MfaService,
    private val accounts: UserAccounts,
    private val transactions: TenantTransactions,
    private val store: SessionStore,
    private val limiter: RateLimiter,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    @PostMapping
    @Operation(
        operationId = "stepUp",
        summary = "Confirm it is you",
        description = "Refreshes the session's strongest factor for the step-up window. Rate-limited per user.",
        responses = [
            ApiResponse(responseCode = "204", description = "Confirmed; retry the refused request", content = [Content()]),
            ApiResponse(
                responseCode = "401",
                description = "Wrong code or password",
                content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = ProblemDetail::class))],
            ),
        ],
    )
    fun stepUp(
        @AuthenticationPrincipal principal: SessionPrincipal,
        @RequestBody body: StepUpRequest,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        limiter.consume(RateLimit.OTP_PER_USER, principal.indexName)
        val factor = StepUp.strongest(principal)
        val confirmed =
            transactions.inTenant(TenantContext.current()) {
                val account = accounts.find(UserId(principal.userId))
                val ok =
                    when {
                        account == null || !account.status.canSignIn -> false
                        factor == Factor.TOTP -> body.code?.let { mfa.verify(account, it.take(MAX_CODE)) } ?: false
                        else -> body.password?.let { accounts.passwordMatches(account.id, it.take(MAX_PASSWORD)) } ?: false
                    }
                val action = if (ok) "identity.step_up.succeeded" else "identity.step_up.failed"
                audit.record(action, "identity.user", principal.userId.toString(), after = mapOf("factor" to factor.name))
                ok
            }
        if (!confirmed) {
            return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(ProblemTypes.of(HttpStatus.UNAUTHORIZED, ProblemTypes.INVALID_CREDENTIALS, "That did not match. Try again."))
        }
        store.upgrade(request, principal.withFactor(factor, clock.instant()))
        return ResponseEntity.noContent().build()
    }

    private companion object {
        const val MAX_CODE = 32
        const val MAX_PASSWORD = 1024
    }
}
