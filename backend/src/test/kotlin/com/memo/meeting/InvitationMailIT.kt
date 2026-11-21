package com.memo.meeting

import com.memo.common.context.ActorContext
import com.memo.common.tenant.TenantContext
import com.memo.meeting.internal.ical.InviteChecks
import com.memo.meeting.internal.ical.InviteKind
import com.memo.notification.Mail
import com.memo.notification.internal.MailDelivery
import com.memo.notification.internal.Relay
import com.memo.notification.internal.TenantRelays
import com.memo.notification.internal.TlsMode
import jakarta.mail.Session
import jakarta.mail.internet.ContentType
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.Properties
import java.util.UUID

/**
 * Invitations as they leave memo (test plan E05 T2, ADR-0063), through the tenant's relay into a real Mailpit, read back
 * raw: the invitation and the cancellation are iMIP messages (`text/calendar; method=…` beside the text and HTML, and
 * `invite.ics`) whose file has the invitation's UID and names only the recipient, from the tenant's sending address as
 * ORGANIZER, under a subject without parameters.
 *
 * The test delivers the outbox itself ([Mail.jobs] off): test contexts share the scheduler table, so a scheduled delivery
 * can run in another context; one that loaded "no relay" for the tenant (for the setup's membership emails) while the relay
 * was being saved kept it (the cache race fixed in E05 T6) and left the cancellation queued, so the wait for it timed out.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InvitationMailIT : InvitationTestSupport() {
    @Autowired internal lateinit var relays: TenantRelays

    @Autowired lateinit var mail: Mail

    @Autowired internal lateinit var delivery: MailDelivery

    private val mailpit: GenericContainer<*> =
        GenericContainer("axllent/mailpit:v1.31.5")
            .withExposedPorts(SMTP, API)
            .waitingFor(Wait.forHttp("/livez").forPort(API))
            .also { it.start() }

    /** Before the committee is set up, so none of this class's emails is scheduled. */
    @BeforeAll
    fun deliverByHand() {
        mail.jobs = false
    }

    @AfterAll
    fun stop() {
        mail.jobs = true
        mailpit.stop()
    }

    /** Delivers every queued message of [meetingId]'s invitation, as the `mail-send` job would. */
    private fun deliver(meetingId: UUID) {
        messages(meetingId).mapNotNull { it.outboxId }.forEach { outboxId ->
            ActorContext.asSystem("mail-send") { TenantContext.call(tenant.id) { delivery.send(outboxId) } }
        }
    }

    override fun label() = "invitemail"

    @Test
    fun `the invitation and the cancellation arrive as iMIP with the meeting's UID, for the recipient alone`() {
        transactions.inTenant(tenant.id) {
            ActorContext.asSystem("test") {
                relays.save(
                    Relay(mailpit.host, mailpit.getMappedPort(SMTP), TlsMode.NONE, null, null, "meetings@bank.example", "Bank Board"),
                    null,
                )
            }
        }
        val meeting = scheduled()
        reconcile(meeting.id)
        deliver(meeting.id)
        val uid = checkNotNull(invitation(meeting.id)).icalUid.toString()

        val invite = received(director.email, "REQUEST")
        assertThat(invite.subject).isEqualTo("You are invited to a meeting")
        val (method, file) = calendarOf(invite)
        assertThat(method).isEqualTo("REQUEST")
        val parsed = InviteChecks.valid(file, InviteKind.REQUEST)
        assertThat(parsed.uid).isEqualTo(uid)
        assertThat(parsed.attendees).containsExactly(director.email)
        assertThat(
            String(file, Charsets.UTF_8).replace("\r\n ", ""),
        ).contains("ORGANIZER;CN=Board Risk Committee:mailto:meetings@bank.example")
        assertThat(invite.getRecipients(jakarta.mail.Message.RecipientType.TO).map { it.toString() }).containsExactly(director.email)

        val current = meeting(meeting.id)
        asUser(secretary) { lifecycle.cancel(current.id, current.version, "Called off") }
        reconcile(meeting.id, Instant.now())
        deliver(meeting.id)
        val cancel = received(director.email, "CANCEL")
        val (cancelMethod, cancelFile) = calendarOf(cancel)
        assertThat(cancelMethod).isEqualTo("CANCEL")
        val cancelled = InviteChecks.valid(cancelFile, InviteKind.CANCEL)
        assertThat(cancelled.uid).isEqualTo(uid)
        assertThat(cancelled.sequence).isEqualTo(1)
        assertThat(cancel.subject).isEqualTo("A meeting was cancelled")
    }

    /** The text/calendar alternative's method parameter and bytes; `invite.ics` must carry the same bytes. */
    private fun calendarOf(message: MimeMessage): Pair<String, ByteArray> {
        val mixed = message.content as MimeMultipart
        val alternative = mixed.getBodyPart(0).content as MimeMultipart
        val part = (0 until alternative.count).map { alternative.getBodyPart(it) }.single { it.isMimeType("text/calendar") }
        val bytes = part.inputStream.readAllBytes()
        assertThat(mixed.getBodyPart(1).fileName).isEqualTo("invite.ics")
        assertThat(mixed.getBodyPart(1).inputStream.readAllBytes()).isEqualTo(bytes)
        return ContentType(part.contentType).getParameter("method") to bytes
    }

    /** The message Mailpit received for [to] whose calendar method is [method], waiting for the outbox job to send it. */
    private fun received(
        to: String,
        method: String,
    ): MimeMessage {
        val deadline = Instant.now().plus(Duration.ofSeconds(60))
        while (Instant.now().isBefore(deadline)) {
            val found = json.readTree(api("/api/v1/search?query=" + URLEncoder.encode("to:\"$to\"", Charsets.UTF_8)))
            val list = found.get("messages")
            val messages = if (list == null) emptyList() else (0 until list.size()).map { raw(list.get(it).get("ID").asString()) }
            val match = messages.firstOrNull { runCatching { calendarOf(it).first == method }.getOrDefault(false) }
            if (match != null) return match
            Thread.sleep(POLL)
        }
        error("no $method message for $to")
    }

    private fun raw(id: String): MimeMessage {
        val bytes =
            HttpClient
                .newHttpClient()
                .send(
                    HttpRequest.newBuilder(URI("http://${mailpit.host}:${mailpit.getMappedPort(API)}/api/v1/message/$id/raw")).build(),
                    HttpResponse.BodyHandlers.ofByteArray(),
                ).body()
        return MimeMessage(Session.getInstance(Properties()), bytes.inputStream())
    }

    private fun api(path: String): String =
        HttpClient
            .newHttpClient()
            .send(
                HttpRequest.newBuilder(URI("http://${mailpit.host}:${mailpit.getMappedPort(API)}$path")).build(),
                HttpResponse.BodyHandlers.ofString(),
            ).body()

    private companion object {
        const val SMTP = 1025
        const val API = 8025
        const val POLL = 500L
    }
}
