package com.memo.identity.operator

import com.memo.identity.mfa.Totp
import com.memo.testsupport.Audit
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.OPERATOR_COOKIE
import com.memo.testsupport.aPassword
import com.memo.testsupport.ageSessionFactors
import com.memo.testsupport.anOperator
import com.memo.testsupport.operatorCookie
import com.memo.testsupport.operatorPartial
import com.memo.testsupport.operatorSession
import com.memo.testsupport.platformGet
import com.memo.testsupport.platformPost
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MockMvcTester
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/** Operator accounts and sign-in (ADR-0031; test plan T1). */
class OperatorAuthIT : IntegrationTest() {
    @Autowired lateinit var mvc: MockMvcTester

    @Autowired lateinit var accounts: OperatorAccounts

    @Autowired lateinit var mfa: OperatorMfa

    @Autowired lateinit var bootstrap: OperatorBootstrap

    @Autowired lateinit var json: JsonMapper

    private fun state(body: String) = json.readTree(body)["state"].asString()

    @Test
    fun `the enrolment link sets the password once, then TOTP enrolment is forced before the session is complete`() {
        val email = "op-${UUID.randomUUID().toString().take(8)}@memo.example"
        val link = bootstrap.invite(email, "New Operator")
        assertThat(link).startsWith("https://admin.localhost/platform/enrol#")
        val token = link.substringAfter('#')

        val lookup = mvc.platformPost("/api/v1/platform/auth/enrolment/lookup", null, """{"token":"$token"}""")
        assertThat(lookup).hasStatus(HttpStatus.OK)
        assertThat(lookup.response.contentAsString).contains("New Operator").doesNotContain(email)

        val weak = mvc.platformPost("/api/v1/platform/auth/enrolment/accept", null, """{"token":"$token","password":"short"}""")
        assertThat(weak).hasStatus(HttpStatus.BAD_REQUEST)

        val password = aPassword()
        val accepted = mvc.platformPost("/api/v1/platform/auth/enrolment/accept", null, """{"token":"$token","password":"$password"}""")
        assertThat(accepted).hasStatus(HttpStatus.OK)
        assertThat(state(accepted.response.contentAsString)).isEqualTo("MFA_ENROLMENT_REQUIRED")
        val partial = requireNotNull(accepted.operatorCookie())

        val again = mvc.platformPost("/api/v1/platform/auth/enrolment/accept", null, """{"token":"$token","password":"$password"}""")
        assertThat(again).hasStatus(HttpStatus.BAD_REQUEST)

        // Partial: the session endpoint says what is missing; anything else is refused.
        assertThat(state(mvc.platformGet("/api/v1/platform/auth/session", partial).response.contentAsString))
            .isEqualTo("MFA_ENROLMENT_REQUIRED")

        val enrolment = mvc.platformPost("/api/v1/platform/auth/mfa/enrolment", partial)
        assertThat(enrolment).hasStatus(HttpStatus.OK)
        val secret = json.readTree(enrolment.response.contentAsString)["secret"].asString()
        val code = Totp.code(Totp.decodeBase32(secret), Totp.step(Instant.now()))
        val confirmed = mvc.platformPost("/api/v1/platform/auth/mfa/enrolment/confirm", partial, """{"code":"$code"}""")
        assertThat(confirmed).hasStatus(HttpStatus.OK)
        assertThat(state(confirmed.response.contentAsString)).isEqualTo("AUTHENTICATED")

        val id = requireNotNull(accounts.findByEmail(email)).id.toString()
        val actions = Audit.platformEntries().filter { it.objectId == id }.map { it.action }
        assertThat(actions).contains(
            "platform.operator.invited",
            "platform.operator.activated",
            "platform.operator.mfa_enrolled",
            "platform.operator.signed_in",
        )
        assertThat(Audit.platformEntries().joinToString { it.canonical }).doesNotContain(password, token, secret)
    }

    @Test
    fun `an enrolled operator cannot re-enrol with the password alone`() {
        val operator = mfa.anOperator()
        val partial = mvc.operatorPartial(operator)

        assertThat(mvc.platformPost("/api/v1/platform/auth/mfa/enrolment", partial)).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(mvc.platformPost("/api/v1/platform/auth/mfa", partial, """{"code":"000000"}""")).hasStatus(HttpStatus.UNAUTHORIZED)
        val complete = mvc.platformPost("/api/v1/platform/auth/mfa", partial, """{"code":"${operator.code()}"}""")
        assertThat(state(complete.response.contentAsString)).isEqualTo("AUTHENTICATED")
    }

    @Test
    fun `wrong passwords lock the operator, and a locked operator gets the same answer as a wrong password`() {
        val operator = mfa.anOperator()
        repeat(OperatorAccounts.LOCK_AFTER) {
            val wrong = mvc.platformPost("/api/v1/platform/auth/login", null, """{"email":"${operator.email}","password":"wrong-$it"}""")
            assertThat(wrong).hasStatus(HttpStatus.UNAUTHORIZED)
        }
        val locked =
            mvc.platformPost(
                "/api/v1/platform/auth/login",
                null,
                """{"email":"${operator.email}","password":"${operator.password}"}""",
            )

        assertThat(locked).hasStatus(HttpStatus.UNAUTHORIZED)
        assertThat(Audit.platformEntries().map { it.action to it.objectId }).contains("platform.operator.locked" to operator.id.toString())

        val failedBefore = Audit.platformEntries().count { it.action == "platform.operator.sign_in_failed" }
        val unknown = mvc.platformPost("/api/v1/platform/auth/login", null, """{"email":"nobody@platform.example","password":"x"}""")
        assertThat(unknown).hasStatus(HttpStatus.UNAUTHORIZED)
        assertThat(Audit.platformEntries().count { it.action == "platform.operator.sign_in_failed" && it.objectId == "unknown" })
            .isGreaterThan(failedBefore)
    }

    @Test
    fun `step-up refreshes the second factor, and a reset by the bootstrap command ends the operator's sessions`() {
        val operator = mfa.anOperator()
        val session = mvc.operatorSession(operator)
        ageSessionFactors(session, OperatorSessionStore.PRINCIPAL)

        val stepUp = mvc.platformPost("/api/v1/platform/auth/step-up", session, """{"code":"${operator.code(1)}"}""")
        assertThat(stepUp).hasStatus(HttpStatus.OK)
        val fresh = requireNotNull(stepUp.operatorCookie(session))
        assertThat(fresh.name).isEqualTo(OPERATOR_COOKIE)

        bootstrap.invite(operator.email, "Test Operator")

        assertThat(state(mvc.platformGet("/api/v1/platform/auth/session", fresh).response.contentAsString)).isEqualTo("ANONYMOUS")
    }
}
