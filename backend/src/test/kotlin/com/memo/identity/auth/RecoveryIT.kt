package com.memo.identity.auth

import com.memo.common.crypto.DataKeys
import com.memo.common.tenant.TenantTransactions
import com.memo.identity.Invitations
import com.memo.identity.UserAccounts
import com.memo.identity.UserStatus
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.Audit
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.MailOutbox
import com.memo.testsupport.TestTenant
import com.memo.testsupport.aPassword
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.anActiveUser
import com.memo.testsupport.login
import com.memo.testsupport.onHost
import com.memo.testsupport.postJson
import com.memo.testsupport.sessionCookie
import com.memo.testsupport.signIn
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MockMvcTester
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** Password reset and invitations (design E03 §3, §6, test plan T7). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RecoveryIT : IntegrationTest() {
    @Autowired lateinit var mvc: MockMvcTester

    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var accounts: UserAccounts

    @Autowired lateinit var invitations: Invitations

    @Autowired lateinit var keys: DataKeys

    @Autowired lateinit var json: JsonMapper

    private val outbox by lazy { MailOutbox(keys, json) }

    private val password = aPassword()

    private val tenant: TestTenant by lazy { registry.aTestTenant(transactions, settings, "recovery", mfaRequired = false) }

    private fun session(cookie: Cookie) =
        mvc
            .get()
            .uri("/api/v1/auth/session")
            .with(onHost(tenant.slug))
            .cookie(cookie)
            .exchange()

    private fun requestReset(email: String) = mvc.postJson(tenant, "/api/v1/auth/password-reset", null, """{"email":"$email"}""")

    private fun completeReset(
        token: String,
        newPassword: String,
    ) = mvc.postJson(tenant, "/api/v1/auth/password-reset/complete", null, """{"token":"$token","newPassword":"$newPassword"}""")

    @Test
    fun `a reset request answers the same for unknown emails and mails only existing accounts`() {
        val user = accounts.anActiveUser(transactions, tenant, password)
        val unknown = "nobody-${UUID.randomUUID()}@bank.example"

        assertThat(requestReset(unknown)).hasStatus(HttpStatus.ACCEPTED)
        assertThat(requestReset(user.email)).hasStatus(HttpStatus.ACCEPTED)

        val mails = outbox.all(tenant)
        assertThat(mails.map { it.to }).contains(user.email).doesNotContain(unknown)
        val mail = outbox.last(tenant, "PASSWORD_RESET", user.email)
        assertThat(mail.params.getValue("link")).startsWith("https://${tenant.slug}.localhost/reset-password#")
        Audit.assertAudited(tenant.id, "identity.user.password_reset_requested", user.id.toString())
        val entries = Audit.entries(tenant.id)
        assertThat(
            entries
                .filter {
                    it.action == "identity.user.password_reset_requested"
                }.map { it.canonical },
        ).noneMatch { it.contains(user.email) }
        assertThat(entries.map { it.canonical }).noneMatch { it.contains(unknown) }
    }

    @Test
    fun `a reset link sets a new password once, signs out everywhere and lifts a lockout`() {
        val user = accounts.anActiveUser(transactions, tenant, password)
        val cookie = mvc.signIn(tenant, user.email, password)
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            c
                .prepareStatement(
                    "update identity_user set status = 'LOCKED', locked_until = now() + interval '30 minutes', failed_logins = 20 " +
                        "where id = ?",
                ).use {
                    it.setObject(1, user.id.value)
                    it.executeUpdate()
                }
            c.commit()
        }
        requestReset(user.email)
        val token = outbox.token(outbox.last(tenant, "PASSWORD_RESET", user.email))
        val newPassword = aPassword()

        assertThat(completeReset(token, "short")).bodyJson().extractingPath("$.errors[0].field").isEqualTo("newPassword")
        assertThat(completeReset(token, newPassword)).hasStatus(HttpStatus.NO_CONTENT)

        assertThat(session(cookie)).bodyJson().extractingPath("$.state").isEqualTo("ANONYMOUS")
        assertThat(mvc.login(tenant, user.email, newPassword)).hasStatus(HttpStatus.OK)
        assertThat(transactions.inTenant(tenant.id) { accounts.find(user.id)!!.status }).isEqualTo(UserStatus.ACTIVE)
        assertThat(completeReset(token, aPassword())).bodyJson().extractingPath("$.errors[0].code").isEqualTo("INVALID")
        assertThat(outbox.all(tenant).map { it.template to it.to }).contains("PASSWORD_CHANGED" to user.email)
        Audit.assertAudited(tenant.id, "identity.user.password_reset_completed", user.id.toString(), actorId = user.id.toString())
    }

    @Test
    fun `an expired reset link is refused`() {
        val user = accounts.anActiveUser(transactions, tenant, password)
        requestReset(user.email)
        val token = outbox.token(outbox.last(tenant, "PASSWORD_RESET", user.email))
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            c.prepareStatement("update identity_action_token set expires_at = now() - interval '1 second' where user_id = ?").use {
                it.setObject(1, user.id.value)
                it.executeUpdate()
            }
            c.commit()
        }

        assertThat(completeReset(token, aPassword())).bodyJson().extractingPath("$.errors[0].code").isEqualTo("INVALID")
    }

    @Test
    fun `reset requests are limited per account`() {
        val user = accounts.anActiveUser(transactions, tenant, password)

        repeat(3) { assertThat(requestReset(user.email)).hasStatus(HttpStatus.ACCEPTED) }

        assertThat(requestReset(user.email)).hasStatus(HttpStatus.TOO_MANY_REQUESTS)
    }

    @Test
    fun `an invitee sees the invitation, sets a password and is signed in, once`() {
        val strict = registry.aTestTenant(transactions, settings, "invite")
        val email = "new-${UUID.randomUUID().toString().take(8)}@bank.example"
        val invited =
            transactions.inTenant(
                strict.id,
            ) { invitations.invite(email, "Nadia Karim", tenantAdmin = false, inviterName = "Demo Admin") }
        val mail = outbox.last(strict, "INVITATION", email)
        assertThat(mail.params).containsEntry("inviter", "Demo Admin").containsEntry("name", "Nadia Karim")
        val token = outbox.token(mail)

        val lookup = mvc.postJson(strict, "/api/v1/auth/invitations/lookup", null, """{"token":"$token"}""")
        assertThat(lookup).hasStatus(HttpStatus.OK)
        assertThat(lookup).bodyJson().extractingPath("$.email").isEqualTo("n***@bank.example")
        assertThat(mvc.postJson(strict, "/api/v1/auth/invitations/accept", null, """{"token":"$token","password":"nadia karim 2026"}"""))
            .bodyJson()
            .extractingPath("$.errors[0].code")
            .isEqualTo("CONTAINS_IDENTITY")

        val accepted = mvc.postJson(strict, "/api/v1/auth/invitations/accept", null, """{"token":"$token","password":"$password"}""")

        assertThat(accepted).hasStatus(HttpStatus.OK)
        assertThat(accepted).bodyJson().extractingPath("$.state").isEqualTo("MFA_ENROLMENT_REQUIRED")
        assertThat(accepted.sessionCookie()).isNotNull()
        assertThat(transactions.inTenant(strict.id) { accounts.find(invited.id)!!.status }).isEqualTo(UserStatus.ACTIVE)
        assertThat(mvc.postJson(strict, "/api/v1/auth/invitations/lookup", null, """{"token":"$token"}""")).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(mvc.postJson(strict, "/api/v1/auth/invitations/accept", null, """{"token":"$token","password":"$password"}"""))
            .hasStatus(HttpStatus.BAD_REQUEST)
        Audit.assertAudited(strict.id, "identity.user.invited", invited.id.toString())
        Audit.assertAudited(strict.id, "identity.user.activated", invited.id.toString())
    }

    @Test
    fun `a new invitation link retires the old one`() {
        val email = "again-${UUID.randomUUID().toString().take(8)}@bank.example"
        val invited =
            transactions.inTenant(
                tenant.id,
            ) { invitations.invite(email, "Omar Haddad", tenantAdmin = false, inviterName = "Admin") }
        val first = outbox.token(outbox.last(tenant, "INVITATION", email))

        transactions.inTenant(tenant.id) { invitations.resend(invited.id, "Admin") }

        assertThat(mvc.postJson(tenant, "/api/v1/auth/invitations/lookup", null, """{"token":"$first"}""")).hasStatus(HttpStatus.NOT_FOUND)
        val second = outbox.token(outbox.last(tenant, "INVITATION", email))
        assertThat(mvc.postJson(tenant, "/api/v1/auth/invitations/lookup", null, """{"token":"$second"}""")).hasStatus(HttpStatus.OK)
    }

    @Test
    fun `a returning user signing in from a new browser gets an alert, the same browser does not`() {
        val user = accounts.anActiveUser(transactions, tenant, password)

        fun alerts() = outbox.all(tenant).count { it.template == "SIGN_IN_ALERT" && it.to == user.email }

        mvc.login(tenant, user.email, password, userAgent = "Firefox/140")
        assertThat(alerts()).describedAs("first sign-in").isZero()
        mvc.login(tenant, user.email, password, userAgent = "Firefox/140")
        assertThat(alerts()).describedAs("same browser").isZero()
        mvc.login(tenant, user.email, password, userAgent = "Safari/26")
        assertThat(alerts()).describedAs("new browser").isOne()
        assertThat(outbox.last(tenant, "SIGN_IN_ALERT", user.email).params).containsEntry("device", "Safari/26")
    }
}
