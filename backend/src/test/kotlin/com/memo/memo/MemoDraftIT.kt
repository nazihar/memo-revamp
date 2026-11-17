package com.memo.memo

import com.memo.authz.internal.ClassificationAdmin
import com.memo.common.authz.Clearances
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.identity.UserAccount
import com.memo.identity.UserAccounts
import com.memo.memo.internal.MemoDocumentPurpose
import com.memo.memo.internal.MemoTypeFields
import com.memo.memo.internal.MemoTypes
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.Audit
import com.memo.testsupport.FilesIntegrationTest
import com.memo.testsupport.Samples
import com.memo.testsupport.TestTenant
import com.memo.testsupport.aClientIp
import com.memo.testsupport.aPassword
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.anActiveUser
import com.memo.testsupport.csrfToken
import com.memo.testsupport.fromIp
import com.memo.testsupport.onHost
import com.memo.testsupport.sendJson
import com.memo.testsupport.sendingCsrf
import com.memo.testsupport.signIn
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.test.web.servlet.assertj.MvcTestResult
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * Draft memos over HTTP (test plan E01, T3): create, unnumbered, with a classification between the type's default and
 * the initiator's clearance, edits behind `If-Match`, discard, idempotent creation bound to the actor, visibility (404
 * for others), and the `memo.document` purpose (DOCX only, one file per memo, initiator only while they hold the memo).
 */
class MemoDraftIT : FilesIntegrationTest() {
    @Autowired lateinit var mvc: MockMvcTester

    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var accounts: UserAccounts

    @Autowired lateinit var access: ClassificationAdmin

    @Autowired lateinit var clearances: Clearances

    @Autowired lateinit var types: MemoTypes

    @Autowired lateinit var json: JsonMapper

    private lateinit var tenant: TestTenant
    private lateinit var admin: UserAccount
    private lateinit var alice: UserAccount
    private lateinit var aliceSession: Cookie
    private lateinit var bobSession: Cookie

    @BeforeEach
    fun people() {
        tenant = registry.aTestTenant(transactions, settings, "memo", mfaRequired = false)
        admin = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
        val alicePassword = aPassword()
        alice = accounts.anActiveUser(transactions, tenant, alicePassword)
        aliceSession = mvc.signIn(tenant, alice.email, alicePassword)
        val bobPassword = aPassword()
        val bob = accounts.anActiveUser(transactions, tenant, bobPassword)
        bobSession = mvc.signIn(tenant, bob.email, bobPassword)
    }

    @Test
    fun `a draft is unnumbered until submit, takes the type's default classification, and only its initiator sees it`() {
        val general = generalType()
        val created = create(aliceSession, """{"subject":"Budget transfer","typeId":"$general"}""")
        assertThat(created).hasStatus(HttpStatus.CREATED)
        val memo = created.json()
        // Numbered when submitted (ADR-0044), so drafts no longer use up numbers.
        assertThat(memo.get("reference")?.isNull ?: true).isTrue()
        assertThat(memo["numberedWhen"].asString()).isEqualTo("ON_SUBMIT")
        assertThat(memo["status"].asString()).isEqualTo("DRAFT")
        assertThat(memo["classification"]["rank"].asInt()).isZero()
        assertThat(memo["initiator"]["id"].asString()).isEqualTo(alice.id.value.toString())
        assertThat(memo["allowedActions"].toString()).isEqualTo("""["EDIT","UPLOAD_DOCUMENT","EDIT_ROUTE","SUBMIT","DISCARD"]""")
        assertThat(created.response.getHeader(HttpHeaders.ETAG)).isEqualTo("\"0\"")
        val id = memo["id"].asString()

        assertThat(get(id, aliceSession)).hasStatusOk()
        assertThat(get(id, bobSession)).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(get(UUID.randomUUID().toString(), aliceSession)).hasStatus(HttpStatus.NOT_FOUND)
        Audit.assertAudited(tenant.id, "memo.created", id, alice.id.value.toString())
    }

    @Test
    fun `one Idempotency-Key creates one memo, and another user cannot replay it`() {
        val body = """{"subject":"Once","typeId":"${generalType()}"}"""
        val key = UUID.randomUUID().toString()
        val first = create(aliceSession, body, key).json()["id"].asString()
        val again = create(aliceSession, body, key)
        assertThat(again).hasStatus(HttpStatus.CREATED)
        assertThat(again.json()["id"].asString()).isEqualTo(first)
        assertThat(create(bobSession, body, key)).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
        assertThat(create(aliceSession, """{"subject":"Different","typeId":"${generalType()}"}""", key))
            .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
    }

