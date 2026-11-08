package com.memo.identity.operator

import com.memo.common.authz.AuthenticationFlow
import com.memo.common.ratelimit.RateLimit
import com.memo.common.ratelimit.RateLimiter
import com.memo.common.web.FieldProblem
import com.memo.common.web.ProblemTypes
import com.memo.identity.Invitations
import com.memo.identity.PasswordRejectedException
import com.memo.identity.UserAccounts
import com.memo.identity.auth.CodeRequest
import com.memo.identity.auth.EnrolmentResponse
import com.memo.identity.auth.InvitationAcceptance
import com.memo.identity.auth.InvitationLookup
import com.memo.identity.auth.LoginRequest
import com.memo.identity.auth.SessionState
import com.memo.identity.password.PasswordProblems
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
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
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@Schema(name = "PlatformSession", description = "Who is signed in on the operator console, and what they must do next.")
data class PlatformSessionResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val state: SessionState,
    @field:Schema(description = "Present unless ANONYMOUS")
    val operator: PlatformOperatorResponse? = null,
)

@Schema(name = "PlatformOperator")
data class PlatformOperatorResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val email: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val displayName: String,
)

@Schema(name = "PlatformEnrolment")
data class PlatformEnrolmentResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Masked, for example o***@memo.example")
    val email: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val displayName: String,
)

/** The parts of an operator sign-in response that every step shares (ADR-0031). */
@Component
class OperatorViews(
    private val signIn: OperatorSignIn,
    private val accounts: OperatorAccounts,
    private val store: OperatorSessionStore,
) {
    /** The operator behind [principal] while still ACTIVE. */
    fun active(principal: OperatorPrincipal): Operator? = accounts.find(principal.operatorId)?.takeIf { it.status == OperatorStatus.ACTIVE }

    fun of(principal: OperatorPrincipal?): PlatformSessionResponse {
        val operator = principal?.let { active(it) }
        val state = signIn.state(principal, operator)
        return PlatformSessionResponse(
            state,
            operator?.takeIf { state != SessionState.ANONYMOUS }?.let { PlatformOperatorResponse(it.id, it.email, it.displayName) },
        )
    }

    /** Adds a fresh TOTP factor under a new session id and answers with the new state. */
    fun upgraded(
        principal: OperatorPrincipal,
        action: String,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        val next = signIn.withTotp(principal, action)
        store.upgrade(request, next)
        return noStore(of(next))
    }
}

internal fun <T : Any> noStore(body: T): ResponseEntity<T> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)

internal fun invalidCode(): ResponseEntity<Any> =
    ResponseEntity
        .status(HttpStatus.UNAUTHORIZED)
        .body(ProblemTypes.of(HttpStatus.UNAUTHORIZED, ProblemTypes.INVALID_CREDENTIALS, "The code is not valid."))

