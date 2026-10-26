package com.memo.identity.me

import com.memo.common.tenant.TenantTransactions
import com.memo.identity.MfaSeeding
import com.memo.identity.UserAccount
import com.memo.identity.UserAccounts
import com.memo.identity.mfa.Totp
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.Audit
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.TestTenant
import com.memo.testsupport.aPassword
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.ageSessionFactors
import com.memo.testsupport.anActiveUser
import com.memo.testsupport.login
import com.memo.testsupport.onHost
import com.memo.testsupport.postJson
import com.memo.testsupport.sendJson
import com.memo.testsupport.sessionCookie
import com.memo.testsupport.sessionIdOf
import com.memo.testsupport.signIn
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MockMvcTester
import java.time.Instant

/** Step-up (ADR-0024) and the `/me` endpoints it guards. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StepUpAndMeIT : IntegrationTest() {
    @Autowired lateinit var mvc: MockMvcTester

    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var accounts: UserAccounts

    @Autowired lateinit var mfaSeeding: MfaSeeding

    private val password = aPassword()

    /** Password-only users (MFA for admins only). */
    private val relaxed: TestTenant by lazy { registry.aTestTenant(transactions, settings, "stepup", mfaRequired = false) }

    private val strict: TestTenant by lazy { registry.aTestTenant(transactions, settings, "stepup-mfa") }

    private fun session(
        tenant: TestTenant,
        cookie: Cookie,
    ) = mvc
        .get()
        .uri("/api/v1/auth/session")
        .with(onHost(tenant.slug))
        .cookie(cookie)
        .exchange()

    private fun changePassword(
        cookie: Cookie,
        current: String,
        new: String,
    ) = mvc.sendJson(HttpMethod.PUT, relaxed, "/api/v1/me/password", cookie, """{"currentPassword":"$current","newPassword":"$new"}""")

    /** A user with a seeded TOTP secret, signed in with password and code. */
    private fun totpUser(): Triple<UserAccount, String, Cookie> {
        val user = accounts.anActiveUser(transactions, strict, password)
        val secret = Totp.base32(Totp.newSecret())
        transactions.inTenant(strict.id) { mfaSeeding.ensureTotp(user.id, secret) }
        val partial = mvc.login(strict, user.email, password).sessionCookie()
        val code = Totp.code(Totp.decodeBase32(secret), Totp.step(Instant.now()))
        val complete = mvc.postJson(strict, "/api/v1/auth/mfa", partial, """{"code":"$code"}""")
        assertThat(complete).hasStatus(HttpStatus.OK)
        return Triple(user, secret, complete.sessionCookie()!!)
    }

    @Test
    fun `right after sign-in the password counts as fresh, later the action asks for a step-up`() {
        val user = accounts.anActiveUser(transactions, relaxed, password)
        val cookie = mvc.signIn(relaxed, user.email, password)
        val second = mvc.signIn(relaxed, user.email, password)
        val newPassword = aPassword()

        ageSessionFactors(cookie)
        val refused = changePassword(cookie, password, newPassword)
        assertThat(refused).hasStatus(HttpStatus.UNAUTHORIZED)
        assertThat(refused).bodyJson().extractingPath("$.type").isEqualTo("urn:memo:problem:step-up-required")
        assertThat(refused).bodyJson().extractingPath("$.stepUpWindowSeconds").isEqualTo(300)

        assertThat(
            mvc.postJson(relaxed, "/api/v1/auth/step-up", cookie, """{"password":"$password-wrong"}"""),
        ).hasStatus(HttpStatus.UNAUTHORIZED)
        val confirmed = mvc.postJson(relaxed, "/api/v1/auth/step-up", cookie, """{"password":"$password"}""")
        assertThat(confirmed).hasStatus(HttpStatus.NO_CONTENT)
        val current = confirmed.sessionCookie(cookie)!!

        assertThat(changePassword(current, password, newPassword)).hasStatus(HttpStatus.NO_CONTENT)
        assertThat(session(relaxed, second)).bodyJson().extractingPath("$.state").isEqualTo("ANONYMOUS")
        assertThat(session(relaxed, current)).bodyJson().extractingPath("$.state").isEqualTo("AUTHENTICATED")
        assertThat(mvc.login(relaxed, user.email, newPassword)).hasStatus(HttpStatus.OK)
        Audit.assertAudited(relaxed.id, "identity.step_up.failed", user.id.toString(), actorId = user.id.toString())
        Audit.assertAudited(relaxed.id, "identity.step_up.succeeded", user.id.toString(), actorId = user.id.toString())
    }

    @Test
    fun `password changes report the wrong current password and policy violations per field`() {
        val user = accounts.anActiveUser(transactions, relaxed, password)
        val cookie = mvc.signIn(relaxed, user.email, password)

        val wrong = changePassword(cookie, "$password-wrong", aPassword())
        val weak = changePassword(cookie, password, "short")

        assertThat(wrong).hasStatus(HttpStatus.BAD_REQUEST)
        assertThat(wrong).bodyJson().extractingPath("$.errors[0].field").isEqualTo("currentPassword")
        assertThat(wrong).bodyJson().extractingPath("$.errors[0].code").isEqualTo("INCORRECT")
        assertThat(weak).bodyJson().extractingPath("$.errors[0].field").isEqualTo("newPassword")
        assertThat(weak).bodyJson().extractingPath("$.errors[0].code").isEqualTo("TOO_SHORT")
    }

    @Test
    fun `users with MFA step up with a code before new recovery codes`() {
        val (_, secret, cookie) = totpUser()
        ageSessionFactors(cookie)

        assertThat(mvc.postJson(strict, "/api/v1/me/recovery-codes", cookie)).hasStatus(HttpStatus.UNAUTHORIZED)
        assertThat(mvc.postJson(strict, "/api/v1/auth/step-up", cookie, """{"password":"$password"}""")).hasStatus(HttpStatus.UNAUTHORIZED)
        val next = Totp.code(Totp.decodeBase32(secret), Totp.step(Instant.now()) + 1)
        val confirmed = mvc.postJson(strict, "/api/v1/auth/step-up", cookie, """{"code":"$next"}""")
        assertThat(confirmed).hasStatus(HttpStatus.NO_CONTENT)

        val codes = mvc.postJson(strict, "/api/v1/me/recovery-codes", confirmed.sessionCookie(cookie))
        assertThat(codes).hasStatus(HttpStatus.OK)
        assertThat(codes).bodyJson().extractingPath("$.recoveryCodes.length()").isEqualTo(10)
    }

    @Test
    fun `my sessions list every device without session ids, and one can be signed out`() {
        val user = accounts.anActiveUser(transactions, relaxed, password)
        val mine = mvc.signIn(relaxed, user.email, password)
        val other = mvc.signIn(relaxed, user.email, password)

        val list =
            mvc
                .get()
                .uri("/api/v1/me/sessions")
                .with(onHost(relaxed.slug))
                .cookie(mine)
                .exchange()

        assertThat(list).hasStatus(HttpStatus.OK)
        assertThat(list).bodyJson().extractingPath("$.length()").isEqualTo(2)
        assertThat(list)
            .bodyJson()
            .extractingPath("$[?(@.current == true)].id")
            .asArray()
            .hasSize(1)
        val body = list.response.contentAsString
        assertThat(body).doesNotContain(sessionIdOf(mine), sessionIdOf(other))
        val otherHandle =
            Regex("\"id\":\"([^\"]+)\",\"current\":false").find(body)!!.groupValues[1]
        assertThat(mvc.sendJson(HttpMethod.DELETE, relaxed, "/api/v1/me/sessions/$otherHandle", mine)).hasStatus(HttpStatus.NO_CONTENT)
        assertThat(session(relaxed, other)).bodyJson().extractingPath("$.state").isEqualTo("ANONYMOUS")
        assertThat(mvc.sendJson(HttpMethod.DELETE, relaxed, "/api/v1/me/sessions/$otherHandle", mine)).hasStatus(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `me shows my account`() {
        val (user, _, cookie) = totpUser()

        val me =
            mvc
                .get()
                .uri("/api/v1/me")
                .with(onHost(strict.slug))
                .cookie(cookie)
                .exchange()

        assertThat(me).bodyJson().extractingPath("$.email").isEqualTo(user.email)
        assertThat(me).bodyJson().extractingPath("$.mfaEnrolled").isEqualTo(true)
    }
}
