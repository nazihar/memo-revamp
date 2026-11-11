package com.memo.docedit

import com.memo.common.context.ActorContext
import com.memo.common.tenant.TenantContext
import com.memo.docedit.internal.EditSessionExpiry
import com.memo.docedit.internal.Hs256
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.Audit
import com.memo.testsupport.MemoIntegrationTest
import com.memo.testsupport.Person
import com.memo.testsupport.Samples
import com.memo.testsupport.onHost
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.assertj.MvcTestResult
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.JsonNode
import java.net.InetSocketAddress
import java.time.Clock
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * In-browser editing against a fake document server (E02 test plan, T2/T3): who may open the editor, the signed
 * download link, callbacks with good and bad tokens, saves through the files pipeline as the editor's user, refused
 * foreign download hosts, submit refused while editing, a save that arrives after submit, and the two connectors kept
 * apart.
 */
class DocEditIT : MemoIntegrationTest() {
    @Autowired lateinit var expiry: EditSessionExpiry

    private val jwt by lazy { Hs256(SECRET, json, Clock.systemUTC()) }

    /** The memo's document file, as its initiator sees it. */
    private fun fileOf(
        id: String,
        initiator: Person,
    ): String = get(id, initiator.session).json()["document"]["fileId"].asString()

    private fun openEditor(
        fileId: String,
        person: Person,
        mode: String = "EDIT",
    ): MvcTestResult = post("/api/v1/files/$fileId/edit-sessions", person.session, """{"mode":"$mode","language":"en"}""")

    private fun internal(port: Int = INTERNAL_PORT) =
        RequestPostProcessor {
            it.localPort = port
            it
        }

    private fun callback(
        config: JsonNode,
        status: Int,
        url: String? = SAVED_URL,
        signWith: Hs256 = jwt,
    ): JsonNode {
        val callbackUrl = config["editorConfig"]["callbackUrl"].asString()
        val payload = mapOf("key" to config["document"]["key"].asString(), "status" to status, "url" to url)
        val body = json.writeValueAsString(payload + ("token" to signWith.sign(payload)))
        val result =
            mvc
                .post()
                .uri(callbackUrl.substringAfter("8082"))
                .with(internal())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange()
        assertThat(result).hasStatusOk()
        return result.json()
    }

    private fun draftWithDocument(alice: Person): String {
        val id = aDraft(alice)
        uploadDocument(id, alice.session)
        return id
    }

