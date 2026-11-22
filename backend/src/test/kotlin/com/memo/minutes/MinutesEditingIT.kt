package com.memo.minutes

import com.memo.common.context.ActorContext
import com.memo.common.tenant.TenantContext
import com.memo.docedit.internal.Hs256
import com.memo.files.FileContents
import com.memo.forum.ForumRole
import com.memo.minutes.internal.DraftTask
import com.memo.minutes.internal.MinutesDrafting
import com.memo.minutes.internal.MinutesDrafts
import com.memo.minutes.internal.MinutesJobs
import com.memo.minutes.internal.MinutesRole
import com.memo.minutes.internal.MinutesStage
import com.memo.minutes.internal.MinutesStore
import com.memo.minutes.internal.MinutesStructure
import com.memo.minutes.internal.MinutesStructureChecks
import com.memo.minutes.internal.StructureProblemKind
import com.memo.testsupport.Audit
import com.memo.testsupport.Docx
import com.memo.testsupport.Person
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.assertj.MvcTestResult
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.JsonNode
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Editing the minutes in ONLYOFFICE (test plan E06 T5 `MinutesEditingIT`; ADR-0047, ADR-0066), against a fake document
 * server as `DocEditIT` does: the committee's secretaries not recused from any item open the draft for editing and share
 * one co-editing room (one document key); a recused secretary, the chair and a member cannot open it; the editor's save
 * lands as the saving secretary's version of the draft, and its structure check finds the block the save lost; once the
 * minutes leave the editable stages the draft opens for viewing only.
 */
class MinutesEditingIT : ReleaseFixture() {
    @Autowired lateinit var drafts: MinutesDrafts

    @Autowired lateinit var drafting: MinutesDrafting

    @Autowired lateinit var checks: MinutesStructureChecks

    @Autowired lateinit var minutesStore: MinutesStore

    @Autowired lateinit var minutesJobs: MinutesJobs

    @Autowired lateinit var contents: FileContents

    @Autowired lateinit var files: com.memo.files.Files

    private val jwt by lazy { Hs256(SECRET, json, Clock.systemUTC()) }

    @BeforeEach
    fun jobsByHand() {
        minutesJobs.jobs = false
    }

    @AfterEach
    fun minutesJobsBack() {
        minutesJobs.jobs = true
    }

