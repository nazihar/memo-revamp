package com.memo.identity.auth

import com.memo.common.tenant.TenantTransactions
import com.memo.identity.UserAccounts
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.Audit
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.SESSION_COOKIE
import com.memo.testsupport.TestTenant
import com.memo.testsupport.aPassword
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.anActiveUser
import com.memo.testsupport.csrfToken
import com.memo.testsupport.login
import com.memo.testsupport.onHost
import com.memo.testsupport.query
import com.memo.testsupport.sendingCsrf
import com.memo.testsupport.signIn
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.assertj.MockMvcTester
import java.util.Base64

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthIT : IntegrationTest() {
    @Autowired lateinit var mvc: MockMvcTester

    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var accounts: UserAccounts

    private val password = aPassword()

    /** MFA relaxed for non-admins, so a password completes the sign-in. */
    private val tenant: TestTenant by lazy { registry.aTestTenant(transactions, settings, "auth", mfaRequired = false) }

    private fun session(
        cookie: Cookie?,
        on: TestTenant = tenant,
    ) = mvc
        .get()
        .uri("/api/v1/auth/session")
        .with(onHost(on.slug))
        .apply { if (cookie != null) cookie(cookie) }
        .exchange()

    private fun sessionId(cookie: Cookie) = String(Base64.getDecoder().decode(cookie.value))

    private fun storedSessions(cookie: Cookie): Int =
        AppRoleJdbc
            .connect { c ->
                c.query("select count(*) from identity_http_session where session_id = ?", sessionId(cookie)) { it.getInt(1) }
            }.single()

    @Test
    fun `a password completes sign-in where MFA is not required, in a hardened session cookie`() {
        val user = accounts.anActiveUser(transactions, tenant, password)

        val result = mvc.login(tenant, user.email, password)

        assertThat(result).hasStatus(HttpStatus.OK)
        assertThat(result).bodyJson().extractingPath("$.state").isEqualTo("AUTHENTICATED")
        assertThat(result).bodyJson().extractingPath("$.user.email").isEqualTo(user.email)
        val cookie = result.response.getCookie(SESSION_COOKIE)!!
        assertThat(cookie.isHttpOnly).isTrue()
        assertThat(cookie.secure).isTrue()
        assertThat(cookie.domain).isNull()
        assertThat(result.response.getHeaders("Set-Cookie").single { it.startsWith(SESSION_COOKIE) }).contains("SameSite=Lax")
        // 256-bit random id, not a UUID.
        assertThat(sessionId(cookie)).matches("[A-Za-z0-9_-]{43}")
        assertThat(session(cookie)).bodyJson().extractingPath("$.state").isEqualTo("AUTHENTICATED")
        Audit.assertAudited(tenant.id, "identity.session.created", user.id.toString(), actorId = user.id.toString())
    }

    @Test
    fun `the session id changes on every sign-in`() {
        val user = accounts.anActiveUser(transactions, tenant, password)
        val first = mvc.signIn(tenant, user.email, password)

        val second = mvc.login(tenant, user.email, password, session = first).response.getCookie(SESSION_COOKIE)!!

        assertThat(sessionId(second)).isNotEqualTo(sessionId(first))
        assertThat(storedSessions(first)).isZero()
        assertThat(session(first)).bodyJson().extractingPath("$.state").isEqualTo("ANONYMOUS")
    }

    @Test
    fun `wrong password, unknown email and an invited account get the same answer`() {
        val user = accounts.anActiveUser(transactions, tenant, password)
        val invited = transactions.inTenant(tenant.id) { accounts.create("invited-${user.email}", "Invited User") }

        val answers =
            listOf(
                mvc.login(tenant, user.email, "$password-wrong"),
                mvc.login(tenant, "nobody-${user.email}", password),
                mvc.login(tenant, invited.email, password),
            )

        assertThat(answers).allSatisfy {
            assertThat(it).hasStatus(HttpStatus.UNAUTHORIZED)
            assertThat(it.response.getCookie(SESSION_COOKIE)).isNull()
        }
        assertThat(answers.map { it.response.contentAsString }.toSet()).hasSize(1)
        assertThat(answers.first().response.contentAsString).contains("urn:memo:problem:invalid-credentials")
        val failed = Audit.entries(tenant.id).filter { it.action == "identity.login.failed" }
        assertThat(failed.map { it.objectId }).contains(user.id.toString(), invited.id.toString(), null)
        assertThat(failed.map { it.canonical }).noneMatch { it.contains("nobody-") }
    }

    @Test
    fun `sign-in needs a CSRF token`() {
        val user = accounts.anActiveUser(transactions, tenant, password)

        val result =
            mvc
                .post()
                .uri("/api/v1/auth/login")
                .with(onHost(tenant.slug))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"${user.email}","password":"$password"}""")
                .exchange()

        assertThat(result).hasStatus(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `the CSRF endpoint sets a readable XSRF-TOKEN cookie`() {
        val result =
            mvc
                .get()
                .uri("/api/v1/auth/csrf")
                .with(onHost(tenant.slug))
                .exchange()

        assertThat(result).hasStatus(HttpStatus.NO_CONTENT)
        val cookie = result.response.getCookie("XSRF-TOKEN")!!
        assertThat(cookie.isHttpOnly).isFalse()
        assertThat(cookie.value).isNotBlank()
    }

    @Test
    fun `a tenant admin must enrol MFA, and the partial session reaches nothing but the auth endpoints`() {
        val admin = accounts.anActiveUser(transactions, tenant, password, tenantAdmin = true)

        val result = mvc.login(tenant, admin.email, password)
        assertThat(result).bodyJson().extractingPath("$.state").isEqualTo("MFA_ENROLMENT_REQUIRED")
        val cookie = result.response.getCookie(SESSION_COOKIE)!!

        val protected =
            mvc
                .get()
                .uri("/api/v1/does-not-exist")
                .with(onHost(tenant.slug))
                .cookie(cookie)
                .exchange()
        assertThat(protected).hasStatus(HttpStatus.UNAUTHORIZED)
        assertThat(protected).bodyJson().extractingPath("$.type").isEqualTo("urn:memo:problem:mfa-required")
        assertThat(session(cookie)).bodyJson().extractingPath("$.state").isEqualTo("MFA_ENROLMENT_REQUIRED")
    }

    @Test
    fun `a session presented on another tenant's host is anonymous there and destroyed`() {
        val user = accounts.anActiveUser(transactions, tenant, password)
        val cookie = mvc.signIn(tenant, user.email, password)
        val other = registry.aTestTenant(transactions, settings, "auth-other", mfaRequired = false)

        assertThat(session(cookie, on = other)).bodyJson().extractingPath("$.state").isEqualTo("ANONYMOUS")
        assertThat(storedSessions(cookie)).isZero()
        assertThat(session(cookie)).bodyJson().extractingPath("$.state").isEqualTo("ANONYMOUS")
    }

    @Test
    fun `a session past its absolute timeout is rejected and destroyed`() {
        val user = accounts.anActiveUser(transactions, tenant, password)
        val cookie = mvc.signIn(tenant, user.email, password)
        AppRoleJdbc.connect { c ->
            c
                .prepareStatement(
                    """
                    update identity_http_session_attributes
                    set attribute_bytes = convert_to(jsonb_set(convert_from(attribute_bytes, 'UTF8')::jsonb, '{expiresAt}', '"2000-01-01T00:00:00Z"')::text, 'UTF8')
                    where attribute_name = 'memo.principal'
                      and session_primary_id = (select primary_id from identity_http_session where session_id = ?)
                    """,
                ).use {
                    it.setString(1, sessionId(cookie))
                    assertThat(it.executeUpdate()).isOne()
                }
        }

        assertThat(session(cookie)).bodyJson().extractingPath("$.state").isEqualTo("ANONYMOUS")
        assertThat(storedSessions(cookie)).isZero()
    }

    @Test
    fun `the session stores only the principal as JSON, with the tenant's idle timeout`() {
        val strict = registry.aTestTenant(transactions, settings, "auth-idle", mfaRequired = false)
        transactions.inTenant(strict.id) { settings.put(AuthSettings.IDLE_MINUTES, "10") }
        val user = accounts.anActiveUser(transactions, strict, password)
        val cookie = mvc.signIn(strict, user.email, password)

        val (names, values) =
            AppRoleJdbc
                .connect { c ->
                    c.query(
                        """
                    select a.attribute_name, convert_from(a.attribute_bytes, 'UTF8') from identity_http_session_attributes a
                    join identity_http_session s on s.primary_id = a.session_primary_id where s.session_id = ?
                    """,
                        sessionId(cookie),
                    ) { it.getString(1) to it.getString(2) }
                }.unzip()
        assertThat(
            names,
        ).containsExactlyInAnyOrder(
            "memo.principal",
            "org.springframework.session.FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME",
        )
        assertThat(values).allMatch { !it.contains(password) }
        assertThat(values.first { it.startsWith("{") }).contains("\"userId\":\"${user.id}\"").contains("\"tenantId\":\"${strict.id}\"")
        val maxInactive =
            AppRoleJdbc.connect { c ->
                c.query("select max_inactive_interval from identity_http_session where session_id = ?", sessionId(cookie)) { it.getInt(1) }
            }
        assertThat(maxInactive.single()).isEqualTo(600)
    }

    @Test
    fun `logout ends the session and is audited as the user`() {
        val user = accounts.anActiveUser(transactions, tenant, password)
        val cookie = mvc.signIn(tenant, user.email, password)

        val result =
            mvc
                .post()
                .uri(
                    "/api/v1/auth/logout",
                ).with(onHost(tenant.slug))
                .with(sendingCsrf(mvc.csrfToken(tenant), cookie))
                .exchange()

        assertThat(result).hasStatus(HttpStatus.NO_CONTENT)
        assertThat(storedSessions(cookie)).isZero()
        Audit.assertAudited(tenant.id, "identity.session.revoked", user.id.toString(), actorId = user.id.toString())
    }

    @Test
    fun `logout without a session is 401`() {
        val result =
            mvc
                .post()
                .uri("/api/v1/auth/logout")
                .with(onHost(tenant.slug))
                .with(sendingCsrf(mvc.csrfToken(tenant)))
                .exchange()
        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED)
    }
}
