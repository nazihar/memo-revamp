package com.memo.meeting

import com.memo.common.context.ActorContext
import com.memo.common.crypto.DataKeys
import com.memo.common.tenant.TenantContext
import com.memo.meeting.internal.DeclareInput
import com.memo.meeting.internal.LateDecision
import com.memo.meeting.internal.Lateness
import com.memo.meeting.internal.PaperInput
import com.memo.meeting.internal.invite.InvitationReconciler
import com.memo.notification.internal.MailConfig
import com.memo.notification.internal.MailDelivery
import com.memo.notification.internal.MailSettings
import com.memo.notification.internal.MailTexts
import com.memo.notification.internal.TenantRelays
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.query
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Properties
import java.util.UUID

/**
 * The recusal leak suite's email channel (test plan E04, T5; [PaperSurfaceRegistry.Channel.MAIL]): every email of the
 * planted scenario — the agenda's publication and change notices, declarations made and the rulings asked, the request to
 * discuss, a late paper on an item whose chair and secretary are recused, and the invitations with their calendar files —
 * is delivered through SMTP into a real Mailpit and read back decoded, part by part. None carries a canary, a nature or an
 * item's title, to anyone; the recused chair and secretary are not told of the late paper at all.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RecusalMailIT : RecusalLeakFixture() {
    @Autowired lateinit var keys: DataKeys

    @Autowired lateinit var reconciler: InvitationReconciler

    @Autowired lateinit var clock: Clock

    @Autowired internal lateinit var relays: TenantRelays

    @Autowired internal lateinit var texts: MailTexts

    private val mailpit: GenericContainer<*> by lazy {
        GenericContainer("axllent/mailpit:v1.31.5")
            .withExposedPorts(SMTP, API)
            .waitingFor(Wait.forHttp("/livez").forPort(API))
            .also { it.start() }
    }

    private var started = false

    @AfterAll
    fun stop() {
        if (started) mailpit.stop()
    }

    @Test
    fun `every email of the scenario, read back from Mailpit with its calendar files, is free of every canary`() {
        val p = Planted(content = true)
        ActorContext.asSystem("meeting-invitations") {
            TenantContext.call(tenant.id) { reconciler.run(p.meeting.id, Instant.now().plus(Duration.ofMinutes(3))) }
        }
        latePaperWithTheChairRecused()

        val queued =
            AppRoleJdbc.inTransaction(tenant.id) { c ->
                c.query("select id from notification_mail_outbox where status = 'PENDING'") { it.getObject(1, UUID::class.java) }
            }
        assertThat(queued).hasSizeGreaterThan(MIN_MAILS)
        started = true
        val delivery = delivery()
        TenantContext.call(tenant.id) { queued.forEach { delivery.send(it) } }
        val messages = received()
        assertThat(messages).hasSameSizeAs(queued)
        val everything = canaries(observer) + natureOf(observer) + TITLE
        messages.forEach { (to, text) -> everything.forEach { assertThat(text).describedAs("a message to $to").doesNotContain(it) } }
        assertThat(messages.map { it.second }).anySatisfy { assertThat(it).contains("BEGIN:VCALENDAR") }
    }

    /** Tomorrow's meeting is past its paper deadline: a paper on an item the chair and a secretary are recused from. */
    private fun latePaperWithTheChairRecused() {
        val late = meeting(days = 1)
        val lateItem = addItem(late, TITLE)
        publish(late)
        listOf(secretary, chair).forEach { officer ->
            asUser(officer.account) { declarations.declare(late.id, lateItem, DeclareInput(natureOf(officer), DeclarationIntent.LEAVE)) }
        }
        val lateFile = upload(late, "$CANARY_FILE-late.pdf", canaryPdf())
        var at = version(late)
        val latePaper = asUser(secretary2.account) { papers.add(late.id, lateItem, at, PaperInput(lateFile, CANARY_PAPER)) }
        val waiting =
            asUser(secretary2.account) { views.draft(late.id) }
                .sections
                .flatMap { it.items }
                .single { it.id == lateItem }
                .papers
                .single()
        assertThat(waiting.id to waiting.late).isEqualTo(latePaper to Lateness.AWAITING_CHAIR)
        assertThat(notified(vice, "MEETING_LATE_PAPER")).isEqualTo(1)
        assertThat(notified(chair, "MEETING_LATE_PAPER") + notified(secretary, "MEETING_LATE_PAPER")).isZero()
        at = version(late)
        asUser(vice.account) { chairing.decidePaper(late.id, latePaper, at, LateDecision(true)) }
    }

    private fun delivery(): MailDelivery {
        val settings =
            MailSettings(host = mailpit.host, port = mailpit.getMappedPort(SMTP), from = "memo@platform.example", starttls = false)
        return MailDelivery(transactions, tenantDsl, keys, json, MailConfig().mailSender(settings), settings, relays, texts, clock)
    }

    /** Every message Mailpit holds, as (recipient, the subject and every decoded part's text). */
    private fun received(): List<Pair<String, String>> {
        val list = json.readTree(mailApi("/api/v1/messages?limit=1000"))["messages"].list()
        return list.map { summary ->
            val raw = mailApi("/api/v1/message/${summary["ID"].asString()}/raw").toByteArray(Charsets.ISO_8859_1)
            val message = MimeMessage(Session.getInstance(Properties()), ByteArrayInputStream(raw))
            summary["To"].toString() to (message.subject + "\n" + textOf(message))
        }
    }

    private fun textOf(part: Part): String =
        when (val content = part.content) {
            is MimeMultipart -> (0 until content.count).joinToString("\n") { textOf(content.getBodyPart(it)) }
            is String -> content
            else -> part.inputStream.readAllBytes().toString(Charsets.UTF_8)
        }

    private fun mailApi(path: String): String =
        HttpClient
            .newHttpClient()
            .send(
                HttpRequest.newBuilder(URI("http://${mailpit.host}:${mailpit.getMappedPort(API)}$path")).build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            ).body()
            .toString(Charsets.ISO_8859_1)

    private companion object {
        const val SMTP = 1025
        const val API = 8025
        const val MIN_MAILS = 5
    }
}
