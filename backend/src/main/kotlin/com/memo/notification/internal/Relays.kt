package com.memo.notification.internal

import com.memo.common.cache.CacheInvalidation
import com.memo.common.cache.TenantCache
import com.memo.common.cache.TenantCaches
import com.memo.common.crypto.DataKeys
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantTransactions
import com.memo.jooq.tables.references.NOTIFICATION_SMTP
import com.memo.tenancy.TenantSettings
import com.memo.tenancy.TermKey
import com.memo.tenancy.Terminology
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.MessageSource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.support.ResourceBundleMessageSource
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.JavaMailSenderImpl
import org.springframework.stereotype.Component
import org.springframework.web.util.HtmlUtils
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale
import java.util.Optional

/** How the connection to a relay is protected. `NONE` only where the installation allows it (local mail catchers). */
enum class TlsMode { STARTTLS, TLS, NONE }

/** One SMTP relay; [password] in clear only in memory. `toString` never prints it. */
data class Relay(
    val host: String,
    val port: Int,
    val tls: TlsMode,
    val username: String?,
    val password: String?,
    val fromAddress: String,
    val fromName: String?,
) {
    override fun toString() = "Relay(host=$host, port=$port, tls=$tls, username=$username, from=$fromAddress)"
}

/** A JavaMail sender for a relay: TLS as configured, server identity checked, 10 s timeouts. */
internal object Relays {
    private const val TIMEOUT_MS = "10000"

    fun sender(relay: Relay): JavaMailSender =
        JavaMailSenderImpl().apply {
            host = relay.host.ifBlank { null }
            port = relay.port
            username = relay.username
            password = relay.password
            val props = javaMailProperties
            props["mail.smtp.auth"] = (relay.username != null).toString()
            props["mail.smtp.starttls.enable"] = (relay.tls == TlsMode.STARTTLS).toString()
            props["mail.smtp.starttls.required"] = (relay.tls == TlsMode.STARTTLS).toString()
            props["mail.smtp.ssl.enable"] = (relay.tls == TlsMode.TLS).toString()
            props["mail.smtp.ssl.checkserveridentity"] = (relay.tls != TlsMode.NONE).toString()
            props["mail.smtp.connectiontimeout"] = TIMEOUT_MS
            props["mail.smtp.timeout"] = TIMEOUT_MS
            props["mail.smtp.writetimeout"] = TIMEOUT_MS
        }
}

/** The tenant's own relay settings (ADR-0030), cached per tenant; the password is decrypted only into the cache. */
@Component
internal class TenantRelays(
    private val tenantDsl: TenantDsl,
    private val keys: DataKeys,
    private val invalidation: CacheInvalidation,
    private val clock: Clock,
    caches: TenantCaches,
) {
    private val cache: TenantCache<String, Optional<Relay>> = caches.register(CACHE)
    private val senders: TenantCache<String, JavaMailSender> = caches.register(SENDERS)

    /** The tenant's relay, or null to use the platform relay. Inside a tenant transaction. */
    fun current(): Relay? = cache.get(KEY) { Optional.ofNullable(load()) }.orElse(null)

    /** A sender for the tenant's relay, rebuilt when the settings change. */
    fun sender(relay: Relay): JavaMailSender = senders.get(KEY) { Relays.sender(relay) }

    /** Raw row for the admin API: settings, whether a password is set, and the optimistic-lock version. */
    fun stored(): StoredRelay? =
        tenantDsl.current().selectFrom(NOTIFICATION_SMTP).fetchOne()?.let {
            StoredRelay(
                Relay(it.host, it.port, TlsMode.valueOf(it.tlsMode), it.username, null, it.fromAddress, it.fromName),
                passwordSet = it.passwordEnc != null,
                version = it.version!!,
            )
        }

    /**
     * Creates or replaces the tenant's relay. [password] null keeps the stored one (unless the username is removed);
     * a blank one removes it.
     */
    fun save(
        relay: Relay,
        password: String?,
    ) {
        val tenant = tenantDsl.tenant()
        val keep = password == null && relay.username != null
        val encrypted = password?.takeIf { it.isNotBlank() }?.let { keys.encrypt(it.toByteArray(Charsets.UTF_8), aad(tenant.toString())) }
        val now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
        val dsl = tenantDsl.current()
        dsl
            .insertInto(NOTIFICATION_SMTP)
            .set(NOTIFICATION_SMTP.TENANT_ID, tenant.value)
            .set(NOTIFICATION_SMTP.HOST, relay.host)
            .set(NOTIFICATION_SMTP.PORT, relay.port)
            .set(NOTIFICATION_SMTP.TLS_MODE, relay.tls.name)
            .set(NOTIFICATION_SMTP.USERNAME, relay.username)
            .set(NOTIFICATION_SMTP.PASSWORD_ENC, encrypted)
            .set(NOTIFICATION_SMTP.FROM_ADDRESS, relay.fromAddress)
            .set(NOTIFICATION_SMTP.FROM_NAME, relay.fromName)
            .set(NOTIFICATION_SMTP.UPDATED_AT, now)
            .onConflict(NOTIFICATION_SMTP.TENANT_ID)
            .doUpdate()
            .set(NOTIFICATION_SMTP.HOST, relay.host)
            .set(NOTIFICATION_SMTP.PORT, relay.port)
            .set(NOTIFICATION_SMTP.TLS_MODE, relay.tls.name)
            .set(NOTIFICATION_SMTP.USERNAME, relay.username)
            .set(
                NOTIFICATION_SMTP.PASSWORD_ENC,
                if (keep) {
                    NOTIFICATION_SMTP.PASSWORD_ENC
                } else {
                    org.jooq.impl.DSL
                        .`val`(encrypted)
                },
            ).set(NOTIFICATION_SMTP.FROM_ADDRESS, relay.fromAddress)
            .set(NOTIFICATION_SMTP.FROM_NAME, relay.fromName)
            .set(NOTIFICATION_SMTP.VERSION, NOTIFICATION_SMTP.VERSION.plus(1))
            .set(NOTIFICATION_SMTP.UPDATED_AT, now)
            .execute()
        evict()
    }

    fun delete(): Boolean = (tenantDsl.current().deleteFrom(NOTIFICATION_SMTP).execute() > 0).also { evict() }

    private fun evict() {
        invalidation.invalidate(CACHE, tenantDsl.tenant(), KEY)
        invalidation.invalidate(SENDERS, tenantDsl.tenant(), KEY)
    }

    private fun load(): Relay? =
        tenantDsl.current().selectFrom(NOTIFICATION_SMTP).fetchOne()?.let {
            val password =
                it.passwordEnc?.let { sealed ->
                    String(keys.decrypt(sealed, aad(tenantDsl.tenant().toString())), Charsets.UTF_8)
                }
            Relay(it.host, it.port, TlsMode.valueOf(it.tlsMode), it.username, password, it.fromAddress, it.fromName)
        }

    companion object {
        const val CACHE = "notification.relays"
        const val SENDERS = "notification.senders"
        private const val KEY = "relay"

        internal fun aad(tenant: String) = "notification.smtp:$tenant"
    }
}