    @Test
    fun `the initiator edits a draft, approvers and others only view what they may see`() {
        val alice = person()
        val bob = person()
        val stranger = person()
        val id = draftWithDocument(alice)
        val file = fileOf(id, alice)

        val opened = openEditor(file, alice)
        assertThat(opened).hasStatusOk()
        val config = opened.json()["config"]
        assertThat(opened.json()["scriptUrl"].asString()).isEqualTo("/office/web-apps/apps/api/documents/api.js")
        assertThat(config["document"]["permissions"]["edit"].asBoolean()).isTrue()
        assertThat(config["document"]["permissions"]["download"].asBoolean()).isFalse()
        assertThat(config["document"]["key"].asString()).matches("^m[A-Z2-7]{40}$")
        assertThat(config["document"]["url"].asString()).startsWith("http://backend:8082/internal/docedit/")
        assertThat(jwt.verify(config["token"].asString())["document"]).isNotNull()
        // Opening again resumes the same session (one open edit session per file and user).
        assertThat(openEditor(file, alice).json()["sessionId"].asString()).isEqualTo(opened.json()["sessionId"].asString())
        Audit.assertAudited(tenant.id, "docedit.session.opened", opened.json()["sessionId"].asString(), alice.id.toString())

        assertThat(openEditor(file, stranger)).hasStatus(HttpStatus.NOT_FOUND)
        callback(config, STATUS_CLOSED)
        Audit.assertAudited(tenant.id, "docedit.session.closed", opened.json()["sessionId"].asString())
        route(id, alice, bob)
        assertThat(post("/api/v1/memos/$id/submit", alice.session)).hasStatusOk()
        assertThat(openEditor(file, bob, mode = "EDIT")).hasStatus(HttpStatus.FORBIDDEN)
        val view = openEditor(file, bob, mode = "VIEW")
        assertThat(view).hasStatusOk()
        assertThat(view.json()["config"]["editorConfig"]["mode"].asString()).isEqualTo("view")
        assertThat(openEditor(file, alice, mode = "EDIT")).hasStatus(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `the document is served only through a valid signed link with the server's token, on the internal port`() {
        val alice = person()
        val id = draftWithDocument(alice)
        val file = fileOf(id, alice)
        val config = openEditor(file, alice).json()["config"]
        val path = config["document"]["url"].asString().substringAfter("8082")
        val token = "Bearer ${jwt.sign(mapOf("url" to path))}"

        val served =
            mvc
                .get()
                .uri(path)
                .with(internal())
                .header("Authorization", token)
                .exchange()
        assertThat(served).hasStatusOk()
        assertThat(documentXml(served.response.contentAsByteArray)).isEqualTo("<doc/>")
        assertThat(
            mvc
                .get()
                .uri(path)
                .with(internal())
                .exchange(),
        ).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(
            mvc
                .get()
                .uri(path.replace("sig=", "sig=x"))
                .with(internal())
                .header("Authorization", token)
                .exchange(),
        ).hasStatus(HttpStatus.FORBIDDEN)
        // The public port does not know the internal paths, and the internal port knows nothing else.
        assertThat(
            mvc
                .get()
                .uri(path)
                .with(onHost(tenant.slug))
                .header("Authorization", token)
                .exchange(),
        ).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(
            mvc
                .get()
                .uri("/api/v1/health")
                .with(internal())
                .exchange(),
        ).hasStatus(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `closing the editor with changes stores a new version as the editor, once, fetched from the document server's cache only`() {
        val alice = person()
        val id = draftWithDocument(alice)
        val file = fileOf(id, alice)
        val opened = openEditor(file, alice).json()
        val config = opened["config"]

        assertThat(callback(config, STATUS_SAVE, signWith = Hs256("x".repeat(40), json, Clock.systemUTC()))["error"].asInt()).isEqualTo(1)
        assertThat(callback(config, STATUS_SAVE, url = "https://evil.example/office/admin/secret")["error"].asInt()).isEqualTo(1)
        assertThat(callback(config, STATUS_SAVE, url = "https://evil.example/office/cache/../admin")["error"].asInt())
            .isEqualTo(1)
        assertThat(get(id, alice.session).json()["document"]["number"].asInt()).isEqualTo(1)

        assertThat(callback(config, STATUS_SAVE)["error"].asInt()).isZero()
        val document = get(id, alice.session).json()["document"]
        assertThat(document["number"].asInt()).isEqualTo(2)
        assertThat(versionUploader(document["versionId"].asString())).isEqualTo(alice.id)
        val session = read("/api/v1/edit-sessions/${opened["sessionId"].asString()}", alice.session).json()
        assertThat(session["state"].asString()).isEqualTo("SAVED")
        Audit.assertAudited(tenant.id, "docedit.version.saved", opened["sessionId"].asString())
        Audit.assertAudited(tenant.id, "docedit.callback.refused", opened["sessionId"].asString())

        callback(config, STATUS_SAVE)
        assertThat(get(id, alice.session).json()["document"]["number"].asInt()).isEqualTo(2)
    }

    @Test
    fun `submit waits for the editor, and a save after submit is refused and reported`() {
        val alice = person()
        val bob = person()
        val id = draftWithDocument(alice)
        val file = fileOf(id, alice)
        route(id, alice, bob)
        val opened = openEditor(file, alice).json()
        val refused = post("/api/v1/memos/$id/submit", alice.session)
        assertThat(refused).hasStatus(HttpStatus.CONFLICT)
        assertThat(refused.json()["reason"].asString()).isEqualTo("DOCUMENT_BEING_EDITED")

        // The editor was left open past its lifetime: it no longer blocks submit, and its late save does not land.
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            val backdate =
                "update docedit_session set created_at = created_at - interval '13 hours', " +
                    "expires_at = created_at - interval '1 hour'"
            c.prepareStatement("$backdate where id = ?").use {
                it.setObject(1, UUID.fromString(opened["sessionId"].asString()))
                assertThat(it.executeUpdate()).isEqualTo(1)
            }
            c.commit()
        }
        assertThat(ActorContext.asSystem("test") { TenantContext.call(tenant.id) { expiry.expireDue() } }).isEqualTo(1)
        val sessionUrl = "/api/v1/edit-sessions/${opened["sessionId"].asString()}"
        assertThat(read(sessionUrl, alice.session).json()["state"].asString()).isEqualTo("EXPIRED")
        assertThat(post("/api/v1/memos/$id/submit", alice.session)).hasStatusOk()
        assertThat(callback(opened["config"], STATUS_SAVE)["error"].asInt()).isZero()
        assertThat(get(id, alice.session).json()["document"]["number"].asInt()).isEqualTo(1)
        val session = read(sessionUrl, alice.session).json()
        assertThat(session["state"].asString()).isEqualTo("FAILED")
        assertThat(session["reason"].asString()).isEqualTo("LOCKED")
        val told = read("/api/v1/me/notifications", alice.session).json()["items"].first()
        assertThat(told["kind"].asString()).isEqualTo("EDIT_NOT_SAVED")
        assertThat(told["params"]["name"].asString()).isNotBlank()
    }

    @Test
    fun `a save after a newer version was uploaded is refused instead of overwriting it`() {
        val alice = person()
        val id = draftWithDocument(alice)
        val opened = openEditor(fileOf(id, alice), alice).json()
        assertThat(uploadDocument(id, alice.session, Samples.docx("<doc>uploaded meanwhile</doc>"))).hasStatus2xxSuccessful()

        assertThat(callback(opened["config"], STATUS_SAVE)["error"].asInt()).isZero()
        assertThat(get(id, alice.session).json()["document"]["number"].asInt()).isEqualTo(2)
        val session = read("/api/v1/edit-sessions/${opened["sessionId"].asString()}", alice.session).json()
        assertThat(session["state"].asString()).isEqualTo("FAILED")
        assertThat(session["reason"].asString()).isEqualTo("SUPERSEDED")
    }

    @Test
    fun `editing is reported as available, and the memo offers it where a new version may be uploaded`() {
        val alice = person()
        val id = draftWithDocument(alice)
        assertThat(read("/api/v1/files/editing", alice.session).json()["enabled"].asBoolean()).isTrue()
        assertThat(get(id, alice.session).json()["allowedActions"].toString()).contains("EDIT_DOCUMENT", "VIEW_DOCUMENT")
    }

    private fun documentXml(docx: ByteArray): String =
        ZipInputStream(docx.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.first { it.name == "word/document.xml" }
            zip.readBytes().decodeToString()
        }

    private fun versionUploader(versionId: String): UUID =
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            c.prepareStatement("select uploaded_by from files_version where id = ?").use {
                it.setObject(1, UUID.fromString(versionId))
                it.executeQuery().use { rs -> rs.next().let { rs.getObject(1, UUID::class.java) } }
            }
        }

    companion object {
        private const val SECRET = "docedit-test-secret-of-at-least-32-bytes!"
        private const val INTERNAL_PORT = 8082
        private const val STATUS_SAVE = 2
        private const val STATUS_CLOSED = 4

        /** The fake document server: serves the "edited" document from its cache path. */
        private val server: HttpServer =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/cache/edited.docx") { exchange ->
                    val bytes = Samples.docx("<doc>edited in the browser</doc>")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                start()
            }

        // As ONLYOFFICE names it: on the address browsers use (the host is ignored; the path is fetched from the document server).
        private const val SAVED_URL = "https://tenant.example/office/cache/edited.docx"

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
