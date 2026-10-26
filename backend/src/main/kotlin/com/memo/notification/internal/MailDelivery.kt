package com.memo.notification.internal

import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask
import com.memo.common.crypto.DataKeys
import com.memo.common.jobs.Jobs
import com.memo.common.jobs.TenantScopedPayload
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantTransactions
import com.memo.jooq.tables.references.NOTIFICATION_MAIL_OUTBOX
import com.memo.notification.Mail
import com.memo.notification.MailTemplate
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.JavaMailSenderImpl
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Component
import org.springframework.web.util.HtmlUtils
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Delivery of one outbox entry (instance id = outbox id, so a message is scheduled at most once). */
data class MailSend(
    override val tenantId: UUID,
    val outboxId: UUID,
) : TenantScopedPayload

/**
 * The platform SMTP relay (`MEMO_SMTP_*`). STARTTLS is required unless switched off for a local mail catcher; with no
 * host, mail stays queued (PENDING) and nothing is sent.
 */
@ConfigurationProperties("memo.mail")
data class MailSettings(
    val host: String = "",
    val port: Int = 25,
    val from: String = "",
    val username: String = "",
    val password: String = "",
    val starttls: Boolean = true,
)

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MailSettings::class)
class MailConfig {
    @Bean
    fun mailSender(settings: MailSettings): JavaMailSender =
        JavaMailSenderImpl().apply {
            host = settings.host.ifBlank { null }
            port = settings.port
            if (settings.username.isNotBlank()) {
                username = settings.username
                password = settings.password
            }
            javaMailProperties["mail.smtp.auth"] = settings.username.isNotBlank().toString()
            javaMailProperties["mail.smtp.starttls.enable"] = settings.starttls.toString()
            javaMailProperties["mail.smtp.starttls.required"] = settings.starttls.toString()
            javaMailProperties["mail.smtp.connectiontimeout"] = TIMEOUT_MS
            javaMailProperties["mail.smtp.timeout"] = TIMEOUT_MS
            javaMailProperties["mail.smtp.writetimeout"] = TIMEOUT_MS
        }

    /** Retries with the jobs' backoff (ADR-0018); eight attempts span about a day. */
    @Bean
    fun mailSendTask(
        jobs: Jobs,
        delivery: MailDelivery,
    ): OneTimeTask<MailSend> = jobs.tenantTask("mail-send", MailSend::class.java, maxRetries = MAX_ATTEMPTS) { delivery.send(it.outboxId) }

    private companion object {
        const val TIMEOUT_MS = "10000"
        const val MAX_ATTEMPTS = 8
    }
}

@Component
class MailDelivery(
    private val transactions: TenantTransactions,
    private val tenantDsl: TenantDsl,
    private val keys: DataKeys,
    private val json: JsonMapper,
    private val sender: JavaMailSender,
    private val settings: MailSettings,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun send(outboxId: UUID) {
        val tenant = TenantContext.current()
        val message =
            transactions.readInTenant(tenant) {
                tenantDsl
                    .current()
                    .selectFrom(NOTIFICATION_MAIL_OUTBOX)
                    .where(NOTIFICATION_MAIL_OUTBOX.ID.eq(outboxId))
                    .and(NOTIFICATION_MAIL_OUTBOX.STATUS.eq("PENDING"))
                    .fetchOne()
            }
        // Already sent (or gone): nothing to do, so a retried or duplicate task never mails twice.
        if (message == null) return
        when {
            settings.host.isBlank() -> {
                log.warn("No SMTP relay configured (MEMO_SMTP_HOST): mail {} stays queued", outboxId)
            }

            else -> {
                val template = MailTemplate.valueOf(message.template)
                val params = readParams(keys.decrypt(message.paramsEnc!!, Mail.aad(tenant.toString(), outboxId.toString())))
                deliver(message.toAddress, template, params)
                transactions.inTenant(tenant) {
                    tenantDsl
                        .current()
                        .update(NOTIFICATION_MAIL_OUTBOX)
                        .set(NOTIFICATION_MAIL_OUTBOX.STATUS, "SENT")
                        .set(NOTIFICATION_MAIL_OUTBOX.SENT_AT, OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC))
                        .set(NOTIFICATION_MAIL_OUTBOX.ATTEMPTS, NOTIFICATION_MAIL_OUTBOX.ATTEMPTS.plus(1))
                        .setNull(NOTIFICATION_MAIL_OUTBOX.PARAMS_ENC)
                        .where(NOTIFICATION_MAIL_OUTBOX.ID.eq(outboxId))
                        .execute()
                }
            }
        }
    }

    private fun deliver(
        to: String,
        template: MailTemplate,
        params: Map<String, String>,
    ) {
        val text = MailText.render(template.text, params)
        val mime = sender.createMimeMessage()
        MimeMessageHelper(mime, true, Charsets.UTF_8.name()).apply {
            setFrom(settings.from.ifBlank { "memo@${settings.host}" })
            setTo(to)
            setSubject(template.subject)
            setText(text, MailText.html(text))
        }
        sender.send(mime)
    }

    @Suppress("UNCHECKED_CAST")
    private fun readParams(bytes: ByteArray): Map<String, String> = json.readValue(bytes, Map::class.java) as Map<String, String>
}

/** Plain-text templates with `{name}` placeholders; the HTML part is the escaped text with clickable links. */
object MailText {
    private val PLACEHOLDER = Regex("\\{([a-z]+)}")
    private val LINK = Regex("https?://\\S+")

    fun render(
        template: String,
        params: Map<String, String>,
    ): String = PLACEHOLDER.replace(template) { params[it.groupValues[1]].orEmpty() }

    fun html(text: String): String {
        val escaped = HtmlUtils.htmlEscape(text, Charsets.UTF_8.name())
        val linked = LINK.replace(escaped) { """<a href="${it.value}">${it.value}</a>""" }
        return "<!doctype html><html><body style=\"font-family:sans-serif;line-height:1.5\">" +
            linked.replace("\n", "<br>") + "</body></html>"
    }
}
