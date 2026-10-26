package com.memo.identity.auth

import com.memo.audit.AuditLog
import com.memo.common.ratelimit.RateLimit
import com.memo.common.ratelimit.RateLimiter
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.ProblemTypes
import com.memo.identity.UserAccount
import com.memo.identity.UserAccounts
import com.memo.identity.UserId
import com.memo.identity.mfa.MfaService
import com.memo.identity.session.Factor
import com.memo.identity.session.SessionPrincipal
import com.memo.identity.session.SessionStore
import com.memo.tenancy.TenantRegistry
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
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock

@Schema(name = "CodeRequest")
data class CodeRequest(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "6-digit TOTP code, or a recovery code", maxLength = 32)
    val code: String,
)

@Schema(name = "MfaEnrolment", description = "Shown once while enrolling; the secret never appears again.")
data class EnrolmentResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Base32 secret for manual entry")
    val secret: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val otpauthUri: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "QR code of otpauthUri as SVG markup")
    val qrSvg: String,
)

@Schema(name = "MfaEnrolmentConfirmed")
data class EnrolmentConfirmedResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Shown once; each code works once")
    val recoveryCodes: List<String>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val session: SessionResponse,
)

/** The second sign-in step and TOTP enrolment (ADR-0024). Each success adds `FACTOR_TOTP` under a new session id. */
@RestController
@RequestMapping("/api/v1/auth/mfa")
@Tag(name = "auth")
class MfaController(
    private val mfa: MfaService,
    private val accounts: UserAccounts,
    private val registry: TenantRegistry,
    private val transactions: TenantTransactions,
    private val store: SessionStore,
    private val views: SessionViews,
    private val limiter: RateLimiter,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    @PostMapping
    @Operation(
        operationId = "verifyMfa",
        summary = "Second sign-in step",
        description = "A TOTP code (each usable once) or an unused recovery code. Rate-limited per user.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "Factor added; see state",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = SessionResponse::class))],
            ),
            ApiResponse(
                responseCode = "401",
                description = "Invalid code",
                content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = ProblemDetail::class))],
            ),
            ApiResponse(
                responseCode = "429",
                description = "Too many codes; see Retry-After",
                content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = ProblemDetail::class))],
            ),
        ],
    )
    fun verify(
        @AuthenticationPrincipal principal: SessionPrincipal,
        @RequestBody body: CodeRequest,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        limiter.consume(RateLimit.OTP_PER_USER, principal.indexName)
        val verified = inTenant { account(principal).let { it.status.canSignIn && mfa.verify(it, body.code.take(MAX_CODE)) } }
        return if (verified) addTotp(principal, request) else invalidCode()
    }

    @PostMapping("/enrolment")
    @Operation(
        operationId = "startMfaEnrolment",
        summary = "Start TOTP enrolment",
        description =
            "During sign-in when enrolment is required, or from a complete session to switch devices. " +
                "Confirm with a code to activate.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "Enrolment started",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = EnrolmentResponse::class))],
            ),
            ApiResponse(responseCode = "403", description = "Already enrolled and the session is not complete", content = [Content()]),
        ],
    )
    fun startEnrolment(
        @AuthenticationPrincipal principal: SessionPrincipal,
    ): ResponseEntity<Any> =
        inTenant {
            val account = account(principal)
            if (!mayEnrol(principal, account)) {
                forbidden()
            } else {
                val enrolment = mfa.start(account, registry.current()?.name ?: ISSUER)
                ResponseEntity
                    .ok()
                    .cacheControl(
                        CacheControl.noStore(),
                    ).body(EnrolmentResponse(enrolment.secret, enrolment.otpauthUri, enrolment.qrSvg))
            }
        }

    @PostMapping("/enrolment/confirm")
    @Operation(
        operationId = "confirmMfaEnrolment",
        summary = "Confirm TOTP enrolment",
        description = "Activates MFA, returns recovery codes once, and completes the sign-in step.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "Enrolled",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = EnrolmentConfirmedResponse::class))],
            ),
            ApiResponse(
                responseCode = "401",
                description = "Invalid code",
                content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = ProblemDetail::class))],
            ),
        ],
    )
    fun confirmEnrolment(
        @AuthenticationPrincipal principal: SessionPrincipal,
        @RequestBody body: CodeRequest,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        limiter.consume(RateLimit.OTP_PER_USER, principal.indexName)
        val codes =
            inTenant {
                val account = account(principal)
                if (mayEnrol(principal, account)) mfa.confirm(account, body.code.take(MAX_CODE)) else null
            }
        if (codes == null) return invalidCode()
        val upgraded = upgrade(principal, request)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(EnrolmentConfirmedResponse(codes, views.of(upgraded)))
    }

    /**
     * Enrolling replaces the second factor, so it needs either no factor yet (first enrolment during sign-in) or a
     * complete session: a stolen password alone must never re-enrol an enrolled account.
     */
    private fun mayEnrol(
        principal: SessionPrincipal,
        account: UserAccount,
    ): Boolean = account.status.canSignIn && (principal.sessionComplete || !mfa.isEnrolled(account.id))

    private fun addTotp(
        principal: SessionPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<Any> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(views.of(upgrade(principal, request)))

    private fun upgrade(
        principal: SessionPrincipal,
        request: HttpServletRequest,
    ): SessionPrincipal {
        val upgraded = principal.withFactor(Factor.TOTP, clock.instant())
        store.upgrade(request, upgraded)
        inTenant {
            audit.record(
                "identity.session.factor_added",
                "identity.user",
                principal.userId.toString(),
                after =
                    mapOf("factor" to "TOTP"),
            )
        }
        return upgraded
    }

    private fun account(principal: SessionPrincipal): UserAccount =
        requireNotNull(accounts.find(UserId(principal.userId))) {
            "session of an unknown user"
        }

    private fun <T> inTenant(block: () -> T): T = transactions.inTenant(TenantContext.current(), block)

    private fun invalidCode(): ResponseEntity<Any> =
        ResponseEntity
            .status(HttpStatus.UNAUTHORIZED)
            .body(ProblemTypes.of(HttpStatus.UNAUTHORIZED, ProblemTypes.INVALID_CREDENTIALS, "The code is not valid."))

    private fun forbidden(): ResponseEntity<Any> =
        ResponseEntity
            .status(
                HttpStatus.FORBIDDEN,
            ).body(ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Sign in completely to change the authenticator."))

    private companion object {
        const val MAX_CODE = 32
        const val ISSUER = "memo"
    }
}
