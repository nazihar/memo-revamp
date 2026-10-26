package com.memo.identity.admin

import com.memo.common.crypto.DataKeys
import com.memo.common.tenant.TenantTransactions
import com.memo.identity.LifecycleRefusedException
import com.memo.identity.MfaSeeding
import com.memo.identity.UserAccount
import com.memo.identity.UserAccounts
import com.memo.identity.UserId
import com.memo.identity.UserLifecycle
import com.memo.identity.mfa.Totp
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.MailOutbox
import com.memo.testsupport.TestTenant
import com.memo.testsupport.aClientIp
import com.memo.testsupport.aPassword
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.ageSessionFactors
import com.memo.testsupport.anActiveUser
import com.memo.testsupport.csrfToken
import com.memo.testsupport.fromIp
import com.memo.testsupport.login
import com.memo.testsupport.onHost
import com.memo.testsupport.postJson
import com.memo.testsupport.sendingCsrf
import com.memo.testsupport.sessionCookie
import com.memo.testsupport.signIn
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.test.web.servlet.assertj.MvcTestResult
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/** User administration API (design E03 §3, §4; test plan T8). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserAdminApiIT : IntegrationTest() {
    @Autowired lateinit var mvc: MockMvcTester

    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var accounts: UserAccounts

    @Autowired lateinit var lifecycle: UserLifecycle

    @Autowired lateinit var mfaSeeding: MfaSeeding

    @Autowired lateinit var keys: DataKeys

    @Autowired lateinit var json: JsonMapper

    private val password = aPassword()

    /** Users sign in with a password; admins always with TOTP. */
    private val tenant: TestTenant by lazy { registry.aTestTenant(transactions, settings, "admin", mfaRequired = false) }

    private val outbox by lazy { MailOutbox(keys, json) }

    private val admin: UserAccount by lazy { accounts.anActiveUser(transactions, tenant, password, tenantAdmin = true) }

    /** The admin's complete session (password + TOTP), shared by the tests. */
    private val adminSession: Cookie by lazy {
        val secret = Totp.base32(Totp.newSecret())
        transactions.inTenant(tenant.id) { mfaSeeding.ensureTotp(admin.id, secret) }
        val partial = mvc.login(tenant, admin.email, password).sessionCookie()
        val code = Totp.code(Totp.decodeBase32(secret), Totp.step(Instant.now()))
        mvc.postJson(tenant, "/api/v1/auth/mfa", partial, """{"code":"$code"}""").sessionCookie(partial)!!
    }

    private fun get(
        uri: String,
        cookie: Cookie = adminSession,
    ) = mvc
        .get()
        .uri(uri)
        .with(onHost(tenant.slug))
        .cookie(cookie)
        .exchange()

    private fun action(
        user: UserAccount,
        action: String,
        version: Long? = user.version,
        cookie: Cookie = adminSession,
    ): MvcTestResult =
        mvc
            .post()
            .uri("/api/v1/admin/users/${user.id}/$action")
            .with(onHost(tenant.slug))
            .with(fromIp(aClientIp()))
            .with(sendingCsrf(mvc.csrfToken(tenant), cookie))
            .apply { if (version != null) header("If-Match", "\"$version\"") }
            .exchange()

    private fun invite(
        key: String?,
        body: String,
    ): MvcTestResult =
        mvc
            .post()
            .uri("/api/v1/admin/users")
            .with(onHost(tenant.slug))
            .with(fromIp(aClientIp()))
            .with(sendingCsrf(mvc.csrfToken(tenant), adminSession))
            .apply { if (key != null) header("Idempotency-Key", key) }
            .contentType(MediaType.APPLICATION_JSON)
            .content(body)
            .exchange()

    private fun state(cookie: Cookie) =
        mvc
            .get()
            .uri("/api/v1/auth/session")
            .with(onHost(tenant.slug))
            .cookie(cookie)
            .exchange()

    private fun reload(user: UserAccount) = transactions.inTenant(tenant.id) { accounts.find(user.id)!! }

    @Test
    fun `only tenant admins with a complete session reach user administration`() {
        val user = accounts.anActiveUser(transactions, tenant, password)
        val userSession = mvc.signIn(tenant, user.email, password)
        val otherAdmin = accounts.anActiveUser(transactions, tenant, password, tenantAdmin = true)
        val partialAdmin = mvc.login(tenant, otherAdmin.email, password).sessionCookie()!!

        assertThat(get("/api/v1/admin/users", userSession)).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(get("/api/v1/admin/users", partialAdmin)).hasStatus(HttpStatus.UNAUTHORIZED)
        assertThat(get("/api/v1/admin/users")).hasStatus(HttpStatus.OK)
    }

    @Test
    fun `the list pages by name without gaps or repeats, and filters`() {
        val tag = UUID.randomUUID().toString().take(6)
        val names = listOf("Aya", "Bilal", "Chen", "Dana", "Emre").map { "$tag $it" }
        names.forEach { name ->
            transactions.inTenant(tenant.id) { accounts.create("${name.replace(' ', '.').lowercase()}@bank.example", name) }
        }

        val seen = mutableListOf<String>()
        var cursor: String? = null
        do {
            val page = get("/api/v1/admin/users?q=$tag&limit=2" + (cursor?.let { "&cursor=$it" } ?: ""))
            val body = json.readTree(page.response.contentAsString)
            body.at("/items").forEach { seen += it.at("/displayName").asString() }
            cursor = body.at("/nextCursor").takeIf { !it.isMissingNode && !it.isNull }?.asString()
        } while (cursor != null)

        assertThat(seen).containsExactlyElementsOf(names)
        assertThat(get("/api/v1/admin/users?q=$tag&status=ACTIVE")).bodyJson().extractingPath("$.items.length()").isEqualTo(0)
        assertThat(get("/api/v1/admin/users?q=$tag&status=INVITED")).bodyJson().extractingPath("$.items.length()").isEqualTo(5)
    }

    @Test
    fun `inviting is idempotent per key and refuses a reused key or a taken email`() {
        val email = "invitee-${UUID.randomUUID().toString().take(8)}@bank.example"
        val body = """{"email":"$email","displayName":"Leila Nasser","tenantAdmin":false}"""
        val key = UUID.randomUUID().toString()

        val first = invite(key, body)
        val second = invite(key, body)

        assertThat(first).hasStatus(HttpStatus.CREATED)
        assertThat(second).hasStatus(HttpStatus.CREATED)
        assertThat(second).bodyJson().extractingPath("$.id").isEqualTo(json.readTree(first.response.contentAsString).at("/id").asString())
        assertThat(outbox.all(tenant).count { it.template == "INVITATION" && it.to == email }).isOne()
        assertThat(invite(key, body.replace("Leila", "Lina"))).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
        assertThat(invite(UUID.randomUUID().toString(), body)).hasStatus(HttpStatus.CONFLICT)
        assertThat(invite(null, body)).hasStatus(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `deactivation needs the current version, signs the user out, and can be undone`() {
        val user = accounts.anActiveUser(transactions, tenant, password)
        val userSession = mvc.signIn(tenant, user.email, password)

        assertThat(action(user, "deactivate", version = null)).hasStatus(HttpStatus.PRECONDITION_REQUIRED)
        assertThat(action(user, "deactivate", version = user.version + 7)).hasStatus(HttpStatus.PRECONDITION_FAILED)
        val deactivated = action(user, "deactivate")
        assertThat(deactivated).hasStatus(HttpStatus.OK)
        assertThat(deactivated).bodyJson().extractingPath("$.status").isEqualTo("DEACTIVATED")
        assertThat(deactivated.response.getHeader("ETag")).isEqualTo("\"${user.version + 1}\"")

        assertThat(state(userSession)).bodyJson().extractingPath("$.state").isEqualTo("ANONYMOUS")
        assertThat(mvc.login(tenant, user.email, password)).hasStatus(HttpStatus.UNAUTHORIZED)

        assertThat(action(reload(user), "reactivate")).bodyJson().extractingPath("$.status").isEqualTo("ACTIVE")
        assertThat(mvc.login(tenant, user.email, password)).hasStatus(HttpStatus.OK)
    }

    @Test
    fun `an admin cannot deactivate themselves, and the last active admin stays`() {
        assertThat(action(reload(admin), "deactivate")).hasStatus(HttpStatus.CONFLICT)
        assertThat(action(reload(admin), "deactivate")).bodyJson().extractingPath("$.reason").isEqualTo("SELF")

        val solo = registry.aTestTenant(transactions, settings, "admin-solo")
        val onlyAdmin = accounts.anActiveUser(transactions, solo, password, tenantAdmin = true)
        assertThatThrownBy {
            transactions.inTenant(
                solo.id,
            ) { lifecycle.deactivate(onlyAdmin.id, onlyAdmin.version, UserId(UUID.randomUUID())) }
        }.isInstanceOfSatisfying(LifecycleRefusedException::class.java) { assertThat(it.reason).isEqualTo("LAST_ADMIN") }
    }

    @Test
    fun `unlock lifts a lock, and actions in the wrong state are refused`() {
        val user = accounts.anActiveUser(transactions, tenant, password)
        assertThat(action(user, "unlock")).bodyJson().extractingPath("$.reason").isEqualTo("STATUS")
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            c
                .prepareStatement(
                    "update identity_user set status = 'LOCKED', locked_until = now() + interval '30 minutes' where id = ?",
                ).use {
                    it.setObject(1, user.id.value)
                    it.executeUpdate()
                }
            c.commit()
        }

        assertThat(action(reload(user), "unlock")).bodyJson().extractingPath("$.status").isEqualTo("ACTIVE")
        assertThat(mvc.login(tenant, user.email, password)).hasStatus(HttpStatus.OK)
    }

    @Test
    fun `an MFA reset makes the user enrol again and tells them`() {
        val otherAdmin = accounts.anActiveUser(transactions, tenant, password, tenantAdmin = true)
        transactions.inTenant(tenant.id) { mfaSeeding.ensureTotp(otherAdmin.id, Totp.base32(Totp.newSecret())) }
        assertThat(mvc.login(tenant, otherAdmin.email, password)).bodyJson().extractingPath("$.state").isEqualTo("MFA_REQUIRED")

        assertThat(action(reload(otherAdmin), "mfa-reset")).hasStatus(HttpStatus.OK)

        assertThat(mvc.login(tenant, otherAdmin.email, password)).bodyJson().extractingPath("$.state").isEqualTo("MFA_ENROLMENT_REQUIRED")
        assertThat(outbox.all(tenant).map { it.template to it.to }).contains("MFA_RESET" to otherAdmin.email)
    }

    @Test
    fun `revoking a user's sessions signs them out everywhere`() {
        val user = accounts.anActiveUser(transactions, tenant, password)
        val sessions = listOf(mvc.signIn(tenant, user.email, password), mvc.signIn(tenant, user.email, password))

        assertThat(action(user, "sessions/revoke", version = null)).hasStatus(HttpStatus.NO_CONTENT)

        assertThat(sessions).allSatisfy { assertThat(state(it)).bodyJson().extractingPath("$.state").isEqualTo("ANONYMOUS") }
    }

    @Test
    fun `admin mutations need a fresh step-up`() {
        val other = accounts.anActiveUser(transactions, tenant, password, tenantAdmin = true)
        val secret = Totp.base32(Totp.newSecret())
        transactions.inTenant(tenant.id) { mfaSeeding.ensureTotp(other.id, secret) }
        val partial = mvc.login(tenant, other.email, password).sessionCookie()
        val session =
            mvc
                .postJson(
                    tenant,
                    "/api/v1/auth/mfa",
                    partial,
                    """{"code":"${Totp.code(Totp.decodeBase32(secret), Totp.step(Instant.now()))}"}""",
                ).sessionCookie(partial)!!
        val target = accounts.anActiveUser(transactions, tenant, password)
        ageSessionFactors(session)

        val refused = action(target, "deactivate", cookie = session)

        assertThat(refused).hasStatus(HttpStatus.UNAUTHORIZED)
        assertThat(refused).bodyJson().extractingPath("$.type").isEqualTo("urn:memo:problem:step-up-required")
    }
}
