package com.memo.notification

import com.memo.common.context.ActorContext
import com.memo.common.crypto.DataKeys
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantTransactions
import com.memo.notification.internal.MailConfig
import com.memo.notification.internal.MailDelivery
import com.memo.notification.internal.MailSettings
import com.memo.notification.internal.MailTexts
import com.memo.notification.internal.Relay
import com.memo.notification.internal.TenantRelays
import com.memo.notification.internal.TlsMode
import com.memo.notification.internal.TransientMailFailure
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.MailOutbox
import com.memo.testsupport.TestTenant
import com.memo.testsupport.aTestTenant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.util.UUID

/** Outbox → SMTP (design E03 §6, ADR-0030) against a real Mailpit, with its chaos triggers for SMTP refusals. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MailDeliveryIT : IntegrationTest() {
    @Autowired lateinit var mail: Mail

    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var tenantDsl: TenantDsl

    @Autowired lateinit var keys: DataKeys

    @Autowired lateinit var json: JsonMapper

    @Autowired lateinit var clock: Clock

    @Autowired internal lateinit var relays: TenantRelays

    @Autowired internal lateinit var texts: MailTexts

    private val mailpit: GenericContainer<*> =
        GenericContainer("axllent/mailpit:v1.31.5")
            .withEnv("MP_ENABLE_CHAOS", "true")
            .withExposedPorts(SMTP, API)
            .waitingFor(Wait.forHttp("/livez").forPort(API))
            .also { it.start() }

    @AfterAll
    fun stop() = mailpit.stop()

    @AfterEach
    fun calm() = chaos(null)

    private fun delivery(platformHost: String = mailpit.host): MailDelivery {
        val mailSettings =
            MailSettings(host = platformHost, port = mailpit.getMappedPort(SMTP), from = "memo@platform.example", starttls = false)
        return MailDelivery(transactions, tenantDsl, keys, json, MailConfig().mailSender(mailSettings), mailSettings, relays, texts, clock)
    }

    private fun api(
        path: String,
        method: String = "GET",
        body: String? = null,
    ): String =
        HttpClient
            .newHttpClient()
            .send(
                HttpRequest
                    .newBuilder(URI("http://${mailpit.host}:${mailpit.getMappedPort(API)}$path"))
                    .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
                    .header("Content-Type", "application/json")
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            ).body()

    /** Mailpit chaos: every recipient refused with [code], or calm again when null. */
    private fun chaos(code: Int?) {
        val trigger = if (code == null) """{"ErrorCode":451,"Probability":0}""" else """{"ErrorCode":$code,"Probability":100}"""
        api("/api/v1/chaos", "PUT", """{"Recipient":$trigger}""")
    }

    private fun queue(
        tenant: TestTenant,
        template: MailTemplate = MailTemplate.PASSWORD_CHANGED,
        locale: String? = null,
    ): Pair<String, UUID> {
        val to = "user-${UUID.randomUUID().toString().take(8)}@bank.example"
        transactions.inTenant(tenant.id) { mail.enqueue(template, to, mapOf("name" to "Nadia", "organisation" to "Bank"), locale) }
        return to to MailOutbox(keys, json).last(tenant, template.name, to).id
    }

    private fun column(
        tenant: TestTenant,
        id: UUID,
        name: String,
    ): String? =
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            c.prepareStatement("select $name::text from notification_mail_outbox where id = ?").use { st ->
                st.setObject(1, id)
                st.executeQuery().use {
                    it.next()
                    it.getString(1)
                }
            }
        }

    @Test
    fun `a queued mail is delivered once through the platform relay, then its parameters are gone`() {
        val tenant = registry.aTestTenant(transactions, settings, "mail")
        val link = "https://${tenant.slug}.localhost/reset-password#secret-token"
        val to = "nadia-${UUID.randomUUID().toString().take(8)}@bank.example"
        transactions.inTenant(tenant.id) {
            mail.enqueue(MailTemplate.PASSWORD_RESET, to, mapOf("name" to "Nadia", "organisation" to "Bank", "link" to link))
        }
        val queued = MailOutbox(keys, json).last(tenant, "PASSWORD_RESET", to)

        TenantContext.call(tenant.id) {
            delivery().send(queued.id)
            delivery().send(queued.id)
        }

        val messages = api("/api/v1/messages")
        assertThat(Regex(Regex.escape(to)).findAll(messages).count()).isEqualTo(1)
        assertThat(messages).contains("Reset your memo password").doesNotContain("secret-token\",\"Subject")
        val sent = MailOutbox(keys, json).last(tenant, "PASSWORD_RESET", to)
        assertThat(sent.status).isEqualTo("SENT")
        assertThat(sent.params).isEmpty()
        assertThat(column(tenant, queued.id, "relay")).isEqualTo("PLATFORM")
    }

    @Test
    fun `a tenant relay takes the tenant's mail, from the tenant's address, in the recipient's language`() {
        val tenant = registry.aTestTenant(transactions, settings, "relay")
        transactions.inTenant(tenant.id) {
            ActorContext.asSystem("test") {
                relays.save(
                    Relay(mailpit.host, mailpit.getMappedPort(SMTP), TlsMode.NONE, null, null, "board@bank.example", "Bank Board"),
                    null,
                )
            }
        }
        val (to, id) = queue(tenant, MailTemplate.PASSWORD_CHANGED, "ar")

        TenantContext.call(tenant.id) { delivery(platformHost = "").send(id) }

        val messages = api("/api/v1/messages")
        assertThat(messages).contains(to).contains("board@bank.example").contains("كلمة مرور")
        assertThat(column(tenant, id, "relay")).isEqualTo("TENANT")
    }

    @Test
    fun `a permanent refusal fails the mail at once and keeps the code, a transient one is retried`() {
        val tenant = registry.aTestTenant(transactions, settings, "bounce")
        val (_, permanent) = queue(tenant)
        chaos(550)

        TenantContext.call(tenant.id) { delivery().send(permanent) }

        assertThat(column(tenant, permanent, "status")).isEqualTo("FAILED")
        assertThat(column(tenant, permanent, "smtp_code")).isEqualTo("550")
        assertThat(column(tenant, permanent, "params_enc")).isNull()

        val (_, transient) = queue(tenant)
        chaos(451)
        assertThatThrownBy { TenantContext.call(tenant.id) { delivery().send(transient) } }.isInstanceOf(TransientMailFailure::class.java)
        assertThat(column(tenant, transient, "status")).isEqualTo("PENDING")
        assertThat(column(tenant, transient, "smtp_code")).isEqualTo("451")
        assertThat(column(tenant, transient, "attempts")).isEqualTo("1")
    }

    @Test
    fun `without any relay mail stays queued`() {
        val tenant = registry.aTestTenant(transactions, settings, "mail-off")
        val (_, id) = queue(tenant)

        TenantContext.call(tenant.id) { delivery(platformHost = "").send(id) }

        assertThat(column(tenant, id, "status")).isEqualTo("PENDING")
    }

    private companion object {
        const val SMTP = 1025
        const val API = 8025
    }
}
