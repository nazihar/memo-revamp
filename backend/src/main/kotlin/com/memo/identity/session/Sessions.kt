package com.memo.identity.session

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask
import com.github.kagkarlsson.scheduler.task.schedule.Schedules
import com.memo.common.context.ActorContext
import com.memo.common.jobs.Jobs
import com.memo.common.tenant.TenantContext
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpSession
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.ConversionService
import org.springframework.core.convert.TypeDescriptor
import org.springframework.core.convert.converter.GenericConverter
import org.springframework.core.convert.support.GenericConversionService
import org.springframework.security.core.context.DeferredSecurityContext
import org.springframework.security.core.context.SecurityContext
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.security.web.context.HttpRequestResponseHolder
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.session.FindByIndexNameSessionRepository
import org.springframework.session.SessionIdGenerator
import org.springframework.session.config.SessionRepositoryCustomizer
import org.springframework.session.jdbc.JdbcIndexedSessionRepository
import org.springframework.session.web.http.CookieSerializer
import org.springframework.session.web.http.DefaultCookieSerializer
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.Base64

/** Where and when a session started (client address and user agent as seen at sign-in). */
data class SessionClient(
    val ip: String?,
    val userAgent: String?,
    val signedInAt: java.time.Instant,
)

/** Reads and writes the session principal (ADR-0023). Nothing else security-relevant lives in a session. */
@Component
class SessionStore(
    private val jsonMapper: JsonMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun read(session: HttpSession): SessionPrincipal? {
        val json = session.getAttribute(PRINCIPAL) as? String ?: return null
        return runCatching { jsonMapper.readValue(json, SessionPrincipal::class.java) }
            .onFailure { log.warn("Unreadable session principal; the session is discarded") }
            .getOrNull()
    }

    /**
     * Signs [principal] in on a brand-new session: any previous session is invalidated, so the id always changes
     * (session fixation, ASVS V7.2).
     */
    fun start(
        request: HttpServletRequest,
        principal: SessionPrincipal,
        idleTimeout: Duration,
    ) {
        request.getSession(false)?.invalidate()
        val session = request.getSession(true)
        session.maxInactiveInterval = idleTimeout.toSeconds().toInt()
        write(session, principal)
        val client = ActorContext.currentOrNull()?.client
        session.setAttribute(CLIENT, jsonMapper.writeValueAsString(SessionClient(client?.ip, client?.userAgent, principal.authenticatedAt)))
    }

    fun client(json: String?): SessionClient? =
        json?.let { runCatching { jsonMapper.readValue(it, SessionClient::class.java) }.getOrNull() }

    /** Stores an updated principal (a new factor) under a new session id. */
    fun upgrade(
        request: HttpServletRequest,
        principal: SessionPrincipal,
    ) {
        val session = requireNotNull(request.getSession(false)) { "no session to upgrade" }
        request.changeSessionId()
        write(session, principal)
    }

    private fun write(
        session: HttpSession,
        principal: SessionPrincipal,
    ) {
        session.setAttribute(PRINCIPAL, jsonMapper.writeValueAsString(principal))
        session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, principal.indexName)
    }

    companion object {
        const val PRINCIPAL = "memo.principal"

        /** Where the session was started, for "my sessions" (shown to the user only). */
        const val CLIENT = "memo.client"
    }
}

/**
 * Rebuilds the request's authentication from the session principal (ADR-0023). A principal from another tenant than the
 * request host's, or past its absolute timeout, invalidates the session; requests outside a tenant (health, management)
 * are anonymous. Sessions are written only by [SessionStore], never here.
 */
