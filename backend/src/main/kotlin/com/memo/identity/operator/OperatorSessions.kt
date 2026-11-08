package com.memo.identity.operator

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.memo.common.web.AuthenticatedOperator
import com.memo.common.web.StepUpRequiredException
import com.memo.identity.session.Factor
import com.memo.identity.session.SessionFactor
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import org.slf4j.LoggerFactory
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.FactorGrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.session.FindByIndexNameSessionRepository
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * An operator's session (ADR-0031), stored as JSON in the `memo.operator` attribute, never next to a tenant principal.
 * Password and TOTP are both required; the absolute timeout is fixed at sign-in.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class OperatorPrincipal(
    override val operatorId: UUID,
    val factors: List<SessionFactor>,
    val authenticatedAt: Instant,
    val expiresAt: Instant,
) : AuthenticatedOperator {
    @get:JsonIgnore
    override val sessionComplete: Boolean
        get() = has(Factor.PASSWORD) && has(Factor.TOTP)

    fun has(factor: Factor): Boolean = factors.any { it.type == factor }

    fun factor(factor: Factor): SessionFactor? = factors.lastOrNull { it.type == factor }

    fun withFactor(
        factor: Factor,
        at: Instant,
    ): OperatorPrincipal = copy(factors = factors.filterNot { it.type == factor } + SessionFactor(factor, at))

    /** Spring Session's principal index; never collides with a tenant user's `<tenantId>:<userId>`. */
    @get:JsonIgnore
    val indexName: String
        get() = "platform:$operatorId"

    @JsonIgnore
    fun authorities(): List<GrantedAuthority> =
        buildList {
            factors.forEach { add(FactorGrantedAuthority.withAuthority(it.type.authority).issuedAt(it.issuedAt).build()) }
            if (sessionComplete) add(SimpleGrantedAuthority(AuthenticatedOperator.OPERATOR_SESSION_COMPLETE))
        }
}

/** The request's operator authentication, rebuilt from the session on every request (never serialised). */
class OperatorAuthentication(
    @Transient val operator: OperatorPrincipal,
) : AbstractAuthenticationToken(operator.authorities()) {
    init {
        super.setAuthenticated(true)
    }

    override fun getCredentials(): Any? = null

    override fun getPrincipal(): OperatorPrincipal = operator

    override fun getName(): String = operator.indexName

    override fun setAuthenticated(authenticated: Boolean) {
        require(!authenticated) { "OperatorAuthentication is authenticated by construction" }
        super.setAuthenticated(false)
    }

    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** Reads and writes the operator principal (ADR-0031); the counterpart of the tenant `SessionStore`. */
@Component
class OperatorSessionStore(
    private val jsonMapper: JsonMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun read(session: HttpSession): OperatorPrincipal? {
        val json = session.getAttribute(PRINCIPAL) as? String ?: return null
        return runCatching { jsonMapper.readValue(json, OperatorPrincipal::class.java) }
            .onFailure { log.warn("Unreadable operator principal; the session is discarded") }
            .getOrNull()
    }

    /** Signs in on a brand-new session, so the id always changes (session fixation). */
    fun start(
        request: HttpServletRequest,
        principal: OperatorPrincipal,
    ) {
        request.getSession(false)?.invalidate()
        val session = request.getSession(true)
        session.maxInactiveInterval = IDLE_TIMEOUT.toSeconds().toInt()
        write(session, principal)
    }

    /** Stores an updated principal (a new factor) under a new session id. */
    fun upgrade(
        request: HttpServletRequest,
        principal: OperatorPrincipal,
    ) {
        val session = requireNotNull(request.getSession(false)) { "no session to upgrade" }
        request.changeSessionId()
        write(session, principal)
    }

    private fun write(
        session: HttpSession,
        principal: OperatorPrincipal,
    ) {
        session.setAttribute(PRINCIPAL, jsonMapper.writeValueAsString(principal))
        session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, principal.indexName)
    }

    companion object {
        const val PRINCIPAL = "memo.operator"
        val IDLE_TIMEOUT: Duration = Duration.ofMinutes(15)
        val ABSOLUTE_TIMEOUT: Duration = Duration.ofHours(8)
    }
}

/** Fresh TOTP for sensitive operator actions (suspend, resume, first-admin invitation; ADR-0031). */
@Component
class OperatorStepUp(
    private val clock: Clock,
) {
    fun require() {
        val principal = SecurityContextHolder.getContext().authentication?.principal as? OperatorPrincipal
        val totp = principal?.factor(Factor.TOTP)
        if (totp == null || totp.issuedAt.plus(WINDOW).isBefore(clock.instant())) throw StepUpRequiredException(WINDOW)
    }

    companion object {
        val WINDOW: Duration = Duration.ofMinutes(5)
    }
}
