package com.memo.platform

import com.memo.common.tenant.TenantId
import com.memo.common.tenant.TenantTransactions
import com.memo.identity.UserAccounts
import com.memo.identity.UserStatus
import com.memo.identity.operator.OperatorMfa
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.Audit
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.aPassword
import com.memo.testsupport.anOperator
import com.memo.testsupport.operatorSession
import com.memo.testsupport.platformGet
import com.memo.testsupport.platformPost
import com.memo.testsupport.platformSend
import com.memo.testsupport.query
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MockMvcTester
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** Operators create and manage tenants (ADR-0031; test plan T2). */
class PlatformTenantsIT : IntegrationTest() {
    @Autowired lateinit var mvc: MockMvcTester

    @Autowired lateinit var mfa: OperatorMfa

    @Autowired lateinit var accounts: UserAccounts

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var json: JsonMapper

    private fun slug() = "acme-${UUID.randomUUID().toString().take(8)}"

    private fun create(
        session: Cookie,
        slug: String,
        adminEmail: String = "first-${UUID.randomUUID().toString().take(8)}@acme.example",
    ) = mvc.platformPost(
        "/api/v1/platform/tenants",
        session,
        """{"slug":"$slug","name":"Acme Bank","locale":"en","timeZone":"Asia/Dhaka","adminEmail":"$adminEmail","adminName":"Ada Admin"}""",
    )

    @Test
    fun `creating a tenant invites its first admin, records both chains and keeps the admin's address out of the platform chain`() {
        val session = mvc.operatorSession(mfa.anOperator())
        val slug = slug()
        val email = "first-${UUID.randomUUID().toString().take(8)}@acme.example"

        val created = create(session, slug, email)

        assertThat(created).hasStatus(HttpStatus.CREATED)
        val id = TenantId(UUID.fromString(json.readTree(created.response.contentAsString)["id"].asString()))
        val admin = transactions.inTenant(id) { requireNotNull(accounts.findByEmail(email)) }
        assertThat(admin.status).isEqualTo(UserStatus.INVITED)
        val mails = AppRoleJdbc.inTransaction(id) { c -> c.query("select template from notification_mail_outbox") { it.getString(1) } }
        assertThat(mails).contains("INVITATION")
        val settings = AppRoleJdbc.inTransaction(id) { c -> c.query("select key || '=' || value from tenancy_setting") { it.getString(1) } }
        assertThat(settings).contains("locale.default=en", "default.timezone=Asia/Dhaka")

        val tenantChain = Audit.entries(id)
        assertThat(tenantChain.map { it.action }).contains("tenant.created", "identity.user.invited")
        assertThat(tenantChain.single { it.action == "identity.user.invited" }.actorType).isEqualTo("PLATFORM_OPERATOR")
        val platformChain = Audit.platformEntries().filter { it.objectId == id.toString() }
        assertThat(platformChain.map { it.action }).contains("tenant.created", "tenant.first_admin.invited")
        assertThat(platformChain.joinToString { it.canonical }).doesNotContain(email)

        val view = mvc.platformGet("/api/v1/platform/tenants/$id", session)
        assertThat(json.readTree(view.response.contentAsString)["hasAdmin"].asBoolean()).isFalse()
        val listed = mvc.platformGet("/api/v1/platform/tenants?q=$slug", session)
        assertThat(listed.response.contentAsString).contains(slug)
    }

    @Test
    fun `reserved, malformed and taken slugs are refused`() {
        val session = mvc.operatorSession(mfa.anOperator())
        val taken = slug()
        assertThat(create(session, taken)).hasStatus(HttpStatus.CREATED)

        listOf("admin" to "RESERVED", "Bad_Slug" to "FORMAT", taken to "TAKEN").forEach { (slug, code) ->
            val refused = create(session, slug)
            assertThat(refused).hasStatus(HttpStatus.BAD_REQUEST)
            assertThat(refused.response.contentAsString).contains(code)
        }
    }

    @Test
    fun `the first admin cannot be invited again once the tenant has an active admin`() {
        val session = mvc.operatorSession(mfa.anOperator())
        val email = "first-${UUID.randomUUID().toString().take(8)}@acme.example"
        val id = json.readTree(create(session, slug(), email).response.contentAsString)["id"].asString()
        val tenant = TenantId(UUID.fromString(id))
        val body = """{"email":"$email","displayName":"Ada Admin"}"""

        // Still INVITED: a new link is sent.
        assertThat(mvc.platformPost("/api/v1/platform/tenants/$id/first-admin", session, body)).hasStatus(HttpStatus.NO_CONTENT)

        transactions.inTenant(tenant) { accounts.setPassword(requireNotNull(accounts.findByEmail(email)).id, aPassword()) }

        val refused =
            mvc.platformPost(
                "/api/v1/platform/tenants/$id/first-admin",
                session,
                """{"email":"x@acme.example","displayName":"X"}""",
            )
        assertThat(refused).hasStatus(HttpStatus.CONFLICT)
        assertThat(json.readTree(mvc.platformGet("/api/v1/platform/tenants/$id", session).response.contentAsString)["hasAdmin"].asBoolean())
            .isTrue()
    }

    @Test
    fun `renaming needs the current version, and the tenant's own chain shows the change`() {
        val session = mvc.operatorSession(mfa.anOperator())
        val created = json.readTree(create(session, slug()).response.contentAsString)
        val id = created["id"].asString()
        val version = created["version"].asLong()

        val stale =
            mvc.platformSend(
                HttpMethod.PATCH,
                "/api/v1/platform/tenants/$id",
                session,
                """{"name":"Acme"}""",
                mapOf("If-Match" to "\"${version + 7}\""),
            )
        val renamed =
            mvc.platformSend(
                HttpMethod.PATCH,
                "/api/v1/platform/tenants/$id",
                session,
                """{"name":"Acme"}""",
                mapOf("If-Match" to "\"$version\""),
            )

        assertThat(stale.response.status).isIn(HttpStatus.CONFLICT.value(), HttpStatus.PRECONDITION_FAILED.value())
        assertThat(renamed).hasStatus(HttpStatus.OK)
        assertThat(Audit.entries(TenantId(UUID.fromString(id))).map { it.action }).contains("tenant.renamed")
    }
}
