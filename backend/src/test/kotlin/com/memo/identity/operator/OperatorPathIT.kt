package com.memo.identity.operator

import com.memo.common.tenant.TenantTransactions
import com.memo.identity.UserAccounts
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.aPassword
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.anActiveUser
import com.memo.testsupport.anOperator
import com.memo.testsupport.onHost
import com.memo.testsupport.onPlatformHost
import com.memo.testsupport.operatorSession
import com.memo.testsupport.platformGet
import com.memo.testsupport.signIn
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MockMvcTester

/**
 * The operator console and tenant hosts are separate (ADR-0031; threat model S1, E1): each host serves only its own API,
 * and neither side's session works on the other.
 */
class OperatorPathIT : IntegrationTest() {
    @Autowired lateinit var mvc: MockMvcTester

    @Autowired lateinit var operators: OperatorMfa

    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var accounts: UserAccounts

    @Test
    fun `the platform API exists only on the operator host, and the tenant API nowhere on it`() {
        val tenant = registry.aTestTenant(transactions, settings, "path")

        val platformOnTenant =
            mvc
                .get()
                .uri("/api/v1/platform/auth/session")
                .with(onHost(tenant.slug))
                .exchange()
        val tenantOnPlatform =
            mvc
                .get()
                .uri("/api/v1/auth/session")
                .with(onPlatformHost())
                .exchange()
        val healthOnPlatform =
            mvc
                .get()
                .uri("/api/v1/health")
                .with(onPlatformHost())
                .exchange()

        assertThat(platformOnTenant).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(tenantOnPlatform).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(healthOnPlatform.response.status).isNotEqualTo(HttpStatus.NOT_FOUND.value())
    }

    @Test
    fun `a tenant admin's session is nobody on the operator host, and an operator's session is nobody on a tenant host`() {
        val tenant = registry.aTestTenant(transactions, settings, "cross", mfaRequired = false)
        val password = aPassword()
        val user = accounts.anActiveUser(transactions, tenant, password)
        val tenantCookie = mvc.signIn(tenant, user.email, password)
        val operatorCookie = mvc.operatorSession(operators.anOperator())

        // Same cookie value under the other side's name, as if a browser sent it there.
        val asOperator = Cookie(OPERATOR_COOKIE_NAME, tenantCookie.value)
        val asTenant = Cookie(TENANT_COOKIE_NAME, operatorCookie.value)

        assertThat(mvc.platformGet("/api/v1/platform/auth/session", tenantCookie).response.contentAsString).contains("ANONYMOUS")
        assertThat(mvc.platformGet("/api/v1/platform/auth/session", asOperator).response.contentAsString).contains("ANONYMOUS")
        assertThat(
            mvc
                .get()
                .uri("/api/v1/auth/session")
                .with(onHost(tenant.slug))
                .cookie(operatorCookie, asTenant)
                .exchange()
                .response.contentAsString,
        ).contains("ANONYMOUS")
        assertThat(
            mvc
                .get()
                .uri("/api/v1/me")
                .with(onHost(tenant.slug))
                .cookie(asTenant)
                .exchange(),
        ).hasStatus(HttpStatus.UNAUTHORIZED)
    }

    private companion object {
        const val OPERATOR_COOKIE_NAME = "MEMO_OPERATOR"
        const val TENANT_COOKIE_NAME = "MEMO_SESSION"
    }
}
