package com.memo.identity.admin

import com.memo.common.tenant.TenantTransactions
import com.memo.identity.MfaSeeding
import com.memo.identity.UserAccounts
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.Audit
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.TestTenant
import com.memo.testsupport.aPassword
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.adminSession
import com.memo.testsupport.anActiveUser
import com.memo.testsupport.login
import com.memo.testsupport.onHost
import com.memo.testsupport.sendJson
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MockMvcTester
import tools.jackson.databind.json.JsonMapper

/** Admin → Security (design E07 §2, §4; threat model R2): ranges enforced, changes audited and honoured. */
class SecuritySettingsIT : IntegrationTest() {
    @Autowired lateinit var mvc: MockMvcTester

    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var accounts: UserAccounts

    @Autowired lateinit var mfaSeeding: MfaSeeding

    @Autowired lateinit var json: JsonMapper

    private fun body(
        idle: Int = 30,
        throttle: Int = 5,
        lockAfter: Int = 20,
    ) = """{"mfaRequired":"all","idleMinutes":$idle,"absoluteHours":12,"stepUpMinutes":5,"passwordMinLength":12,""" +
        """"throttleAfter":$throttle,"lockAfter":$lockAfter,"lockMinutes":30}"""

    private fun put(
        tenant: TestTenant,
        session: Cookie,
        json: String,
        version: Long,
    ) = mvc.sendJson(HttpMethod.PUT, tenant, "/api/v1/admin/security-settings", session, json, mapOf("If-Match" to "\"$version\""))

    @Test
    fun `values out of range are refused, and a saved throttle threshold applies to the next sign-ins`() {
        val tenant = registry.aTestTenant(transactions, settings, "secset")
        val session = mvc.adminSession(tenant, accounts, transactions, mfaSeeding)
        val current =
            json.readTree(
                mvc
                    .get()
                    .uri("/api/v1/admin/security-settings")
                    .with(onHost(tenant.slug))
                    .cookie(session)
                    .exchange()
                    .response.contentAsString,
            )
        val version = current["version"].asLong()
        assertThat(current["settings"]["lockAfter"].asInt()).isEqualTo(20)

        val tooShort = put(tenant, session, body(idle = 2), version)
        assertThat(tooShort).hasStatus(HttpStatus.BAD_REQUEST)
        assertThat(tooShort.response.contentAsString).contains("idleMinutes", "OUT_OF_RANGE")
        assertThat(put(tenant, session, body(throttle = 5, lockAfter = 5), version).response.contentAsString).contains("NOT_ABOVE_THROTTLE")

        assertThat(put(tenant, session, body(throttle = 3, lockAfter = 6), version)).hasStatus(HttpStatus.OK)
        val stale = put(tenant, session, body(), version)
        assertThat(stale.response.status).isIn(HttpStatus.CONFLICT.value(), HttpStatus.PRECONDITION_FAILED.value())

        val changed = Audit.entries(tenant.id).filter { it.action.startsWith("tenant.setting.") }.map { it.objectId }
        assertThat(changed)
            .contains("security.lockout.lock.after", "security.lockout.throttle.after")
            .doesNotContain("security.session.idle.minutes")

        // Waiting starts at the tenant's 3rd failure (the default would be the 5th).
        val password = aPassword()
        val user = accounts.anActiveUser(transactions, tenant, password)
        repeat(3) { mvc.login(tenant, user.email, "$password-wrong") }
        assertThat(transactions.inTenant(tenant.id) { requireNotNull(accounts.find(user.id)).lockedUntil }).isNotNull()
    }
}
