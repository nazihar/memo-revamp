package com.memo.identity.auth

import com.memo.audit.AuditLog
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.ProblemTypes
import com.memo.identity.UserAccount
import com.memo.identity.UserAccounts
import com.memo.identity.UserId
import com.memo.identity.mfa.MfaEnrolments
import com.memo.identity.session.SessionPrincipal
import com.memo.identity.session.SessionStore
import com.memo.tenancy.TenantRegistry
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
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
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.security.web.csrf.CsrfTokenRepository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@Schema(name = "LoginRequest")
data class LoginRequest(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 320)
    val email: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 1024, format = "password")
    val password: String,
)

@Schema(name = "Session", description = "Who is signed in on this organisation's host, and what they must do next.")
data class SessionResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val state: SessionState,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Display name of the organisation of this host")
    val organisation: String,
    @field:Schema(description = "Present unless ANONYMOUS")
    val user: SessionUserResponse? = null,
)

@Schema(name = "SessionUser")
data class SessionUserResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val email: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val displayName: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val tenantAdmin: Boolean,
)

/** Sign-in, sign-out and session state for the SPA (ADR-0023): JSON only, no redirects, no tokens in JavaScript. */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "auth")
class AuthController(
    private val signIn: SignIn,
    private val store: SessionStore,
    private val csrfTokens: CsrfTokenRepository,
    private val transactions: TenantTransactions,
    private val accounts: UserAccounts,
    private val registry: TenantRegistry,
    private val audit: AuditLog,
    private val mfa: MfaEnrolments,
) {
    @GetMapping("/csrf")
    @Operation(
        operationId = "getCsrfToken",
        summary = "Issue the CSRF cookie",
        description =
            "Sets the XSRF-TOKEN cookie; send its value as X-XSRF-TOKEN on every mutating request. " +
                "Call on start and after sign-in or sign-out.",
        responses = [ApiResponse(responseCode = "204", description = "Cookie set")],
    )
    fun csrf(
        @Parameter(hidden = true) token: CsrfToken,
    ): ResponseEntity<Void> {
        // Reading the deferred token makes the repository write the cookie.
        token.token
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
    }

    @GetMapping("/session")
    @Operation(
        operationId = "getSession",
        summary = "Current session state",
        description = "Never 401: anonymous callers get ANONYMOUS.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "Session state",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = SessionResponse::class))],
            ),
        ],
    )
    fun session(
        @AuthenticationPrincipal principal: SessionPrincipal?,
    ): ResponseEntity<SessionResponse> {
        val body =
            transactions.readInTenant(TenantContext.current()) {
                val organisation = registry.current()?.name.orEmpty()
                val account = principal?.let { accounts.find(UserId(it.userId)) }
                if (principal == null || account == null) {
                    SessionResponse(SessionState.ANONYMOUS, organisation)
                } else {
                    SessionResponse(state(principal, account), organisation, account.toResponse())
                }
            }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)
    }

    @PostMapping("/login")
    @Operation(
        operationId = "login",
        summary = "Sign in with email and password",
        description = "Starts a new session (new id). The state says whether a TOTP code or MFA enrolment follows.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "Signed in; see state",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = SessionResponse::class))],
            ),
            ApiResponse(
                responseCode = "401",
                description = "Invalid credentials (the same answer for unknown, inactive and wrong-password cases)",
                content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = ProblemDetail::class))],
            ),
        ],
    )
    fun login(
        @RequestBody body: LoginRequest,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<Any> {
        if (!plausible(body)) return invalidCredentials()
        return when (val result = signIn.withPassword(TenantContext.current(), body.email, body.password)) {
            is SignInResult.Rejected -> {
                invalidCredentials()
            }

            is SignInResult.SignedIn -> {
                store.start(request, result.principal, result.idleTimeout)
                csrfTokens.saveToken(null, request, response)
                val organisation = transactions.readInTenant(TenantContext.current()) { registry.current()?.name.orEmpty() }
                ResponseEntity
                    .ok()
                    .cacheControl(CacheControl.noStore())
                    .body(SessionResponse(result.next, organisation, result.account.toResponse()))
            }
        }
    }

    @PostMapping("/logout")
    @Operation(
        operationId = "logout",
        summary = "Sign out",
        description = "Ends this session, complete or not.",
        responses = [ApiResponse(responseCode = "204", description = "Signed out")],
    )
    fun logout(
        @AuthenticationPrincipal principal: SessionPrincipal,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<Void> {
        transactions.inTenant(TenantContext.current()) {
            audit.record("identity.session.revoked", "identity.user", principal.userId.toString(), after = mapOf("reason" to "logout"))
        }
        request.getSession(false)?.invalidate()
        csrfTokens.saveToken(null, request, response)
        return ResponseEntity.noContent().build()
    }

    private fun state(
        principal: SessionPrincipal,
        account: UserAccount,
    ): SessionState =
        when {
            principal.sessionComplete -> SessionState.AUTHENTICATED
            mfa.isEnrolled(account.id) -> SessionState.MFA_REQUIRED
            else -> SessionState.MFA_ENROLMENT_REQUIRED
        }

    /** Implausible input gets the same answer as wrong credentials, before any lookup or hashing. */
    private fun plausible(body: LoginRequest) =
        body.email.isNotBlank() && body.email.length <= MAX_EMAIL && body.password.length in 1..MAX_PASSWORD

    private fun invalidCredentials(): ResponseEntity<Any> =
        ResponseEntity
            .status(HttpStatus.UNAUTHORIZED)
            .body(ProblemTypes.of(HttpStatus.UNAUTHORIZED, ProblemTypes.INVALID_CREDENTIALS, "The email or password is incorrect."))

    private fun UserAccount.toResponse() = SessionUserResponse(id.value, email, displayName, tenantAdmin)

    private companion object {
        const val MAX_EMAIL = 320
        const val MAX_PASSWORD = 1024
    }
}
