package com.memo.notification

import com.memo.common.crypto.DataKeys
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantTransactions
import com.memo.notification.internal.MailConfig
import com.memo.notification.internal.MailDelivery
import com.memo.notification.internal.MailSettings
import com.memo.notification.internal.MailText
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.MailOutbox
import com.memo.testsupport.aTestTenant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
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

/** Outbox → SMTP (design E03 §6), against a real Mailpit. */
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

    private val mailpit: GenericContainer<*> =
        GenericContainer("axllent/mailpit:v1.31.5")
            .withExposedPorts(SMTP, API)
            .waitingFor(Wait.forHttp("/livez").forPort(API))
            .also { it.start() }

    @AfterAll
    fun stop() = mailpit.stop()

    private fun delivery(host: String = mailpit.host): MailDelivery {
        val mailSettings = MailSettings(host = host, port = mailpit.getMappedPort(SMTP), from = "memo@bank.example", starttls = false)
        return MailDelivery(transactions, tenantDsl, keys, json, MailConfig().mailSender(mailSettings), mailSettings, clock)
    }

    private fun mailpitMessages(): String =
        HttpClient
            .newHttpClient()
            .send(
                HttpRequest.newBuilder(URI("http://${mailpit.host}:${mailpit.getMappedPort(API)}/api/v1/messages")).build(),
                HttpResponse.BodyHandlers.ofString(),
            ).body()

    @Test
    fun `a queued mail is delivered once, then its parameters are gone`() {
        val tenant = registry.aTestTenant(transactions, settings, "mail")
        val to = "nadia-${UUID.randomUUID().toString().take(8)}@bank.example"
        val link = "https://${tenant.slug}.localhost/reset-password#secret-token"
        transactions.inTenant(tenant.id) {
            mail.enqueue(MailTemplate.PASSWORD_RESET, to, mapOf("name" to "Nadia", "organisation" to "Bank", "link" to link))
        }
        val queued = MailOutbox(keys, json).last(tenant, "PASSWORD_RESET", to)

        TenantContext.call(tenant.id) {
            delivery().send(queued.id)
            delivery().send(queued.id)
        }

        val messages = mailpitMessages()
        assertThat(Regex(Regex.escape(to)).findAll(messages).count()).isEqualTo(1)
        assertThat(messages).contains("Reset your memo password").doesNotContain("secret-token\",\"Subject")
        val sent = MailOutbox(keys, json).last(tenant, "PASSWORD_RESET", to)
        assertThat(sent.status).isEqualTo("SENT")
        assertThat(sent.params).isEmpty()
    }

    @Test
    fun `without a relay mail stays queued`() {
        val tenant = registry.aTestTenant(transactions, settings, "mail-off")
        val to = "omar-${UUID.randomUUID().toString().take(8)}@bank.example"
        transactions.inTenant(
            tenant.id,
        ) { mail.enqueue(MailTemplate.PASSWORD_CHANGED, to, mapOf("name" to "Omar", "organisation" to "Bank")) }
        val queued = MailOutbox(keys, json).last(tenant, "PASSWORD_CHANGED", to)

        TenantContext.call(tenant.id) { delivery(host = "").send(queued.id) }

        assertThat(MailOutbox(keys, json).last(tenant, "PASSWORD_CHANGED", to).status).isEqualTo("PENDING")
    }

    @Test
    fun `templates fill placeholders and the HTML part escapes text and links URLs`() {
        val text = MailText.render("Hi {name}, open {link}", mapOf("name" to "<b>Nadia</b>", "link" to "https://a.example/x#t"))

        assertThat(text).isEqualTo("Hi <b>Nadia</b>, open https://a.example/x#t")
        assertThat(MailText.html(text)).contains("&lt;b&gt;Nadia&lt;/b&gt;").contains("""<a href="https://a.example/x#t">""")
        assertThat(MailTemplate.entries.map { it.subject }).noneMatch { it.contains("{") }
    }

    private companion object {
        const val SMTP = 1025
        const val API = 8025
    }
}
