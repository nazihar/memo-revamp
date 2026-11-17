package com.memo.identity

import com.memo.identity.session.Factor
import com.memo.identity.session.SessionPrincipal
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat

/** How the signed-in user proved who they are in this session (ADR-0051): factors and when each was last proven. */
data class AuthenticationEvidence(
    /** `PASSWORD`, `TOTP`: the factors the session holds. */
    val factors: List<String>,
    val authenticatedAt: Instant,
    val passwordAt: Instant?,
    /** When the second factor (TOTP) was last proven, at sign-in or by a step-up; null without one. */
    val secondFactorAt: Instant?,
    /** Lower-case hex SHA-256 of the session id: links decisions of one session without revealing it. */
    val sessionHash: String?,
)

/**
 * The authentication behind the current request (ADR-0051), for records that must show how a decision was
 * authenticated. Null outside a signed-in request. The session id is never returned, only its SHA-256: session ids are
 * long random values, so the hash cannot be turned back into one.
 */
@Component
class AuthenticationContext {
    fun current(): AuthenticationEvidence? {
        val principal = SecurityContextHolder.getContext().authentication?.principal as? SessionPrincipal ?: return null
        val session = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request?.getSession(false)?.id
        return AuthenticationEvidence(
            factors =
                principal.factors
                    .map { it.type.name }
                    .distinct()
                    .sorted(),
            authenticatedAt = principal.authenticatedAt,
            passwordAt = principal.factor(Factor.PASSWORD)?.issuedAt,
            secondFactorAt = principal.factor(Factor.TOTP)?.issuedAt,
            sessionHash = session?.let { HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) },
        )
    }
}
