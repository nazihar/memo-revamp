package com.memo.identity.auth

import com.memo.common.ratelimit.RateLimit
import com.memo.common.ratelimit.RateLimitedException
import com.memo.common.ratelimit.RateLimiter
import com.memo.common.tenant.TenantTransactions
import com.memo.identity.UserAccounts
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.TestTenant
import com.memo.testsupport.aClientIp
import com.memo.testsupport.aPassword
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.anActiveUser
import com.memo.testsupport.login
import com.memo.testsupport.query
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MockMvcTester
import java.util.UUID
import javax.sql.DataSource

/** Rate limits on sign-in (ADR-0025): per client address and per tenant + email, shared through PostgreSQL. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LoginRateLimitIT : IntegrationTest() {
    @Autowired lateinit var mvc: MockMvcTester

    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var accounts: UserAccounts

    @Autowired lateinit var dataSource: DataSource

    private val password = aPassword()

    private val tenant: TestTenant by lazy { registry.aTestTenant(transactions, settings, "ratelimit", mfaRequired = false) }

    @Test
    fun `the 21st sign-in from one address within a minute gets 429 with Retry-After`() {
        val ip = aClientIp()
        repeat(RateLimit.LOGIN_PER_IP.capacity.toInt()) {
            assertThat(
                mvc.login(tenant, "nobody-${UUID.randomUUID()}@bank.example", password, clientIp = ip),
            ).hasStatus(HttpStatus.UNAUTHORIZED)
        }

        val limited = mvc.login(tenant, "nobody-${UUID.randomUUID()}@bank.example", password, clientIp = ip)

        assertThat(limited).hasStatus(HttpStatus.TOO_MANY_REQUESTS)
        assertThat(limited).bodyJson().extractingPath("$.type").isEqualTo("urn:memo:problem:rate-limited")
        assertThat(limited.response.getHeader("Retry-After")!!.toLong()).isBetween(1, 60)
        assertThat(mvc.login(tenant, "nobody-${UUID.randomUUID()}@bank.example", password)).hasStatus(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `one account is limited across addresses, existing or not, and even the right password waits`() {
        val user = accounts.anActiveUser(transactions, tenant, password)
        val unknown = "nobody-${UUID.randomUUID()}@bank.example"

        repeat(RateLimit.LOGIN_PER_ACCOUNT.capacity.toInt()) {
            mvc.login(tenant, user.email, "$password-wrong")
            mvc.login(tenant, unknown, password)
        }

        assertThat(mvc.login(tenant, user.email.uppercase(), password)).hasStatus(HttpStatus.TOO_MANY_REQUESTS)
        assertThat(mvc.login(tenant, unknown, password)).hasStatus(HttpStatus.TOO_MANY_REQUESTS)
    }

    @Test
    fun `limits are shared by every instance through the database, and keys hide the subject`() {
        val subject = "203.0.113.${UUID.randomUUID().hashCode() and 0xff}-${UUID.randomUUID()}"
        val first = RateLimiter(dataSource)
        val second = RateLimiter(dataSource)

        repeat(3) { first.consume(RateLimit.PASSWORD_RESET_PER_ACCOUNT, subject) }

        assertThatThrownBy { second.consume(RateLimit.PASSWORD_RESET_PER_ACCOUNT, subject) }.isInstanceOf(RateLimitedException::class.java)
        val rows =
            AppRoleJdbc.connect { c ->
                c.query(
                    "select count(*) from identity_rate_limit_bucket where id = ?",
                    RateLimiter.key(RateLimit.PASSWORD_RESET_PER_ACCOUNT, subject),
                ) {
                    it.getInt(1)
                }
            }
        assertThat(rows.single()).isOne()
    }
}