@Component
class MemoSecurityContextRepository(
    private val store: SessionStore,
    private val clock: Clock,
) : SecurityContextRepository {
    override fun loadDeferredContext(request: HttpServletRequest): DeferredSecurityContext =
        object : DeferredSecurityContext {
            private val loaded: SecurityContext? by lazy { load(request) }

            override fun get(): SecurityContext = loaded ?: SecurityContextHolder.createEmptyContext()

            override fun isGenerated(): Boolean = loaded == null
        }

    private fun load(request: HttpServletRequest): SecurityContext? {
        val stored = request.getSession(false)?.let { session -> store.read(session)?.let { session to it } }
        val tenant = TenantContext.currentOrNull()
        if (stored == null || tenant == null) return null
        val (session, principal) = stored
        val valid = principal.tenantId == tenant.value && clock.instant() < principal.expiresAt
        if (!valid) session.invalidate()
        return if (valid) SecurityContextImpl(MemoAuthentication(principal)) else null
    }

    @Deprecated("Spring Security calls loadDeferredContext")
    @Suppress("DEPRECATION") // the interface still declares it
    override fun loadContext(requestResponseHolder: HttpRequestResponseHolder): SecurityContext =
        loadDeferredContext(requestResponseHolder.request).get()

    override fun saveContext(
        context: SecurityContext,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) = Unit

    override fun containsContext(request: HttpServletRequest): Boolean = request.getSession(false)?.let { store.read(it) } != null
}

/** 256-bit random session ids (ASVS V7.2.3 asks for at least 128 bits; Spring's default UUIDs carry 122). */
class RandomSessionIds : SessionIdGenerator {
    private val random = SecureRandom()

    override fun generate(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(ID_BYTES).also { random.nextBytes(it) })

    private companion object {
        const val ID_BYTES = 32
    }
}

/**
 * Session attributes are strings only (the principal JSON and its index name): no Java serialisation of session content,
 * ever (ADR-0023). Anything else fails loudly when written.
 */
object StringOnlySessionAttributes : GenericConverter {
    override fun getConvertibleTypes(): Set<GenericConverter.ConvertiblePair> =
        setOf(
            GenericConverter.ConvertiblePair(Any::class.java, ByteArray::class.java),
            GenericConverter.ConvertiblePair(ByteArray::class.java, Any::class.java),
        )

    override fun convert(
        source: Any?,
        sourceType: TypeDescriptor,
        targetType: TypeDescriptor,
    ): Any? =
        when (source) {
            null -> null
            is ByteArray -> String(source, Charsets.UTF_8)
            is String -> source.toByteArray(Charsets.UTF_8)
            else -> throw IllegalArgumentException("Session attributes must be strings, not ${source.javaClass.name}")
        }

    fun conversionService(): ConversionService = GenericConversionService().apply { addConverter(StringOnlySessionAttributes) }
}

/** Web mode only: Spring Session exists only there (migrate mode has no sessions). */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class SessionConfig {
    /**
     * The session cookie (ADR-0023): `MEMO_SESSION`, HttpOnly, Secure, SameSite=Lax, host-only (no Domain, so it never
     * reaches another tenant's host). Defined here because Boot applies `server.servlet.session.cookie.*` to Spring
     * Session only with an embedded server.
     */
    @Bean
    fun sessionCookieSerializer(
        @Value("\${memo.security.cookie-secure:true}") secure: Boolean,
    ): CookieSerializer =
        DefaultCookieSerializer().apply {
            setCookieName(COOKIE_NAME)
            setUseHttpOnlyCookie(true)
            setUseSecureCookie(secure)
            setSameSite("Lax")
            setCookiePath("/")
        }

    @Bean
    fun memoSessionRepositoryCustomizer(): SessionRepositoryCustomizer<JdbcIndexedSessionRepository> =
        SessionRepositoryCustomizer {
            it.setSessionIdGenerator(RandomSessionIds())
            it.setConversionService(StringOnlySessionAttributes.conversionService())
        }

    /** Replaces Spring Session's own cron cleanup (disabled in application.yml) with a single-execution job (ADR-0018). */
    @Bean
    fun sessionCleanupTask(
        jobs: Jobs,
        sessions: JdbcIndexedSessionRepository,
    ): RecurringTask<Void> = jobs.platformTask("session-cleanup", Schedules.fixedDelay(CLEANUP_EVERY)) { sessions.cleanUpExpiredSessions() }

    companion object {
        const val COOKIE_NAME = "MEMO_SESSION"
        private val CLEANUP_EVERY: Duration = Duration.ofMinutes(5)
    }
}