    @Test
    fun `the classification must be at least the type's default and at most the initiator's clearance`() {
        val levels = asUser(admin) { clearances.levels() }
        val internalType =
            asUser(admin) { types.create(MemoTypeFields("Internal memo", levels[1].id, stepUpOnApprove = true, active = true)) }.id
        val subject = """"subject":"Classified","typeId":"$internalType""""

        // Alice is cleared to the lowest level only: the type's default is already above her clearance.
        assertThat(create(aliceSession, "{$subject}").json()["errors"][0]["code"].asString()).isEqualTo("above-clearance")
        asUser(admin) { access.setClearance(alice.id.value, levels[2].id) }

        val below = create(aliceSession, """{$subject,"classificationId":"${levels[0].id}"}""")
        assertThat(below).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
        assertThat(below.json()["errors"][0]["code"].asString()).isEqualTo("below-type-default")
        val above = create(aliceSession, """{$subject,"classificationId":"${levels[3].id}"}""")
        assertThat(above.json()["errors"][0]["code"].asString()).isEqualTo("above-clearance")
        assertThat(
            create(aliceSession, """{$subject,"classificationId":"${UUID.randomUUID()}"}"""),
        ).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)

        val defaulted = create(aliceSession, "{$subject}")
        assertThat(defaulted).hasStatus(HttpStatus.CREATED)
        assertThat(defaulted.json()["classification"]["rank"].asInt()).isEqualTo(1)
        val raised = create(aliceSession, """{$subject,"classificationId":"${levels[2].id}"}""")
        assertThat(raised.json()["classification"]["rank"].asInt()).isEqualTo(2)
        assertThat(create(aliceSession, """{"subject":"  ","typeId":"$internalType"}""")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
    }

    @Test
    fun `edits need the current version, only the initiator may make them, and a discarded draft is closed`() {
        val general = generalType()
        val id = create(aliceSession, """{"subject":"First","typeId":"$general"}""").json()["id"].asString()
        val body = """{"subject":"Second","typeId":"$general"}"""

        assertThat(put(id, aliceSession, body, null)).hasStatus(HttpStatus.PRECONDITION_REQUIRED)
        assertThat(put(id, aliceSession, body, 7)).hasStatus(HttpStatus.PRECONDITION_FAILED)
        assertThat(put(id, bobSession, body, 0)).hasStatus(HttpStatus.NOT_FOUND)
        val edited = put(id, aliceSession, body, 0)
        assertThat(edited).hasStatusOk()
        assertThat(edited.json()["subject"].asString()).isEqualTo("Second")
        assertThat(edited.response.getHeader(HttpHeaders.ETAG)).isEqualTo("\"1\"")
        Audit.assertAudited(tenant.id, "memo.updated", id, alice.id.value.toString())

        assertThat(post("/api/v1/memos/$id/discard", bobSession)).hasStatus(HttpStatus.NOT_FOUND)
        val discarded = post("/api/v1/memos/$id/discard", aliceSession)
        assertThat(discarded).hasStatusOk()
        assertThat(discarded.json()["status"].asString()).isEqualTo("DISCARDED")
        assertThat(discarded.json()["allowedActions"]).isEmpty()
        Audit.assertAudited(tenant.id, "memo.discarded", id, alice.id.value.toString())
        assertThat(put(id, aliceSession, body, 2)).hasStatus(HttpStatus.CONFLICT)
        assertThat(post("/api/v1/memos/$id/discard", aliceSession)).hasStatus(HttpStatus.CONFLICT)
    }

