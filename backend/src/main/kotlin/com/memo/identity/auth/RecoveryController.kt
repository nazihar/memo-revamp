package com.memo.identity.auth

import com.memo.common.ratelimit.RateLimit
import com.memo.common.ratelimit.RateLimiter
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.FieldProblem
import com.memo.common.web.ProblemTypes
import com.memo.identity.Invitations
import com.memo.identity.PasswordRejectedException
import com.memo.identity.PasswordReset
import com.memo.identity.UserAccounts
import com.memo.identity.password.PasswordProblems
import com.memo.identity.session.SessionRevocation
import com.memo.identity.session.SessionStore
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.web.csrf.CsrfTokenRepository
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Schema(name = "PasswordResetRequest")
data class PasswordResetRequest(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 320)
    val email: String,
)

@Schema(name = "PasswordResetCompletion")
data class PasswordResetCompletion(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "From the emailed link (its fragment)", maxLength = 100)
    val token: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 1024, format = "password")
    val newPassword: String,
)

@Schema(name = "InvitationLookup")
data class InvitationLookup(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "From the emailed link (its fragment)", maxLength = 100)
    val token: String,
)

@Schema(name = "InvitationAcceptance")
data class InvitationAcceptance(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 100)
    val token: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 1024, format = "password")
    val password: String,
)

@Schema(name = "Invitation")
data class InvitationResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val organisation: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Masked, for example n***@bank.example")
    val email: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val displayName: String,
)

/**
 * Password reset and invitation acceptance (design E03 §3). Tokens travel only in request bodies: emailed links carry
 * them in the URL fragment, so they never appear in request lines or access logs. All endpoints are anonymous and
 * rate-limited (ADR-0025).
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "auth")
class RecoveryController(
    private val reset: PasswordReset,
    private val invitations: Invitations,
    private val transactions: TenantTransactions,
    private val limiter: RateLimiter,
    private val sessions: SessionRevocation,
    private val store: SessionStore,
    private val csrfTokens: CsrfTokenRepository,
    private val views: SessionViews,
) {
    @PostMapping("/password-reset")
    @Operation(
        operationId = "requestPasswordReset",
        summary = "Email a password reset link",
        description = "Always 202: the answer never says whether an account exists.",
        responses = [ApiResponse(responseCode = "202", description = "If the account exists, a link is on its way")],
    )
    fun requestReset(
        @RequestBody body: PasswordResetRequest,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        limiter.consume(RateLimit.PASSWORD_RESET_PER_IP, request.remoteAddr)
        val email = body.email.take(MAX_EMAIL)
        limiter.consume(RateLimit.PASSWORD_RESET_PER_ACCOUNT, "${TenantContext.current()}:${UserAccounts.normaliseEmail(email)}")
        transactions.inTenant(TenantContext.current()) { reset.request(email) }
        return ResponseEntity.accepted().build()
    }

    @PostMapping("/password-reset/complete")
    @Operation(
        operationId = "completePasswordReset",
        summary = "Set a new password from a reset link",
        description = "Signs out every session of the account. The link works once.",
        responses = [
            ApiResponse(responseCode = "204", description = "Password set; sign in with it", content = [Content()]),
            ApiResponse(
                responseCode = "400",
                description = "Invalid or expired link (errors[].field token), or a weak password (errors[].field newPassword)",
                content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = ProblemDetail::class))],
            ),
        ],
    )
    fun completeReset(
        @RequestBody body: PasswordResetCompletion,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        limiter.consume(RateLimit.PASSWORD_RESET_PER_IP, request.remoteAddr)
        val outcome =
            runCatching {
                transactions.inTenant(
                    TenantContext.current(),
                ) { reset.complete(body.token.take(MAX_TOKEN), body.newPassword.take(MAX_PASSWORD)) }
            }
        val rejected = outcome.exceptionOrNull() as? PasswordRejectedException
        outcome.exceptionOrNull()?.takeIf { rejected == null }?.let { throw it }
        val account = outcome.getOrNull()
        return when {
            rejected != null -> {
                ResponseEntity.badRequest().body(ProblemTypes.validation(PasswordProblems.of("newPassword", rejected.violations)))
            }

            account == null -> {
                invalidLink()
            }

            else -> {
                sessions.revokeAll("${TenantContext.current()}:${account.id}")
                ResponseEntity.noContent().build()
            }
        }
    }

    @PostMapping("/invitations/lookup")
    @Operation(
        operationId = "lookUpInvitation",
        summary = "Show an invitation",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "Valid invitation",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = InvitationResponse::class))],
            ),
            ApiResponse(responseCode = "404", description = "Unknown, used or expired link"),
        ],
    )
    fun lookUpInvitation(
        @RequestBody body: InvitationLookup,
        request: HttpServletRequest,
    ): ResponseEntity<InvitationResponse> {
        limiter.consume(RateLimit.INVITATION_PER_IP, request.remoteAddr)
        val details = transactions.readInTenant(TenantContext.current()) { invitations.details(body.token.take(MAX_TOKEN)) }
        return details?.let {
            ResponseEntity
                .ok()
                .cacheControl(
                    CacheControl.noStore(),
                ).body(InvitationResponse(it.organisation, it.maskedEmail, it.displayName))
        }
            ?: ResponseEntity.notFound().build()
    }

    @PostMapping("/invitations/accept")
    @Operation(
        operationId = "acceptInvitation",
        summary = "Accept an invitation",
        description = "Sets the password and signs in; the state says whether MFA enrolment follows.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "Signed in",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = SessionResponse::class))],
            ),
            ApiResponse(
                responseCode = "400",
                description = "Invalid or expired link, or a weak password",
                content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = ProblemDetail::class))],
            ),
        ],
    )
    fun acceptInvitation(
        @RequestBody body: InvitationAcceptance,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<Any> {
        limiter.consume(RateLimit.INVITATION_PER_IP, request.remoteAddr)
        val tenant = TenantContext.current()
        val outcome =
            runCatching {
                transactions.inTenant(
                    tenant,
                ) { invitations.accept(tenant, body.token.take(MAX_TOKEN), body.password.take(MAX_PASSWORD)) }
            }
        val rejected = outcome.exceptionOrNull() as? PasswordRejectedException
        outcome.exceptionOrNull()?.takeIf { rejected == null }?.let { throw it }
        val signedIn = outcome.getOrNull()
        return when {
            rejected != null -> {
                ResponseEntity.badRequest().body(ProblemTypes.validation(PasswordProblems.of("password", rejected.violations)))
            }

            signedIn == null -> {
                invalidLink()
            }

            else -> {
                store.start(request, signedIn.principal, signedIn.idleTimeout)
                csrfTokens.saveToken(null, request, response)
                ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(views.of(signedIn.principal))
            }
        }
    }

    private fun invalidLink(): ResponseEntity<Any> =
        ResponseEntity
            .badRequest()
            .body(ProblemTypes.validation(listOf(FieldProblem("token", "INVALID", "This link is no longer valid. Ask for a new one."))))

    private companion object {
        const val MAX_EMAIL = 320
        const val MAX_TOKEN = 100
        const val MAX_PASSWORD = 1024
    }
}
