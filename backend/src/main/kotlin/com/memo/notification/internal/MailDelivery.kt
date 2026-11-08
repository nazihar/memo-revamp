package com.memo.notification.internal

import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask
import com.memo.common.crypto.DataKeys
import com.memo.common.jobs.Jobs
import com.memo.common.jobs.TenantScopedPayload
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantTransactions
import com.memo.jooq.tables.records.NotificationMailOutboxRecord
import com.memo.jooq.tables.references.NOTIFICATION_MAIL_OUTBOX
import com.memo.notification.Mail
import com.memo.notification.MailTemplate
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException
import org.eclipse.angus.mail.smtp.SMTPSendFailedException
import org.eclipse.angus.mail.smtp.SMTPSenderFailedException
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.mail.MailException
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.JavaMailSenderImpl
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Component
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
 * The platform SMTP relay (`MEMO_SMTP_*`), used when a tenant has no relay of its own (ADR-0030). STARTTLS is required
 * unless switched off for a local mail catcher; [allowInsecure] lets tenants choose a plaintext relay too (dev only).
 * With no host, platform mail stays queued (PENDING) and nothing is sent.
 */
@ConfigurationProperties("memo.mail")
data class MailSettings(
    val host: String = "",
    val port: Int = 25,
    val from: String = "",
    val username: String = "",
    val password: String = "",
    val starttls: Boolean = true,
    val allowInsecure: Boolean = false,
)

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MailSettings::class)
class MailConfig {
    @Bean
    fun mailSender(settings: MailSettings): JavaMailSender =
        Relays.sender(
            Relay(
                host = settings.host,
                port = settings.port,
                tls = if (settings.starttls) TlsMode.STARTTLS else TlsMode.NONE,
                username = settings.username.ifBlank { null },
                password = settings.password.ifBlank { null },
                fromAddress = settings.from.ifBlank { "memo@${settings.host}" },
                fromName = null,
            ),
        )

    /** Retries with the jobs' backoff (ADR-0018); eight attempts span about a day. */
    @Bean
    fun mailSendTask(
        jobs: Jobs,
        delivery: MailDelivery,
    ): OneTimeTask<MailSend> = jobs.tenantTask("mail-send", MailSend::class.java, maxRetries = MAX_ATTEMPTS) { delivery.send(it.outboxId) }

    companion object {
        const val MAX_ATTEMPTS = 8
    }
}

/** A delivery attempt failed in a way a retry may fix (connection, timeout, 4xx): the job retries. */
internal class TransientMailFailure(
    cause: Throwable,
) : RuntimeException("mail delivery failed, will retry", cause)

/**
 * Sends one outbox entry (ADR-0030): through the tenant's relay if it has one, else the platform relay; in the
 * recipient's language; and records the attempt in the delivery log. A permanent SMTP refusal (5xx) ends the message at
 * once; anything else is retried until the last attempt. Parameters are cleared when the message is done.
 */