/** The relay as the admin API shows it: never the password. */
internal data class StoredRelay(
    val relay: Relay,
    val passwordSet: Boolean,
    val version: Long,
)

/** Localised subjects, bodies and the HTML layout (ADR-0030). */
@Component
internal class MailTexts(
    @param:Qualifier("mailMessageSource") private val messages: MessageSource,
    private val settings: TenantSettings,
    private val terminology: Terminology,
    private val transactions: TenantTransactions,
) {
    /** The recipient's language if we have texts for it, else the tenant default, else English. */
    fun localeOf(tag: String?): Locale {
        val candidates = listOfNotNull(tag, settings.get(DEFAULT_LOCALE_SETTING)?.value, "en")
        return candidates.map { Locale.forLanguageTag(it) }.first { it.language in SUPPORTED }
    }

    fun subject(
        template: com.memo.notification.MailTemplate,
        locale: Locale,
    ): String = messages.getMessage("mail.${template.name}.subject", null, locale)

    fun body(
        template: com.memo.notification.MailTemplate,
        locale: Locale,
        params: Map<String, String>,
    ): String = MailText.render(messages.getMessage("mail.${template.name}.body", null, locale), terms(locale) + params)

    /**
     * The tenant's words for [locale] as placeholders (ADR-0032): `{unit}`, `{units}` and so on, the tenant's override or
     * the bundle's default. A template's own parameters win over terms with the same name.
     */
    fun terms(locale: Locale): Map<String, String> {
        // Rendering runs outside the delivery's transactions (no transaction is held while talking to the relay).
        val overrides =
            transactions
                .readInTenant(
                    TenantContext.current(),
                ) { terminology.overrides(locale.language) }
                .associateBy { it.term }
        return TermKey.entries
            .flatMap { term ->
                val override = overrides[term]
                listOf(
                    term.key to (override?.one ?: messages.getMessage("term.${term.key}.one", null, locale)),
                    "${term.key}s" to (override?.other ?: messages.getMessage("term.${term.key}.other", null, locale)),
                )
            }.toMap()
    }

    fun footer(locale: Locale): String = messages.getMessage("mail.footer", null, locale)

    fun html(
        text: String,
        locale: Locale,
    ): String = MailText.html(text, locale, footer(locale))

    companion object {
        const val DEFAULT_LOCALE_SETTING = "locale.default"
        val SUPPORTED = setOf("en", "ar")
    }
}

@Configuration(proxyBeanMethods = false)
internal class MailTextsConfig {
    /** Email texts only; plain `{name}` placeholders, so MessageFormat is never applied. */
    @Bean
    fun mailMessageSource(): ResourceBundleMessageSource =
        ResourceBundleMessageSource().apply {
            setBasename("notification/messages")
            setDefaultEncoding(Charsets.UTF_8.name())
            setFallbackToSystemLocale(false)
            setAlwaysUseMessageFormat(false)
        }
}

/** Plain-text templates with `{name}` placeholders; the HTML part is the escaped text with links as buttons. */
object MailText {
    private val PLACEHOLDER = Regex("\\{([a-z]+)}")
    private val LINK = Regex("https?://\\S+")

    fun render(
        template: String,
        params: Map<String, String>,
    ): String = PLACEHOLDER.replace(template) { params[it.groupValues[1]].orEmpty() }

    fun html(
        text: String,
        locale: Locale = Locale.ENGLISH,
        footer: String = "",
    ): String {
        val rtl = locale.language == "ar"
        val escaped = HtmlUtils.htmlEscape(text, Charsets.UTF_8.name())
        val linked =
            LINK.replace(escaped) {
                """<a href="${it.value}" style="display:inline-block;padding:10px 16px;background:#1f3a5f;color:#ffffff;""" +
                    """text-decoration:none;border-radius:4px">${it.value}</a>"""
            }
        val dir = if (rtl) "rtl" else "ltr"
        return """<!doctype html><html lang="${locale.language}" dir="$dir"><body style="font-family:sans-serif;line-height:1.5;""" +
            """color:#1a1a1a;margin:0;padding:24px">""" + linked.replace("\n", "<br>") +
            """<p style="color:#666666;font-size:12px;margin-top:32px">${HtmlUtils.htmlEscape(footer)}</p></body></html>"""
    }
}
