package com.memo.identity.session

import com.fasterxml.jackson.annotation.JsonIgnore
import com.memo.common.web.AuthenticatedUser
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.FactorGrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import java.time.Instant
import java.util.UUID

/** An authentication factor proven in this session (ADR-0023, ADR-0024). */
enum class Factor(
    val authority: String,
) {
    PASSWORD(FactorGrantedAuthority.PASSWORD_AUTHORITY),
    TOTP("FACTOR_TOTP"),
}

data class SessionFactor(
    val type: Factor,
    val issuedAt: Instant,
)

/**
 * The only security-relevant content of a session (ADR-0023), stored as JSON in the `memo.principal` attribute. Role and
 * MFA requirement are fixed at sign-in; changing them (E04 role grants, admin actions) revokes the user's sessions.
 */
data class SessionPrincipal(
    override val tenantId: UUID,
    override val userId: UUID,
    val tenantAdmin: Boolean,
    val mfaRequired: Boolean,
    val factors: List<SessionFactor>,
    val authenticatedAt: Instant,
    /** Absolute timeout, fixed at sign-in from the tenant setting; idle timeout is the session's own. */
    val expiresAt: Instant,
) : AuthenticatedUser {
    @get:JsonIgnore
    override val sessionComplete: Boolean
        get() = has(Factor.PASSWORD) && (!mfaRequired || has(Factor.TOTP))

    fun has(factor: Factor): Boolean = factors.any { it.type == factor }

    fun factor(factor: Factor): SessionFactor? = factors.lastOrNull { it.type == factor }

    /** Adds or refreshes [factor] (a repeated TOTP after step-up moves its `issuedAt`). */
    fun withFactor(
        factor: Factor,
        at: Instant,
    ): SessionPrincipal = copy(factors = factors.filterNot { it.type == factor } + SessionFactor(factor, at))

    /** Spring Session's principal index: lists and revokes all sessions of one user in one tenant. */
    @get:JsonIgnore
    val indexName: String
        get() = "$tenantId:$userId"

    @JsonIgnore
    fun authorities(): List<GrantedAuthority> =
        buildList {
            factors.forEach { add(FactorGrantedAuthority.withAuthority(it.type.authority).issuedAt(it.issuedAt).build()) }
            if (sessionComplete) add(SimpleGrantedAuthority(AuthenticatedUser.SESSION_COMPLETE))
            if (sessionComplete && tenantAdmin) add(SimpleGrantedAuthority(AuthenticatedUser.TENANT_ADMIN))
        }
}

/** The request's authentication, rebuilt from the session principal on every request (never serialised). */
class MemoAuthentication(
    // Never serialised (sessions hold JSON, ADR-0023); transient keeps Authentication's Serializable contract honest.
    @Transient val sessionPrincipal: SessionPrincipal,
) : AbstractAuthenticationToken(sessionPrincipal.authorities()) {
    init {
        super.setAuthenticated(true)
    }

    override fun getCredentials(): Any? = null

    override fun getPrincipal(): SessionPrincipal = sessionPrincipal

    override fun getName(): String = sessionPrincipal.indexName

    override fun setAuthenticated(authenticated: Boolean) {
        require(!authenticated) { "MemoAuthentication is authenticated by construction" }
        super.setAuthenticated(false)
    }

    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}