@Component
class MailDelivery internal constructor(
    private val transactions: TenantTransactions,
    private val tenantDsl: TenantDsl,
    private val keys: DataKeys,
    private val json: JsonMapper,
    private val platformSender: JavaMailSender,
    private val settings: MailSettings,
    private val relays: TenantRelays,
    private val texts: MailTexts,
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
        // Already sent or failed (or gone): nothing to do, so a retried or duplicate task never mails twice.
        if (message == null) return
        val (relay, locale) = transactions.readInTenant(tenant) { relays.current() to texts.localeOf(message.locale) }
        if (relay == null && settings.host.isBlank()) {
            log.warn("No mail relay configured (tenant or MEMO_SMTP_HOST): mail {} stays queued", outboxId)
            return
        }
        val outcome =
            try {
                deliver(message, relay, locale)
                Outcome.Sent
            } catch (e: MailException) {
                classify(e)
            }
        record(message, if (relay != null) "TENANT" else "PLATFORM", outcome)
        if (outcome is Outcome.Retry) {
            log.warn("Mail {} not delivered (attempt {}): {}", outboxId, (message.attempts ?: 0) + 1, outcome.errorClass)
            throw TransientMailFailure(outcome.cause)
        }
    }

    private fun deliver(
        message: NotificationMailOutboxRecord,
        relay: Relay?,
        locale: java.util.Locale,
    ) {
        val tenant = TenantContext.current()
        val template = MailTemplate.valueOf(message.template)
        val params = readParams(keys.decrypt(message.paramsEnc!!, Mail.aad(tenant.toString(), message.id.toString())))
        val sender = if (relay != null) relays.sender(relay) else platformSender
        val text = texts.body(template, locale, params)
        val mime = sender.createMimeMessage()
        MimeMessageHelper(mime, true, Charsets.UTF_8.name()).apply {
            if (relay != null) {
                if (relay.fromName != null) setFrom(relay.fromAddress, relay.fromName) else setFrom(relay.fromAddress)
            } else {
                setFrom(settings.from.ifBlank { "memo@${settings.host}" })
            }
            setTo(message.toAddress)
            setSubject(texts.subject(template, locale))
            setText(text + "\n\n" + texts.footer(locale), texts.html(text, locale))
        }
        sender.send(mime)
    }

    private sealed interface Outcome {
        data object Sent : Outcome

        data class Failed(
            val code: Int?,
            val errorClass: String,
        ) : Outcome

        data class Retry(
            val code: Int?,
            val errorClass: String,
            val cause: Throwable,
        ) : Outcome
    }

    /** 5xx is permanent (RFC 5321 §4.2.1); anything else may pass on a later attempt. */
    private fun classify(e: MailException): Outcome {
        val code = smtpCode(e)
        val errorClass = (rootCause(e)::class.simpleName ?: "MailException").take(MAX_ERROR_CLASS)
        return if (code != null && code >= PERMANENT) Outcome.Failed(code, errorClass) else Outcome.Retry(code, errorClass, e)
    }

    private fun record(
        message: NotificationMailOutboxRecord,
        relay: String,
        outcome: Outcome,
    ) {
        val now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
        val attempts = (message.attempts ?: 0) + 1
        val lastAttempt = outcome is Outcome.Retry && attempts >= MailConfig.MAX_ATTEMPTS
        transactions.inTenant(TenantContext.current()) {
            val update =
                tenantDsl
                    .current()
                    .update(NOTIFICATION_MAIL_OUTBOX)
                    .set(NOTIFICATION_MAIL_OUTBOX.ATTEMPTS, attempts)
                    .set(NOTIFICATION_MAIL_OUTBOX.RELAY, relay)
                    .set(NOTIFICATION_MAIL_OUTBOX.LAST_ATTEMPT_AT, now)
            when (outcome) {
                is Outcome.Sent -> {
                    update
                        .set(NOTIFICATION_MAIL_OUTBOX.STATUS, "SENT")
                        .set(NOTIFICATION_MAIL_OUTBOX.SENT_AT, now)
                        .setNull(NOTIFICATION_MAIL_OUTBOX.PARAMS_ENC)
                        .setNull(NOTIFICATION_MAIL_OUTBOX.SMTP_CODE)
                        .setNull(NOTIFICATION_MAIL_OUTBOX.ERROR_CLASS)
                }

                is Outcome.Failed -> {
                    update.failed(now, outcome.code, outcome.errorClass)
                }

                is Outcome.Retry -> {
                    if (lastAttempt) {
                        update.failed(now, outcome.code, outcome.errorClass)
                    } else {
                        update
                            .set(
                                NOTIFICATION_MAIL_OUTBOX.SMTP_CODE,
                                outcome.code,
                            ).set(NOTIFICATION_MAIL_OUTBOX.ERROR_CLASS, outcome.errorClass)
                    }
                }
            }.where(NOTIFICATION_MAIL_OUTBOX.ID.eq(message.id)).execute()
        }
    }

    private fun org.jooq.UpdateSetMoreStep<NotificationMailOutboxRecord>.failed(
        now: OffsetDateTime,
        code: Int?,
        errorClass: String,
    ) = set(NOTIFICATION_MAIL_OUTBOX.STATUS, "FAILED")
        .set(NOTIFICATION_MAIL_OUTBOX.FAILED_AT, now)
        .set(NOTIFICATION_MAIL_OUTBOX.SMTP_CODE, code)
        .set(NOTIFICATION_MAIL_OUTBOX.ERROR_CLASS, errorClass)
        .setNull(NOTIFICATION_MAIL_OUTBOX.PARAMS_ENC)

    @Suppress("UNCHECKED_CAST")
    private fun readParams(bytes: ByteArray): Map<String, String> = json.readValue(bytes, Map::class.java) as Map<String, String>

    companion object {
        private const val PERMANENT = 500
        private const val MAX_ERROR_CLASS = 100

        /** The SMTP reply code anywhere in the cause chain (Angus Mail reports it on its send exceptions). */
        internal fun smtpCode(e: Throwable): Int? = related(e).firstNotNullOfOrNull(::codeOf)

        private fun codeOf(t: Throwable): Int? =
            when (t) {
                is SMTPSendFailedException -> t.returnCode
                is SMTPAddressFailedException -> t.returnCode
                is SMTPSenderFailedException -> t.returnCode
                else -> null
            }

        /** [e] and what it wraps: causes, chained JavaMail exceptions and Spring's per-message failures (bounded). */
        private fun related(e: Throwable): List<Throwable> {
            val seen = mutableListOf<Throwable>()
            val queue = ArrayDeque(listOf(e))
            while (queue.isNotEmpty() && seen.size < MAX_CAUSES) {
                val next = queue.removeFirst()
                if (seen.any { it === next }) continue
                seen += next
                listOfNotNull(next.cause, (next as? jakarta.mail.MessagingException)?.nextException).forEach(queue::add)
                (next as? org.springframework.mail.MailSendException)?.failedMessages?.values?.forEach(queue::add)
            }
            return seen
        }

        private fun rootCause(e: Throwable): Throwable = generateSequence(e) { it.cause }.take(MAX_CAUSES).last()

        private const val MAX_CAUSES = 20
    }
}
