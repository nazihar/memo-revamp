package com.memo.demo

import com.memo.common.tenant.TenantTransactions
import com.memo.identity.MfaSeeding
import com.memo.identity.UserAccounts
import com.memo.identity.UserStatus
import com.memo.identity.mfa.MfaService
import com.memo.identity.mfa.Totp
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.env.Environment
import org.springframework.core.env.StandardEnvironment
import java.security.SecureRandom
import java.util.Base64

class DemoTenantSeedIT : IntegrationTest() {
    @Autowired lateinit var seeder: DemoTenantSeeder

    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var accounts: UserAccounts

    @Autowired lateinit var environment: Environment

    @Autowired lateinit var mfaSeeding: MfaSeeding

    @Autowired lateinit var mfa: MfaService

    private fun seederWith(
        password: String,
        totpSecret: String = "",
        env: Environment = environment,
    ) = DemoTenantSeeder(registry, settings, transactions, env, accounts, password, mfaSeeding, totpSecret)

    @Test
    fun `seeding is idempotent and produces the demo tenant as defined in code`() {
        seeder.seed()
        seeder.seed()

        transactions.inTenant(DemoTenant.ID) {
            val tenant = registry.current()!!
            assertThat(tenant.slug).isEqualTo(DemoTenant.SLUG)
            assertThat(tenant.name).isEqualTo(DemoTenant.NAME)
            assertThat(settings.list().associate { it.key to it.value }).containsAllEntriesOf(DemoTenant.SETTINGS)
            assertThat(settings.list().map { it.version }).allMatch { it == 0L }
            assertThat(DemoTenant.USERS).allSatisfy { assertThat(accounts.findByEmail(it.email)).isNotNull() }
        }
    }

    @Test
    fun `demo users get password and TOTP secret from the environment and can then sign in`() {
        // Generated per run: no password lives in code or fixtures.
        val password = Base64.getUrlEncoder().encodeToString(ByteArray(24).also { SecureRandom().nextBytes(it) })

        val totpSecret = Totp.base32(Totp.newSecret())
        seederWith(password, totpSecret).seed()
        seederWith(password, totpSecret).seed()

        transactions.inTenant(DemoTenant.ID) {
            DemoTenant.USERS.forEach {
                val account = accounts.findByEmail(it.email)!!
                assertThat(account.status).isEqualTo(UserStatus.ACTIVE)
                assertThat(account.tenantAdmin).isEqualTo(it.tenantAdmin)
                assertThat(accounts.passwordMatches(account.id, password)).isTrue()
                assertThat(mfa.verify(account, Totp.code(Totp.decodeBase32(totpSecret), Totp.step(java.time.Instant.now())))).isTrue()
            }
        }
    }

    @Test
    fun `refuses to seed under the prod profile`() {
        val prod = StandardEnvironment().apply { setActiveProfiles("prod") }
        assertThatThrownBy { seederWith("", env = prod).seed() }.hasMessageContaining("never be seeded in production")
    }
}
