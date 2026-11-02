package com.memo.common.web

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import java.net.URI

/**
 * Stable `type` URIs of RFC 9457 problems that clients act on (design E03 §3). They identify, they are not links.
 * Problems without a specific type keep `about:blank`.
 */
object ProblemTypes {
    val INVALID_CREDENTIALS: URI = URI.create("urn:memo:problem:invalid-credentials")
    val MFA_REQUIRED: URI = URI.create("urn:memo:problem:mfa-required")
    val STEP_UP_REQUIRED: URI = URI.create("urn:memo:problem:step-up-required")
    val RATE_LIMITED: URI = URI.create("urn:memo:problem:rate-limited")
    val VALIDATION: URI = URI.create("urn:memo:problem:validation")
    val CONFLICT: URI = URI.create("urn:memo:problem:conflict")
    val FORBIDDEN: URI = URI.create("urn:memo:problem:forbidden")

    /** 400 `validation` with `errors: [{field, code, message}]`, the shape the web forms consume (E01 design §3). */
    fun validation(errors: List<FieldProblem>): ProblemDetail =
        of(HttpStatus.BAD_REQUEST, VALIDATION, "Some fields need attention.").apply {
            setProperty("errors", errors.map { mapOf("field" to it.field, "code" to it.code, "message" to it.message) })
        }

    fun of(
        status: HttpStatus,
        type: URI,
        title: String,
        detail: String? = null,
    ): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detail ?: title).apply {
            this.type = type
            this.title = title
        }
}

/** One field's problem: [code] for clients that translate, [message] as an English fallback. */
data class FieldProblem(
    val field: String,
    val code: String,
    val message: String,
)

/** A signed-in user as the security chain sees it; implemented by the identity module's session principal. */
interface AuthenticatedUser {
    val tenantId: java.util.UUID
    val userId: java.util.UUID

    /** All factors the tenant requires are present (password, and TOTP where MFA applies). */
    val sessionComplete: Boolean

    companion object {
        /** Granted when every required factor is present; the default requirement of all non-auth endpoints. */
        const val SESSION_COMPLETE = "SESSION_COMPLETE"
    }
}

/**
 * The action needs a fresh second factor (ADR-0024): 401 `step-up-required`, with the window in seconds so the client
 * can say how long the confirmation lasts. The client prompts, calls `/auth/step-up`, and retries once.
 */
class StepUpRequiredException(
    val window: java.time.Duration,
) : RuntimeException("Step-up required")

/** The `Idempotency-Key` was already used for a different request (422). */
class IdempotencyKeyReusedException : RuntimeException("Idempotency-Key reused with a different request")

/** A guarded write needs `If-Match` with the version the client last saw (428). */
class PreconditionRequiredException : RuntimeException("If-Match required")
