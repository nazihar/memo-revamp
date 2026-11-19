package com.memo.notification

import com.memo.common.context.ActorContext
import com.memo.common.crypto.DataKeys
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantTransactions
import com.memo.identity.UserAccounts
import com.memo.identity.recipient
import com.memo.meeting.internal.ical.InviteFixtures
import com.memo.meeting.internal.ical.InviteKind
import com.memo.notification.internal.MailConfig
import com.memo.notification.internal.MailDelivery
import com.memo.notification.internal.MailSettings
import com.memo.notification.internal.MailTexts
import com.memo.notification.internal.NotificationPreferences
import com.memo.notification.internal.Relay
import com.memo.notification.internal.TenantRelays
import com.memo.notification.internal.TlsMode
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.TestTenant
import com.memo.testsupport.aPassword
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.anActiveUser
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.internet.ContentType
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
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
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.util.Locale
import java.util.Properties
import java.util.UUID

/**
 * The mail calendar part (ADR-0063, test plan T1 `MailCalendarPartIT`) against a real Mailpit, reading the raw message
 * back: the iMIP layout of RFC 6047 §2.4 with the method parameter equal to the file's METHOD, the subject without
 * parameters, the part encrypted at rest and cleared after sending or a permanent failure; and `Notifications.notify`
 * with a part, its result, and the email switched off.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MailCalendarPartIT : IntegrationTest() {
    @Autowired lateinit var mail: Mail

    @Autowired lateinit var notifications: Notifications

    @Autowired lateinit var statuses: MailStatuses

    @Autowired lateinit var sending: SendingAddress

    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var accounts: UserAccounts

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var tenantDsl: TenantDsl

    @Autowired lateinit var keys: DataKeys

    @Autowired lateinit var json: JsonMapper

    @Autowired lateinit var clock: Clock

    @Autowired internal lateinit var relays: TenantRelays

    @Autowired internal lateinit var texts: MailTexts

    @Autowired internal lateinit var preferences: NotificationPreferences

    private val mailpit: GenericContainer<*> =
        GenericContainer("axllent/mailpit:v1.31.5")
            .withEnv("MP_ENABLE_CHAOS", "true")
            .withExposedPorts(SMTP, API)
            .waitingFor(Wait.forHttp("/livez").forPort(API))
            .also { it.start() }

    private val request = InviteFixtures.writer().write(InviteFixtures.request())
    private val cancel = InviteFixtures.writer().write(InviteFixtures.of(InviteKind.CANCEL))

    @AfterAll
    fun stop() = mailpit.stop()

    @AfterEach
    fun calm() = chaos(null)

    private fun delivery(): MailDelivery {
        val mailSettings =
            MailSettings(host = mailpit.host, port = mailpit.getMappedPort(SMTP), from = "memo@platform.example", starttls = false)
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

    private fun chaos(code: Int?) {
        val trigger = if (code == null) """{"ErrorCode":451,"Probability":0}""" else """{"ErrorCode":$code,"Probability":100}"""
        api("/api/v1/chaos", "PUT", """{"Recipient":$trigger}""")
    }

    /** The raw message Mailpit received for [to], parsed. */
    private fun received(to: String): MimeMessage {
        val found = json.readTree(api("/api/v1/search?query=" + URLEncoder.encode("to:\"$to\"", Charsets.UTF_8)))
        val id =
            found
                .get("messages")
                .single()
                .get("ID")
                .asString()
        val raw =
            HttpClient
                .newHttpClient()
                .send(
                    HttpRequest.newBuilder(URI("http://${mailpit.host}:${mailpit.getMappedPort(API)}/api/v1/message/$id/raw")).build(),
                    HttpResponse.BodyHandlers.ofByteArray(),
                ).body()
        return MimeMessage(Session.getInstance(Properties()), raw.inputStream())
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

    private fun queue(
        tenant: TestTenant,
        part: CalendarPart,
    ): Pair<String, UUID> {
        val to = "director-${UUID.randomUUID().toString().take(8)}@demo.example"
        val id =
            transactions.inTenant(tenant.id) {
                mail.enqueue(
                    MailTemplate.TEST,
                    to,
                    mapOf(
                        "name" to "Demo Director",
                        "organisation" to "Bank",
                    ),
                    null,
                    part,
                )
            }
        return to to id
    }

    @Test
    fun `an invitation is sent as iMIP with text, HTML and the calendar as alternatives, and invite ics`() {
        val tenant = registry.aTestTenant(transactions, settings, "imip")
        val (to, id) = queue(tenant, CalendarPart(CalendarMethod.REQUEST, request))
        assertThat(column(tenant, id, "calendar_method")).isEqualTo("REQUEST")
        assertThat(column(tenant, id, "encode(calendar_enc, 'escape')")).isNotNull().doesNotContain("VCALENDAR")

        TenantContext.call(tenant.id) { delivery().send(id) }

        val message = received(to)
        assertThat(message.contentType).startsWith("multipart/mixed")
        assertThat(message.subject).isEqualTo(texts.subject(MailTemplate.TEST, Locale.ENGLISH))
        val mixed = message.content as MimeMultipart
        assertThat(mixed.count).isEqualTo(2)
        val alternative = mixed.getBodyPart(0).content as MimeMultipart
        assertThat(ContentType(mixed.getBodyPart(0).contentType).baseType).isEqualTo("multipart/alternative")
        val types = (0 until alternative.count).map { ContentType(alternative.getBodyPart(it).contentType) }
        assertThat(types.map { it.baseType }).containsExactly("text/plain", "text/html", "text/calendar")
        val calendarType = types[2]
        assertThat(calendarType.getParameter("method")).isEqualTo("REQUEST")
        assertThat(calendarType.getParameter("charset")).isEqualToIgnoringCase("UTF-8")
        assertThat(alternative.getBodyPart(2).inputStream.readAllBytes()).isEqualTo(request)
        val attachment = mixed.getBodyPart(1)
        assertThat(ContentType(attachment.contentType).baseType).isEqualTo("application/ics")
        assertThat(attachment.fileName).isEqualTo("invite.ics")
        assertThat(attachment.disposition).isEqualTo(Part.ATTACHMENT)
        assertThat(attachment.inputStream.readAllBytes()).isEqualTo(request)
        assertThat(alternative.getBodyPart(0).content as String).contains("Demo Director")

        assertThat(column(tenant, id, "status")).isEqualTo("SENT")
        assertThat(column(tenant, id, "calendar_enc")).isNull()
        assertThat(column(tenant, id, "calendar_method")).isEqualTo("REQUEST")
        val status = transactions.readInTenant(tenant.id) { statuses.of(listOf(id, UUID.randomUUID())) }
        assertThat(status.keys).containsExactly(id)
        assertThat(status.getValue(id).state).isEqualTo(MailState.SENT)
        assertThat(status.getValue(id).calendarMethod).isEqualTo(CalendarMethod.REQUEST)
    }

    @Test
    fun `a cancellation carries method CANCEL, and a permanent refusal keeps no part`() {
        val tenant = registry.aTestTenant(transactions, settings, "imip-cancel")
        val (to, id) = queue(tenant, CalendarPart(CalendarMethod.CANCEL, cancel))
        TenantContext.call(tenant.id) { delivery().send(id) }
        val parts = (received(to).content as MimeMultipart).getBodyPart(0).content as MimeMultipart
        assertThat(ContentType(parts.getBodyPart(2).contentType).getParameter("method")).isEqualTo("CANCEL")

        val (_, refused) = queue(tenant, CalendarPart(CalendarMethod.CANCEL, cancel))
        chaos(550)
        TenantContext.call(tenant.id) { delivery().send(refused) }

        assertThat(column(tenant, refused, "status")).isEqualTo("FAILED")
        assertThat(column(tenant, refused, "calendar_enc")).isNull()
        assertThat(column(tenant, refused, "params_enc")).isNull()
        val failed = transactions.readInTenant(tenant.id) { statuses.of(listOf(refused)) }.getValue(refused)
        assertThat(failed.state).isEqualTo(MailState.FAILED)
        assertThat(failed.smtpCode).isEqualTo(550)
    }

    @Test
    fun `notify returns the item and the mail, and with the email switched off sends no part`() {
        val tenant = registry.aTestTenant(transactions, settings, "imip-notify", mfaRequired = false)
        val user = accounts.anActiveUser(transactions, tenant, aPassword())
        val part = CalendarPart(CalendarMethod.CANCEL, cancel)
        val params = mapOf("organisation" to "Bank", "committee" to "BRC", "date" to "2026-12-12")

        val sent =
            transactions.inTenant(tenant.id) {
                notifications.notify(user.recipient(), NotificationKind.MEETING_CANCELLED, params, "/meetings", part)
            }
        assertThat(sent.itemId).isNotNull()
        assertThat(sent.outboxId).isNotNull()
        assertThat(column(tenant, sent.outboxId!!, "calendar_method")).isEqualTo("CANCEL")

        transactions.inTenant(tenant.id) {
            ActorContext.asSystem(
                "test",
            ) { preferences.set(user.id.value, NotificationCategory.WORKFLOW, NotificationChannel.EMAIL, false) }
        }
        val quiet =
            transactions.inTenant(tenant.id) {
                notifications.notify(user.recipient(), NotificationKind.MEETING_CANCELLED, params, "/meetings", part)
            }
        assertThat(quiet.itemId).isNotNull()
        assertThat(quiet.outboxId).isNull()

        assertThatThrownBy {
            transactions.inTenant(
                tenant.id,
            ) { notifications.notify(user.recipient(), NotificationKind.EDIT_NOT_SAVED, emptyMap(), null, part) }
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `the sending address is the tenant relay's, else the platform's`() {
        val tenant = registry.aTestTenant(transactions, settings, "imip-from")
        assertThat(transactions.readInTenant(tenant.id) { sending.address() }).contains("@")
        transactions.inTenant(tenant.id) {
            ActorContext.asSystem("test") {
                relays.save(
                    Relay(mailpit.host, mailpit.getMappedPort(SMTP), TlsMode.NONE, null, null, "meetings@bank.example", "Bank Board"),
                    null,
                )
            }
        }
        assertThat(transactions.readInTenant(tenant.id) { sending.address() }).isEqualTo("meetings@bank.example")
    }

    private companion object {
        const val SMTP = 1025
        const val API = 8025
    }
}
