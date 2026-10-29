package com.memo.identity.mfa

import com.memo.common.tenant.TenantTransactions
import com.memo.identity.UserAccount
import com.memo.identity.UserAccounts
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.Audit
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.TestTenant
import com.memo.testsupport.aPassword
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.anActiveUser
import com.memo.testsupport.login
import com.memo.testsupport.onHost
import com.memo.testsupport.postJson
import com.memo.testsupport.query
import com.memo.testsupport.sessionCookie
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.test.web.servlet.assertj.MvcTestResult
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

/** TOTP enrolment, verification, replay, recovery codes and policy (ADR-0024), through the API. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MfaFlowIT : IntegrationTest() {
    @Autowired lateinit var mvc: MockMvcTester

    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var accounts: UserAccounts

    @Autowired lateinit var mfa: MfaService

    @Autowired lateinit var json: JsonMapper

    private val password = aPassword()

    /** Default policy: MFA for everyone. */
    private val tenant: TestTenant by lazy { registry.aTestTenant(transactions, settings, "mfa") }

    private fun MvcTestResult.path(path: String): String = json.readTree(response.contentAsString).at(path).asString()

    private fun state(result: MvcTestResult) = result.path("/state")

    private fun code(
        secret: String,
        stepOffset: Long = 0,
    ) = Totp.code(Totp.decodeBase32(secret), Totp.step(Instant.now()) + stepOffset)

    /** Signs in, enrols and confirms; returns the Base32 secret, the recovery codes and the complete session. */
    private fun enrolled(user: UserAccount): Triple<String, List<String>, Cookie> {
        val partial = mvc.login(tenant, user.email, password).sessionCookie()!!
        val enrolment = mvc.postJson(tenant, "/api/v1/auth/mfa/enrolment", partial)
        assertThat(enrolment).hasStatus(HttpStatus.OK)
        val secret = enrolment.path("/secret")
        val confirmed = mvc.postJson(tenant, "/api/v1/auth/mfa/enrolment/confirm", partial, """{"code":"${code(secret)}"}""")
        assertThat(confirmed).hasStatus(HttpStatus.OK)
        val node = json.readTree(confirmed.response.contentAsString).at("/recoveryCodes")
        val codes = (0 until node.size()).map { node.get(it).asString() }
        return Triple(secret, codes, confirmed.sessionCookie()!!)
    }

    @Test
    fun `enrolment completes the sign-in, rotates the session, and stores the secret encrypted`() {
        val admin = accounts.anActiveUser(transactions, tenant, password, tenantAdmin = true)
        val login = mvc.login(tenant, admin.email, password)
        assertThat(state(login)).isEqualTo("MFA_ENROLMENT_REQUIRED")
        val partial = login.sessionCookie()!!

        val enrolment = mvc.postJson(tenant, "/api/v1/auth/mfa/enrolment", partial)
        val secret = enrolment.path("/secret")
        assertThat(enrolment.path("/otpauthUri")).startsWith("otpauth://totp/").contains("secret=$secret")
        assertThat(enrolment.path("/qrSvg")).startsWith("<svg")
        assertThat(
            mvc.postJson(tenant, "/api/v1/auth/mfa/enrolment/confirm", partial, """{"code":"000000"}"""),
        ).hasStatus(HttpStatus.UNAUTHORIZED)

        val confirmed = mvc.postJson(tenant, "/api/v1/auth/mfa/enrolment/confirm", partial, """{"code":"${code(secret)}"}""")

        assertThat(confirmed).hasStatus(HttpStatus.OK)
        assertThat(confirmed.path("/session/state")).isEqualTo("AUTHENTICATED")
        assertThat(json.readTree(confirmed.response.contentAsString).at("/recoveryCodes").size()).isEqualTo(RecoveryCodes.COUNT)
        val complete = confirmed.sessionCookie()!!
        assertThat(complete.value).isNotEqualTo(partial.value)
        assertThat(
            mvc
                .get()
                .uri("/api/v1/does-not-exist")
                .with(onHost(tenant.slug))
                .cookie(complete)
                .exchange(),
        ).hasStatus(HttpStatus.NOT_FOUND)
        val stored =
            AppRoleJdbc.inTransaction(
                tenant.id,
            ) { c ->
                c.query("select secret_enc from identity_mfa where user_id = ?", admin.id.value) {
                    it.getBytes(1)
                }
            }
        assertThat(String(stored.single(), Charsets.ISO_8859_1)).doesNotContain(String(Totp.decodeBase32(secret), Charsets.ISO_8859_1))
        Audit.assertAudited(tenant.id, "identity.user.mfa_enrolled", admin.id.toString(), actorId = admin.id.toString())
        assertThat(Audit.entries(tenant.id).map { it.canonical }).noneMatch { it.contains(secret) }
    }

    @Test
    fun `a code works once, and the next step's code still works`() {
        val user = accounts.anActiveUser(transactions, tenant, password)
        val (secret, _, _) = enrolled(user)

        val login = mvc.login(tenant, user.email, password)
        assertThat(state(login)).isEqualTo("MFA_REQUIRED")
        val partial = login.sessionCookie()!!

        assertThat(mvc.postJson(tenant, "/api/v1/auth/mfa", partial, """{"code":"${code(secret)}"}""")).hasStatus(HttpStatus.UNAUTHORIZED)
        val next = mvc.postJson(tenant, "/api/v1/auth/mfa", partial, """{"code":"${code(secret, stepOffset = 1)}"}""")
        assertThat(next).hasStatus(HttpStatus.OK)
        assertThat(state(next)).isEqualTo("AUTHENTICATED")
    }

    @Test
    fun `a recovery code works exactly once`() {
        val user = accounts.anActiveUser(transactions, tenant, password)
        val (_, codes, _) = enrolled(user)

        val first =
            mvc.postJson(
                tenant,
                "/api/v1/auth/mfa",
                mvc.login(tenant, user.email, password).sessionCookie(),
                """{"code":"${codes[0].lowercase()}"}""",
            )
        val again =
            mvc.postJson(
                tenant,
                "/api/v1/auth/mfa",
                mvc.login(tenant, user.email, password).sessionCookie(),
                """{"code":"${codes[0]}"}""",
            )

        assertThat(first).hasStatus(HttpStatus.OK)
        assertThat(again).hasStatus(HttpStatus.UNAUTHORIZED)
        Audit.assertAudited(tenant.id, "identity.user.recovery_code_used", user.id.toString())
    }

    @Test
    fun `a password alone cannot re-enrol an enrolled account`() {
        val user = accounts.anActiveUser(transactions, tenant, password)
        enrolled(user)
        val partial = mvc.login(tenant, user.email, password).sessionCookie()

        assertThat(mvc.postJson(tenant, "/api/v1/auth/mfa/enrolment", partial)).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(
            mvc.postJson(tenant, "/api/v1/auth/mfa/enrolment/confirm", partial, """{"code":"123456"}"""),
        ).hasStatus(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `MFA applies to everyone by default, and when relaxed to admins, users sign in with a password`() {
        val relaxed = registry.aTestTenant(transactions, settings, "mfa-admins", mfaRequired = false)
        val user = accounts.anActiveUser(transactions, relaxed, password)
        val admin = accounts.anActiveUser(transactions, relaxed, password, tenantAdmin = true)
        val strictUser = accounts.anActiveUser(transactions, tenant, password)

        assertThat(state(mvc.login(relaxed, user.email, password))).isEqualTo("AUTHENTICATED")
        assertThat(state(mvc.login(relaxed, admin.email, password))).isEqualTo("MFA_ENROLMENT_REQUIRED")
        assertThat(state(mvc.login(tenant, strictUser.email, password))).isEqualTo("MFA_ENROLMENT_REQUIRED")
    }

    @Test
    fun `after an MFA reset the user enrols again`() {
        val user = accounts.anActiveUser(transactions, tenant, password)
        enrolled(user)

        transactions.inTenant(tenant.id) { mfa.reset(user.id) }

        assertThat(state(mvc.login(tenant, user.email, password))).isEqualTo("MFA_ENROLMENT_REQUIRED")
        Audit.assertAudited(tenant.id, "identity.user.mfa_reset", user.id.toString())
    }

    @Test
    fun `wrong codes are rate-limited per user`() {
        val user = accounts.anActiveUser(transactions, tenant, password)
        enrolled(user)
        val partial = mvc.login(tenant, user.email, password).sessionCookie()

        // Five codes per minute per user (ADR-0025); confirming the enrolment used one.
        val answers = (1..5).map { mvc.postJson(tenant, "/api/v1/auth/mfa", partial, """{"code":"000000"}""").response.status }

        assertThat(answers.take(4)).containsOnly(HttpStatus.UNAUTHORIZED.value())
        assertThat(answers.last()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value())
    }
}