    @Test
    fun `the memo document is one DOCX file the initiator versions while they hold the memo, read by those who see the memo`() {
        val id = create(aliceSession, """{"subject":"With a document","typeId":"${generalType()}"}""").json()["id"].asString()
        val target = "/api/v1/files?purpose=${MemoDocumentPurpose.KEY}&owner=$id"

        assertThat(upload(bobSession, Samples.docx(), target)).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(upload(aliceSession, Samples.docm(), target, "memo.docx").response.status).isIn(400, 415, 422)
        assertThat(upload(aliceSession, Samples.pdf(), target, "memo.pdf").response.status).isIn(400, 415, 422)
        val uploaded = upload(aliceSession, Samples.docx(), target)
        assertThat(uploaded).hasStatus(HttpStatus.CREATED)
        val fileId = uploaded.json()["fileId"].asString()

        val document = get(id, aliceSession).json()["document"]
        assertThat(document["fileId"].asString()).isEqualTo(fileId)
        assertThat(document["number"].asInt()).isEqualTo(1)
        assertThat(document["sha256"].asString()).hasSize(64)
        // One file per memo: further uploads are versions of it.
        assertThat(upload(aliceSession, Samples.docx(), target)).hasStatus(HttpStatus.CONFLICT)
        val v2 =
            upload(
                aliceSession,
                Samples.docx(),
                "/api/v1/files/$fileId/versions",
                headers =
                    mapOf("If-Match" to "\"${document["fileVersion"]}\""),
            )
        assertThat(v2).hasStatus(HttpStatus.CREATED)
        assertThat(get(id, aliceSession).json()["document"]["number"].asInt()).isEqualTo(2)

        assertThat(read("/api/v1/files/$fileId/content", aliceSession)).hasStatusOk()
        assertThat(read("/api/v1/files/$fileId/content", bobSession).response.status).isIn(403, 404)
        Audit.assertAudited(
            tenant.id,
            "file.opened",
            get(id, aliceSession).json()["document"]["versionId"].asString(),
            alice.id.value.toString(),
        )

        post("/api/v1/memos/$id/discard", aliceSession)
        val fileVersion = get(id, aliceSession).json()["document"]["fileVersion"]
        assertThat(
            upload(aliceSession, Samples.docx(), "/api/v1/files/$fileId/versions", headers = mapOf("If-Match" to "\"$fileVersion\"")),
        ).hasStatus(HttpStatus.FORBIDDEN)
    }

    private fun generalType(): String =
        json
            .readTree(
                mvc
                    .get()
                    .uri("/api/v1/memo-types")
                    .with(onHost(tenant.slug))
                    .cookie(aliceSession)
                    .exchange()
                    .response.contentAsString,
            ).first { it["name"].asString() == "General memo" }["id"]
            .asString()

    private fun create(
        session: Cookie,
        body: String,
        key: String = UUID.randomUUID().toString(),
    ) = mvc.sendJson(HttpMethod.POST, tenant, "/api/v1/memos", session, body, mapOf("Idempotency-Key" to key))

    private fun post(
        uri: String,
        session: Cookie,
    ) = mvc.sendJson(HttpMethod.POST, tenant, uri, session, "{}", mapOf("Idempotency-Key" to UUID.randomUUID().toString()))

    private fun put(
        id: String,
        session: Cookie,
        body: String,
        version: Long?,
    ) = mvc.sendJson(HttpMethod.PUT, tenant, "/api/v1/memos/$id", session, body, version?.let { mapOf("If-Match" to "\"$it\"") }.orEmpty())

    private fun get(
        id: String,
        session: Cookie,
    ) = read("/api/v1/memos/$id", session)

    private fun read(
        uri: String,
        session: Cookie,
    ) = mvc
        .get()
        .uri(uri)
        .with(onHost(tenant.slug))
        .cookie(session)
        .exchange()

    private fun upload(
        session: Cookie,
        bytes: ByteArray,
        uri: String,
        name: String = "memo.docx",
        headers: Map<String, String> = emptyMap(),
    ): MvcTestResult =
        mvc
            .post()
            .uri(uri)
            .with(onHost(tenant.slug))
            .with(fromIp(aClientIp()))
            .with(sendingCsrf(mvc.csrfToken(tenant), session))
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$name\"")
            .headers { h -> headers.forEach { (header, value) -> h.add(header, value) } }
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .content(bytes)
            .exchange()

    private fun MvcTestResult.json(): JsonNode = json.readTree(response.contentAsString)

    private fun <T> asUser(
        user: UserAccount,
        block: () -> T,
    ): T =
        ActorContext.call(ActorScope(Actor.User(user.id.value), null)) {
            TenantContext.call(tenant.id) { transactions.inTenant(tenant.id, block) }
        }
}
