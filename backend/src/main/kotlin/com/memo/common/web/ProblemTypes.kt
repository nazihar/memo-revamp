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

/** A signed-in user as the security chain sees it; implemented by the identity module's session principal. */
interface AuthenticatedUser {
    val tenantId: java.util.UUID
    val userId: java.util.UUID

    /** All factors the tenant requires are present (password, and TOTP where MFA applies). */
    val sessionComplete: Boolean

    companion object {
        /** Granted when every required factor is present; the default requirement of all non-auth endpoints. */
        const val SESSION_COMPLETE = "SESSION_COMPLETE"

        /** Until E04 replaces it with role grants. */
        const val TENANT_ADMIN = "ROLE_TENANT_ADMIN"
    }
}
