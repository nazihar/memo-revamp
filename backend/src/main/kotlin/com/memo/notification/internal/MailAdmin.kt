package com.memo.notification.internal

import com.memo.audit.AuditLog
import com.memo.common.authz.Permission
import com.memo.common.authz.RequiresPermission
import com.memo.common.authz.StepUpCheck
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.ratelimit.RateLimit
import com.memo.common.ratelimit.RateLimiter
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.etagOf
import com.memo.common.web.ifMatchVersion
import com.memo.jooq.tables.references.NOTIFICATION_MAIL_OUTBOX
import com.memo.notification.MailTemplate
import com.memo.notification.RecipientLookup
import com.memo.tenancy.TenantRegistry
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.jooq.impl.DSL
import org.springframework.http.ResponseEntity
import org.springframework.mail.MailException
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

data class MailSettingsView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val host: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val port: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val tlsMode: String,
    val username: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val passwordSet: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val fromAddress: String,
    val fromName: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val version: Long,
)

data class MailSettingsRequest(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val host: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val port: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val tlsMode: String,
    val username: String?,
    /** Null keeps the stored password; empty removes it. Never returned. */
    val password: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val fromAddress: String,
    val fromName: String?,
)

data class MailTestResult(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val ok: Boolean,
    /** `TENANT` or `PLATFORM`. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val relay: String,
    val smtpCode: Int?,
    val errorClass: String?,
)

data class MailLogEntry(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val template: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val to: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val status: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val attempts: Int,
    val relay: String?,
    val smtpCode: Int?,
    val errorClass: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val createdAt: Instant,
    val sentAt: Instant?,
    val failedAt: Instant?,
)

data class MailLogPage(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val entries: List<MailLogEntry>,
    val nextCursor: String?,
)

/** The tenant's mail relay (ADR-0030): settings without the password in any output or audit entry; test sends. */
@Service
internal class MailAdmin(
    private val relays: TenantRelays,
    private val settings: MailSettings,
    private val platformSender: JavaMailSender,
    private val texts: MailTexts,
    private val recipients: RecipientLookup,
    private val registry: TenantRegistry,
    private val audit: AuditLog,
    private val transactions: TenantTransactions,
) {
    fun view(): MailSettingsView? = relays.stored()?.toView()

    fun save(
        request: MailSettingsRequest,
        expectedVersion: Long?,
    ): MailSettingsView {
        val relay = validate(request)
        val before = relays.stored()
        if (before != null &&
            expectedVersion != before.version
        ) {
            throw ConcurrentModificationException("mail settings", expectedVersion ?: -1)
        }
        relays.save(relay, request.password)
        val after = relays.stored()!!
        audit.record(
            "notification.smtp.changed",
            "notification.smtp",
            TenantContext.current().toString(),
            before = before?.let { describe(it.relay) },
            after = describe(after.relay) + ("loginChanged" to (request.password != null)),
        )
        return after.toView()
    }

    fun remove(): Boolean {
        val before = relays.stored() ?: return false
        relays.delete()
        audit.record("notification.smtp.removed", "notification.smtp", TenantContext.current().toString(), before = describe(before.relay))
        return true
    }

    /**
     * Sends the test mail to the actor's own address now, through the relay mail would use. Reads in one short
     * transaction, talks to the relay outside any, and audits the result in another.
     */
    fun test(): MailTestResult {
        val tenant = TenantContext.current()
        val user = (ActorContext.current().actor as? Actor.User)?.userId ?: error("signed-in user expected")
        val (recipient, relay, organisation) =
            transactions.readInTenant(tenant) {
                Triple(recipients.recipient(user) ?: error("unknown user"), relays.current(), registry.current()?.name.orEmpty())
            }
        val locale = transactions.readInTenant(tenant) { texts.localeOf(recipient.locale) }
        if (relay == null && settings.host.isBlank()) return audited(tenant, MailTestResult(false, "PLATFORM", null, "NoRelayConfigured"))
        val sender = if (relay != null) relays.sender(relay) else platformSender
        val text = texts.body(MailTemplate.TEST, locale, mapOf("name" to recipient.displayName, "organisation" to organisation))
        val result =
            try {
                val mime = sender.createMimeMessage()
                MimeMessageHelper(mime, true, Charsets.UTF_8.name()).apply {
                    setFrom(relay?.fromAddress ?: settings.from.ifBlank { "memo@${settings.host}" })
                    setTo(recipient.email)
                    setSubject(texts.subject(MailTemplate.TEST, locale))
                    setText(text, texts.html(text, locale))
                }
                sender.send(mime)
                MailTestResult(true, relayName(relay), null, null)
            } catch (e: MailException) {
                MailTestResult(false, relayName(relay), MailDelivery.smtpCode(e), errorClass(e))
            } catch (e: jakarta.mail.MessagingException) {
                MailTestResult(false, relayName(relay), null, errorClass(e))
            }
        return audited(tenant, result)
    }

    private fun audited(
        tenant: com.memo.common.tenant.TenantId,
        result: MailTestResult,
    ): MailTestResult {
        transactions.inTenant(tenant) {
            audit.record(
                "notification.smtp.tested",
                "notification.smtp",
                tenant.toString(),
                after = mapOf("ok" to result.ok, "relay" to result.relay, "smtpCode" to result.smtpCode, "errorClass" to result.errorClass),
            )
        }
        return result
    }

    private fun validate(request: MailSettingsRequest): Relay {
        val tls = TlsMode.entries.firstOrNull { it.name == request.tlsMode } ?: throw IllegalArgumentException("unknown TLS mode")
        require(tls != TlsMode.NONE || settings.allowInsecure) { "This installation requires an encrypted connection to the mail relay." }
        require(HOST.matches(request.host)) { "invalid host" }
        require(request.port in 1..MAX_PORT) { "invalid port" }
        require(EMAIL.matches(request.fromAddress)) { "invalid from address" }
        require(request.fromName?.length?.let { it in 1..MAX_NAME } ?: true) { "invalid from name" }
        require(request.username?.length?.let { it in 1..MAX_ADDRESS } ?: true) { "invalid username" }
        return Relay(request.host, request.port, tls, request.username, null, request.fromAddress, request.fromName)
    }

    private fun describe(relay: Relay): Map<String, Any?> =
        mapOf(
            "host" to relay.host,
            "port" to relay.port,
            "tlsMode" to relay.tls.name,
            "username" to relay.username,
            "fromAddress" to relay.fromAddress,
            "fromName" to relay.fromName,
        )

    private fun relayName(relay: Relay?) = if (relay != null) "TENANT" else "PLATFORM"

    private fun errorClass(e: Throwable) =
        (generateSequence(e) { it.cause }.take(MAX_CAUSES).last()::class.simpleName ?: "MailException").take(MAX_ERROR_CLASS)

    private fun StoredRelay.toView() =
        MailSettingsView(relay.host, relay.port, relay.tls.name, relay.username, passwordSet, relay.fromAddress, relay.fromName, version)

    private companion object {
        val HOST = Regex("^[A-Za-z0-9.-]{1,253}$")
        val EMAIL = Regex("^[^@\\s]{1,64}@[A-Za-z0-9.-]{1,253}$")
        const val MAX_PORT = 65535
        const val MAX_NAME = 100
        const val MAX_ADDRESS = 320
        const val MAX_CAUSES = 10
        const val MAX_ERROR_CLASS = 100
    }
}

/** The delivery log (design §5), newest first. */
@Service
internal class MailLog(
    private val tenantDsl: TenantDsl,
) {
    fun page(
        cursor: String?,
        limit: Int,
    ): MailLogPage {
        val o = NOTIFICATION_MAIL_OUTBOX
        val after = cursor?.let { runCatching { UUID.fromString(it) }.getOrNull() ?: throw IllegalArgumentException("invalid cursor") }
        val rows =
            tenantDsl
                .current()
                .selectFrom(o)
                .where(if (after == null) DSL.noCondition() else o.ID.lt(after))
                .orderBy(o.ID.desc())
                .limit(limit + 1)
                .fetch()
        val entries =
            rows.take(limit).map {
                MailLogEntry(
                    it.id,
                    it.template,
                    it.toAddress,
                    it.status,
                    it.attempts ?: 0,
                    it.relay,
                    it.smtpCode,
                    it.errorClass,
                    it.createdAt.toInstant(),
                    it.sentAt?.toInstant(),
                    it.failedAt?.toInstant(),
                )
            }
        return MailLogPage(entries, if (rows.size > limit) entries.last().id.toString() else null)
    }
}

/** Admin → Email (design §5): `notification.settings.manage`; changes need a fresh step-up. */
@RestController
@Tag(name = "notifications")
internal class MailAdminController(
    private val admin: MailAdmin,
    private val log: MailLog,
    private val stepUp: StepUpCheck,
    private val rateLimiter: RateLimiter,
    private val transactions: TenantTransactions,
) {
    @GetMapping("/api/v1/admin/mail-settings")
    @RequiresPermission(Permission.NOTIFICATION_SETTINGS_MANAGE)
    @Operation(operationId = "getMailSettings", summary = "The organisation's mail relay (204 when it uses the platform relay)")
    fun get(): ResponseEntity<MailSettingsView> {
        val view = transactions.readInTenant(TenantContext.current()) { admin.view() }
        return if (view == null) ResponseEntity.noContent().build() else ResponseEntity.ok().eTag(etagOf(view.version)).body(view)
    }

    @PutMapping("/api/v1/admin/mail-settings")
    @RequiresPermission(Permission.NOTIFICATION_SETTINGS_MANAGE)
    @Operation(operationId = "setMailSettings", summary = "Set the organisation's mail relay")
    fun put(
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody request: MailSettingsRequest,
    ): ResponseEntity<MailSettingsView> {
        stepUp.require()
        val expected = ifMatch?.let { ifMatchVersion(it) }
        val view = transactions.inTenant(TenantContext.current()) { admin.save(request, expected) }
        return ResponseEntity.ok().eTag(etagOf(view.version)).body(view)
    }

    @DeleteMapping("/api/v1/admin/mail-settings")
    @RequiresPermission(Permission.NOTIFICATION_SETTINGS_MANAGE)
    @Operation(operationId = "removeMailSettings", summary = "Go back to the platform mail relay")
    fun delete(): ResponseEntity<Unit> {
        stepUp.require()
        transactions.inTenant(TenantContext.current()) { admin.remove() }
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/api/v1/admin/mail-settings/test")
    @RequiresPermission(Permission.NOTIFICATION_SETTINGS_MANAGE)
    @Operation(operationId = "testMailSettings", summary = "Send a test email to myself through the current relay")
    fun test(): MailTestResult {
        stepUp.require()
        rateLimiter.consume(RateLimit.MAIL_TEST_PER_TENANT, TenantContext.current().toString())
        return admin.test()
    }

    @GetMapping("/api/v1/admin/mail-log")
    @RequiresPermission(Permission.NOTIFICATION_SETTINGS_MANAGE)
    @Operation(operationId = "getMailLog", summary = "Delivery log of the organisation's emails, newest first")
    fun log(
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "50") limit: Int,
    ): MailLogPage = transactions.readInTenant(TenantContext.current()) { log.page(cursor, limit.coerceIn(1, MAX_PAGE)) }

    private companion object {
        const val MAX_PAGE = 100
    }
}