internal fun notAllowed(): ResponseEntity<Any> =
    ResponseEntity.status(HttpStatus.FORBIDDEN).body(ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Not allowed."))

private const val MAX_EMAIL = 320
private const val MAX_PASSWORD = 1024
private const val MAX_CODE = 32
private const val MAX_TOKEN = 100

/** Operator sign-in and sign-out on the console's host (ADR-0031). JSON only, like the tenant sign-in. */
@RestController
@AuthenticationFlow
@RequestMapping("/api/v1/platform/auth")
@Tag(name = "platform-auth")
class OperatorAuthController(
    private val signIn: OperatorSignIn,
    private val store: OperatorSessionStore,
    private val views: OperatorViews,
    private val csrfTokens: CsrfTokenRepository,
    private val limiter: RateLimiter,
) {
    @GetMapping("/csrf")
    @Operation(
        operationId = "getPlatformCsrfToken",
        summary = "Issue the CSRF cookie on the operator console",
        responses = [ApiResponse(responseCode = "204", description = "Cookie set")],
    )
    fun csrf(
        @Parameter(hidden = true) token: CsrfToken,
    ): ResponseEntity<Void> {
        token.token
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
    }

    @GetMapping("/session")
    @Operation(
        operationId = "getPlatformSession",
        summary = "Current operator session state",
        description = "Never 401: anonymous callers get ANONYMOUS. Answers only on the operator console's host.",
    )
    fun session(
        @AuthenticationPrincipal principal: OperatorPrincipal?,
    ): ResponseEntity<PlatformSessionResponse> = noStore(views.of(principal))

    @PostMapping("/login")
    @Operation(
        operationId = "platformLogin",
        summary = "Operator sign-in with email and password",
        responses = [
            ApiResponse(responseCode = "200", description = "Password accepted; TOTP or enrolment follows"),
            ApiResponse(responseCode = "401", description = "Invalid credentials (same answer for every reason)"),
            ApiResponse(responseCode = "429", description = "Too many attempts; see Retry-After"),
        ],
    )
    fun login(
        @RequestBody body: LoginRequest,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<Any> {
        val plausible = body.email.isNotBlank() && body.email.length <= MAX_EMAIL && body.password.length in 1..MAX_PASSWORD
        if (plausible) {
            limiter.consume(RateLimit.LOGIN_PER_IP, request.remoteAddr)
            limiter.consume(RateLimit.LOGIN_PER_ACCOUNT, "platform:${UserAccounts.normaliseEmail(body.email)}")
        }
        val principal = if (plausible) signIn.withPassword(body.email, body.password) else null
        return if (principal == null) {
            ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(ProblemTypes.of(HttpStatus.UNAUTHORIZED, ProblemTypes.INVALID_CREDENTIALS, "The email or password is incorrect."))
        } else {
            store.start(request, principal)
            csrfTokens.saveToken(null, request, response)
            noStore(views.of(principal))
        }
    }

    @PostMapping("/logout")
    @Operation(
        operationId = "platformLogout",
        summary = "Sign out of the operator console",
        responses = [ApiResponse(responseCode = "204", description = "Signed out")],
    )
    fun logout(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<Void> {
        request.getSession(false)?.invalidate()
        csrfTokens.saveToken(null, request, response)
        return ResponseEntity.noContent().build()
    }
}

/** The operator's second factor: sign-in step, first enrolment, step-up (ADR-0031). */
@RestController
@AuthenticationFlow
@RequestMapping("/api/v1/platform/auth")
@Tag(name = "platform-auth")
class OperatorMfaController(
    private val mfa: OperatorMfa,
    private val views: OperatorViews,
    private val limiter: RateLimiter,
) {
    @PostMapping("/mfa")
    @Operation(
        operationId = "verifyPlatformMfa",
        summary = "Second sign-in step (TOTP)",
        responses = [
            ApiResponse(responseCode = "200", description = "Signed in"),
            ApiResponse(responseCode = "401", description = "Invalid code"),
        ],
    )
    fun verify(
        @AuthenticationPrincipal principal: OperatorPrincipal,
        @RequestBody body: CodeRequest,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        limiter.consume(RateLimit.OTP_PER_USER, principal.indexName)
        if (!mfa.verify(principal.operatorId, body.code.take(MAX_CODE))) return invalidCode()
        return views.upgraded(principal, "platform.operator.signed_in", request)
    }

    @PostMapping("/mfa/enrolment")
    @Operation(
        operationId = "startPlatformMfaEnrolment",
        summary = "Start TOTP enrolment (first sign-in, or a complete session switching devices)",
        responses = [ApiResponse(responseCode = "403", description = "Already enrolled and the session is not complete")],
    )
    fun startEnrolment(
        @AuthenticationPrincipal principal: OperatorPrincipal,
    ): ResponseEntity<Any> {
        val operator = views.active(principal)
        if (operator == null || !mayEnrol(operator, principal)) return notAllowed()
        val enrolment = mfa.start(operator)
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(EnrolmentResponse(enrolment.secret, enrolment.otpauthUri, enrolment.qrSvg))
    }

    @PostMapping("/mfa/enrolment/confirm")
    @Operation(
        operationId = "confirmPlatformMfaEnrolment",
        summary = "Confirm TOTP enrolment; completes the sign-in",
        responses = [ApiResponse(responseCode = "401", description = "Invalid code")],
    )
    fun confirmEnrolment(
        @AuthenticationPrincipal principal: OperatorPrincipal,
        @RequestBody body: CodeRequest,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        limiter.consume(RateLimit.OTP_PER_USER, principal.indexName)
        val operator = views.active(principal)
        return when {
            operator == null || !mayEnrol(operator, principal) -> notAllowed()
            !mfa.confirm(operator, body.code.take(MAX_CODE)) -> invalidCode()
            else -> views.upgraded(principal, "platform.operator.signed_in", request)
        }
    }

    @PostMapping("/step-up")
    @Operation(
        operationId = "platformStepUp",
        summary = "Confirm a sensitive action with a fresh TOTP code",
        responses = [ApiResponse(responseCode = "401", description = "Invalid code")],
    )
    fun stepUp(
        @AuthenticationPrincipal principal: OperatorPrincipal,
        @RequestBody body: CodeRequest,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        limiter.consume(RateLimit.OTP_PER_USER, principal.indexName)
        return when {
            !principal.sessionComplete -> notAllowed()
            !mfa.verify(principal.operatorId, body.code.take(MAX_CODE)) -> invalidCode()
            else -> views.upgraded(principal, "platform.operator.step_up", request)
        }
    }

    /** Enrolling replaces the second factor: only with none yet (first sign-in) or a complete session. */
    private fun mayEnrol(
        operator: Operator,
        principal: OperatorPrincipal,
    ) = !operator.enrolled || principal.sessionComplete
}

/** The one-time enrolment link from the bootstrap command (ADR-0031): look it up, set the first password. */
@RestController
@AuthenticationFlow
@RequestMapping("/api/v1/platform/auth")
@Tag(name = "platform-auth")
class OperatorEnrolmentController(
    private val accounts: OperatorAccounts,
    private val signIn: OperatorSignIn,
    private val store: OperatorSessionStore,
    private val views: OperatorViews,
    private val csrfTokens: CsrfTokenRepository,
    private val limiter: RateLimiter,
) {
    @PostMapping("/enrolment/lookup")
    @Operation(
        operationId = "lookUpPlatformEnrolment",
        summary = "Show an operator enrolment link",
        responses = [ApiResponse(responseCode = "404", description = "Unknown, used or expired link")],
    )
    fun lookUpEnrolment(
        @RequestBody body: InvitationLookup,
        request: HttpServletRequest,
    ): ResponseEntity<PlatformEnrolmentResponse> {
        limiter.consume(RateLimit.INVITATION_PER_IP, request.remoteAddr)
        val operator = accounts.byEnrolmentToken(body.token.take(MAX_TOKEN)) ?: return ResponseEntity.notFound().build()
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(PlatformEnrolmentResponse(Invitations.mask(operator.email), operator.displayName))
    }

    @PostMapping("/enrolment/accept")
    @Operation(
        operationId = "acceptPlatformEnrolment",
        summary = "Set the operator's password from the enrolment link; TOTP enrolment follows",
        responses = [
            ApiResponse(responseCode = "200", description = "Password set; signed in with the password"),
            ApiResponse(
                responseCode = "400",
                description = "Invalid or expired link, or a weak password",
                content = [
                    io.swagger.v3.oas.annotations.media.Content(
                        mediaType = "application/problem+json",
                        schema = Schema(implementation = ProblemDetail::class),
                    ),
                ],
            ),
        ],
    )
    fun acceptEnrolment(
        @RequestBody body: InvitationAcceptance,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<Any> {
        limiter.consume(RateLimit.INVITATION_PER_IP, request.remoteAddr)
        val outcome = runCatching { accounts.acceptEnrolment(body.token.take(MAX_TOKEN), body.password.take(MAX_PASSWORD)) }
        val rejected = outcome.exceptionOrNull() as? PasswordRejectedException
        outcome.exceptionOrNull()?.takeIf { rejected == null }?.let { throw it }
        val operator = outcome.getOrNull()
        return when {
            rejected != null -> {
                ResponseEntity.badRequest().body(ProblemTypes.validation(PasswordProblems.of("password", rejected.violations)))
            }

            operator == null -> {
                ResponseEntity
                    .badRequest()
                    .body(
                        ProblemTypes.validation(
                            listOf(FieldProblem("token", "INVALID", "This link is no longer valid. Ask for a new one.")),
                        ),
                    )
            }

            else -> {
                val principal = signIn.afterEnrolment(operator)
                store.start(request, principal)
                csrfTokens.saveToken(null, request, response)
                noStore(views.of(principal))
            }
        }
    }
}
