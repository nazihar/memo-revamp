package com.memo.testsupport

import com.memo.authz.internal.ClassificationAdmin
import com.memo.common.authz.ClassificationLevelRef
import com.memo.common.authz.Clearances
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.identity.UserAccount
import com.memo.identity.UserAccounts
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.test.web.servlet.assertj.MvcTestResult
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** A signed-in person of a memo test: the account and its password session. */
data class Person(
    val account: UserAccount,
    val session: Cookie,
) {
    val id: UUID get() = account.id.value
}

/**
 * Memo tests over HTTP (test plan E01): a fresh tenant where only administrators need MFA, a tenant admin for set-up
 * calls, people signed in with a password, and the memo endpoints as one-liners.
 */
abstract class MemoIntegrationTest : FilesIntegrationTest() {
    @Autowired lateinit var mvc: MockMvcTester

    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var accounts: UserAccounts

    @Autowired lateinit var classification: ClassificationAdmin

    @Autowired lateinit var clearances: Clearances

    @Autowired lateinit var json: JsonMapper

    lateinit var tenant: TestTenant
    lateinit var admin: UserAccount

    @BeforeEach
    fun memoTenant() {
        tenant = registry.aTestTenant(transactions, settings, "memo", mfaRequired = false)
        admin = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
    }

    fun person(clearance: Int = 0): Person {
        val password = aPassword()
        val account = accounts.anActiveUser(transactions, tenant, password)
        if (clearance > 0) clear(account.id.value, clearance)
        return Person(account, mvc.signIn(tenant, account.email, password))
    }

    fun levels(): List<ClassificationLevelRef> = asUser(admin) { clearances.levels() }

    fun clear(
        user: UUID,
        rank: Int,
    ) {
        asUser(admin) { classification.setClearance(user, levels()[rank].id) }
    }

    fun generalType(session: Cookie): String =
        read("/api/v1/memo-types", session).json().let { types ->
            (0 until types.size()).map { types[it] }.first { it["name"].asString() == "General memo" }["id"].asString()
        }

    fun create(
        session: Cookie,
        body: String,
        key: String = UUID.randomUUID().toString(),
    ) = mvc.sendJson(HttpMethod.POST, tenant, "/api/v1/memos", session, body, mapOf("Idempotency-Key" to key))

    /** A draft of [initiator] with the General type, classified at [rank]; returns its id. */
    fun aDraft(
        initiator: Person,
        rank: Int = 0,
    ): String {
        val level = levels()[rank].id
        return create(initiator.session, """{"subject":"Memo","typeId":"${generalType(initiator.session)}","classificationId":"$level"}""")
            .json()["id"]
            .asString()
    }

    fun post(
        uri: String,
        session: Cookie,
        body: String = "{}",
        key: String = UUID.randomUUID().toString(),
    ) = mvc.sendJson(HttpMethod.POST, tenant, uri, session, body, mapOf("Idempotency-Key" to key))

    fun put(
        uri: String,
        session: Cookie,
        body: String,
        version: Long?,
    ) = mvc.sendJson(HttpMethod.PUT, tenant, uri, session, body, version?.let { mapOf("If-Match" to "\"$it\"") }.orEmpty())

    fun get(
        id: String,
        session: Cookie,
    ) = read("/api/v1/memos/$id", session)

    fun read(
        uri: String,
        session: Cookie,
    ): MvcTestResult =
        mvc
            .get()
            .uri(uri)
            .with(onHost(tenant.slug))
            .cookie(session)
            .exchange()

    /** Sets the route at the memo's current version. */
    fun route(
        id: String,
        initiator: Person,
        vararg approvers: Person,
    ): MvcTestResult {
        val version = get(id, initiator.session).json()["version"].asLong()
        return put("/api/v1/memos/$id/route", initiator.session, routeBody(approvers.map { it.id }), version)
    }

    fun routeBody(approvers: List<UUID>) = """{"approvers":[${approvers.joinToString(",") { "\"$it\"" }}]}"""

    /** Uploads a DOCX as the memo's document: its first file, or a new version once it has one. */
    fun uploadDocument(
        id: String,
        session: Cookie,
        bytes: ByteArray = Samples.docx(),
        name: String = "memo.docx",
    ): MvcTestResult {
        val document = get(id, session).json()["document"]
        return if (document == null || document.isNull) {
            upload(session, bytes, "/api/v1/files?purpose=memo.document&owner=$id", name)
        } else {
            upload(
                session,
                bytes,
                "/api/v1/files/${document["fileId"].asString()}/versions",
                name,
                mapOf("If-Match" to "\"${document["fileVersion"]}\""),
            )
        }
    }

    fun upload(
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

    fun MvcTestResult.json(): JsonNode = json.readTree(response.contentAsString)

    /** The API leaves null fields out: absent and null both count as no value. */
    fun JsonNode.absent(field: String): Boolean = get(field)?.isNull ?: true

    /** The `code` of each entry of a 422 `errors` list, as `field:code`. */
    fun MvcTestResult.errors(): List<String> {
        val errors = json()["errors"]
        return (0 until errors.size()).map { "${errors[it]["field"].asString()}:${errors[it]["code"].asString()}" }
    }

    fun <T> asUser(
        user: UserAccount,
        block: () -> T,
    ): T =
        ActorContext.call(ActorScope(Actor.User(user.id.value), null)) {
            TenantContext.call(tenant.id) { transactions.inTenant(tenant.id, block) }
        }
}