    @Test
    fun `secretaries not recused co-edit the draft, and a save that lost a block is caught by the structure check`() {
        val r = running("Term loan, Delta Textiles", "Facility renewal, Acme")
        val (loan, renewal) = r.items
        val secretary2 = person().also { seat(it, ForumRole.SECRETARY) }
        val secretary3 = person().also { seat(it, ForumRole.SECRETARY) }
        declare(secretary3, r.meeting, loan.item)
        decide(r, loan.item, APPROVED_WITH_CONDITIONS)
        decide(r, renewal.item, APPROVED)
        held(r)
        asUser(secretary.account) { drafts.request(r.meeting.id, null, false) }
        val seq = checkNotNull(transactions.readInTenant(tenant.id) { minutesStore.find(r.meeting.id) }).workSeq
        TenantContext.call(
            tenant.id,
        ) { ActorContext.asSystem("minutes-draft") { drafting.run(DraftTask(tenant.id.value, r.meeting.id, seq)) } }
        val fileId = checkNotNull(transactions.readInTenant(tenant.id) { minutesStore.find(r.meeting.id) }?.fileId).toString()

        // Two secretaries open it for editing: one room, one key (ADR-0047).
        val first = openEditor(fileId, secretary)
        assertThat(first).hasStatusOk()
        val config = first.json()["config"]
        assertThat(config["document"]["permissions"]["edit"].asBoolean()).isTrue()
        val second = openEditor(fileId, secretary2)
        assertThat(second).hasStatusOk()
        assertThat(second.json()["config"]["document"]["key"].asString()).isEqualTo(config["document"]["key"].asString())
        // The draft is being edited: the whole draft cannot be made again under the editors.
        val busy = post("/api/v1/meetings/${r.meeting.id}/minutes/draft", secretary.session, """{"replaceEdits":true}""")
        assertThat(busy).hasStatus(HttpStatus.CONFLICT)
        assertThat(busy.response.contentAsString).contains("BEING_EDITED")
        // A secretary recused from an item cannot read the full draft; the chair and a member are not its readers yet.
        listOf(secretary3, chair, director).forEach { assertThat(openEditor(fileId, it)).hasStatus(HttpStatus.NOT_FOUND) }
        listOf(secretary3, chair, director).forEach {
            assertThat(read("/api/v1/files/$fileId/content", it.session).response.status).isIn(403, 404)
        }

        // The closing save, without the renewal's block (as a deletion that got through would leave it).
        val tag = MinutesStructure.tag(renewal.item)
        saved.set(
            Docx.editText(current(fileId), "word/document.xml") { xml ->
                val at = xml.indexOf("""w:val="$tag"""")
                xml.replace(xml.substring(xml.lastIndexOf("<w:sdt>", at), xml.indexOf("</w:sdt>", at) + "</w:sdt>".length), "")
            },
        )
        assertThat(callback(config, STATUS_SAVE)["error"].asInt()).isZero()
        val row = checkNotNull(transactions.readInTenant(tenant.id) { minutesStore.find(r.meeting.id) })
        val versions = read("/api/v1/files/$fileId", secretary.session).json()["versions"]
        assertThat(versions.size()).isEqualTo(2)
        val latest = transactions.readInTenant(tenant.id) { files.info(UUID.fromString(fileId))?.currentVersionId?.let(files::version) }
        assertThat(latest?.uploadedBy).describedAs("saved as the editing secretary").isEqualTo(secretary.id)
        val problems =
            checkNotNull(TenantContext.call(tenant.id) { ActorContext.asSystem("minutes-structure") { checks.check(r.meeting.id) } })
        assertThat(problems.problems.map { it.problem to it.itemId }).containsExactly(StructureProblemKind.MISSING to renewal.item)
        assertThat(row.fileId.toString()).isEqualTo(fileId)
        Audit.assertAudited(tenant.id, "minutes.structure.checked", r.meeting.id.toString())
        val page = read("/api/v1/meetings/${r.meeting.id}/minutes", secretary.session).json()
        assertThat(page["structure"]["problems"][0]["itemId"].asString()).isEqualTo(renewal.item.toString())

        // Out of the editable stages (T6 sends them to the chair): viewing only.
        transactions.inTenant(tenant.id) {
            val locked = checkNotNull(minutesStore.lock(r.meeting.id))
            check(minutesStore.update(locked, locked.copy(state = MinutesStage.CHAIR_REVIEW)))
        }
        assertThat(openEditor(fileId, secretary2)).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(openEditor(fileId, secretary2, mode = "VIEW")).hasStatusOk()
    }

    @Test
    fun `the reviewer opens the draft in review mode, and their save is the draft's next version`() {
        val r = running("Term loan, Delta Textiles")
        decide(r, r.items.single().item, APPROVED)
        held(r)
        asUser(secretary.account) { drafts.request(r.meeting.id, null, false) }
        val seq = checkNotNull(transactions.readInTenant(tenant.id) { minutesStore.find(r.meeting.id) }).workSeq
        TenantContext.call(
            tenant.id,
        ) { ActorContext.asSystem("minutes-draft") { drafting.run(DraftTask(tenant.id.value, r.meeting.id, seq)) } }
        val fileId = checkNotNull(transactions.readInTenant(tenant.id) { minutesStore.find(r.meeting.id) }?.fileId).toString()
        // With the chair for review (T6's send-for-review sets the reviewer).
        transactions.inTenant(tenant.id) {
            val locked = checkNotNull(minutesStore.lock(r.meeting.id))
            val reviewing = locked.copy(state = MinutesStage.CHAIR_REVIEW, reviewerId = chair.id, reviewerRole = MinutesRole.CHAIR)
            check(minutesStore.update(locked, reviewing))
        }
        val opened = openEditor(fileId, chair)
        assertThat(opened).hasStatusOk()
        val permissions = opened.json()["config"]["document"]["permissions"]
        assertThat(permissions["edit"].asBoolean() to permissions["review"].asBoolean()).isEqualTo(false to true)
        assertThat(opened.json()["config"]["editorConfig"]["mode"].asString()).isEqualTo("edit")
        // The secretaries may not edit it meanwhile; a member cannot open it.
        assertThat(openEditor(fileId, secretary)).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(openEditor(fileId, director)).hasStatus(HttpStatus.NOT_FOUND)
        // The chair's save (tracked changes) lands as the chair's version.
        saved.set(current(fileId))
        assertThat(callback(opened.json()["config"], STATUS_SAVE)["error"].asInt()).isZero()
        val latest = transactions.readInTenant(tenant.id) { files.info(UUID.fromString(fileId))?.currentVersionId?.let(files::version) }
        assertThat(latest?.uploadedBy).isEqualTo(chair.id)
    }

    private fun openEditor(
        fileId: String,
        person: Person,
        mode: String = "EDIT",
    ): MvcTestResult = post("/api/v1/files/$fileId/edit-sessions", person.session, """{"mode":"$mode","language":"en"}""")

    private fun internal() =
        RequestPostProcessor {
            it.localPort = INTERNAL_PORT
            it
        }

    private fun callback(
        config: JsonNode,
        status: Int,
    ): JsonNode {
        val callbackUrl = config["editorConfig"]["callbackUrl"].asString()
        val payload = mapOf("key" to config["document"]["key"].asString(), "status" to status, "url" to SAVED_URL)
        val body = json.writeValueAsString(payload + ("token" to jwt.sign(payload)))
        val result =
            mvc
                .post()
                .uri(callbackUrl.substringAfter("$INTERNAL_PORT"))
                .with(internal())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange()
        assertThat(result).hasStatusOk()
        return result.json()
    }

    private fun current(fileId: String): ByteArray =
        TenantContext.call(tenant.id) {
            ActorContext.asSystem("test") {
                val version = checkNotNull(contents.current(UUID.fromString(fileId), "minutes.draft"))
                ByteArrayOutputStream().also { contents.write(version, it) }.toByteArray()
            }
        }

    companion object {
        private const val SECRET = "minutes-docedit-test-secret-of-32-bytes!"
        private const val INTERNAL_PORT = 8082
        private const val STATUS_SAVE = 2

        /** What the fake document server answers as the edited document. */
        private val saved = AtomicReference(ByteArray(0))

        private val server: HttpServer =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/cache/minutes.docx") { exchange ->
                    val bytes = saved.get()
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                start()
            }

        private const val SAVED_URL = "https://tenant.example/office/cache/minutes.docx"

        @JvmStatic
        @DynamicPropertySource
        fun editing(registry: DynamicPropertyRegistry) {
            registry.add("memo.docedit.document-server-url") { "http://127.0.0.1:${server.address.port}" }
            registry.add("memo.docedit.jwt-secret") { SECRET }
            registry.add("memo.docedit.internal-port") { INTERNAL_PORT }
        }

        @JvmStatic
        @AfterAll
        fun stop() = server.stop(0)
    }
}
