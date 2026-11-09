package com.memo.demo

import com.memo.common.authz.AccessAdministration
import com.memo.common.tenant.TenantTransactions
import com.memo.identity.MfaSeeding
import com.memo.identity.UserAccounts
import com.memo.identity.UserStatus
import com.memo.identity.mfa.MfaService
import com.memo.identity.mfa.Totp
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.Audit
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.aPassword
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.env.Environment
import org.springframework.core.env.StandardEnvironment

class DemoTenantSeedIT : IntegrationTest() {
    @Autowired lateinit var seeder: DemoTenantSeeder

    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var accounts: UserAccounts

    @Autowired lateinit var environment: Environment

    @Autowired lateinit var mfaSeeding: MfaSeeding

    @Autowired lateinit var mfa: MfaService

    @Autowired lateinit var access: AccessAdministration

    @Autowired lateinit var orgSeeding: com.memo.org.OrgSeeding

    @Autowired lateinit var operators: com.memo.identity.operator.OperatorMfa

    @Autowired lateinit var operatorAccounts: com.memo.identity.operator.OperatorAccounts

    private fun seederWith(
        password: String,
        totpSecret: String = "",
        env: Environment = environment,
    ) = DemoTenantSeeder(registry, settings, transactions, env, accounts, password, mfaSeeding, totpSecret, access, orgSeeding, operators)

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
        val password = aPassword()

        val totpSecret = Totp.base32(Totp.newSecret())
        seederWith(password, totpSecret).seed()
        seederWith(password, totpSecret).seed()

        val operator = requireNotNull(operatorAccounts.findByEmail(DemoTenant.OPERATOR_EMAIL))
        assertThat(operator.enrolled).isTrue()
        assertThat(operatorAccounts.passwordMatches(operator.id, password)).isTrue()

        transactions.inTenant(DemoTenant.ID) {
            DemoTenant.USERS.forEach {
                val account = accounts.findByEmail(it.email)!!
                assertThat(account.status).isEqualTo(UserStatus.ACTIVE)
                assertThat(accounts.isTenantAdmin(account.id)).isEqualTo(it.tenantAdmin)
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

    @Autowired lateinit var tenantDsl: com.memo.common.tenant.TenantDsl

    @Autowired lateinit var policy: com.memo.common.authz.Policy

    @Test
    fun `the demo bank has its org tree, people in most seats and administrators by role`() {
        seeder.seed()
        seeder.seed()

        transactions.inTenant(DemoTenant.ID) {
            val dsl = tenantDsl.current()
            assertThat(dsl.fetchCount(com.memo.jooq.tables.references.ORG_UNIT)).isEqualTo(DemoOrg.UNITS.size)
            assertThat(dsl.fetchCount(com.memo.jooq.tables.references.ORG_POSITION)).isEqualTo(DemoOrg.POSITIONS.size)
            assertThat(dsl.fetchCount(com.memo.jooq.tables.references.ORG_REPORTING_LINE)).isEqualTo(DemoOrg.REPORTING_LINES.size)
            val openSeats = DemoOrg.POSITIONS.size - DemoOrg.SEATS.size - DemoOrg.VACANT.size
            assertThat(dsl.fetchCount(com.memo.jooq.tables.references.ORG_ASSIGNMENT))
                .isEqualTo(DemoOrg.SEATS.size + minOf(openSeats, DemoOrg.STAFF.size - DemoOrg.UNSEATED))
        }
        // Seeding twice audits each object once: only rows actually inserted are recorded.
        val created = Audit.entries(DemoTenant.ID).filter { it.action.startsWith("org.") && it.action.endsWith(".created") }
        for (action in listOf("org.unit.created", "org.reporting_line.created", "org.assignment.created")) {
            val perObject = created.filter { it.action == action }.groupingBy { it.objectId }.eachCount()
            assertThat(perObject).describedAs(action).isNotEmpty()
            assertThat(perObject.values).describedAs(action).allMatch { it == 1 }
        }
        val branchAdmin = transactions.readInTenant(DemoTenant.ID) { accounts.findByEmail("branchadmin@demo.example")!! }
        com.memo.common.context.ActorContext.call(
            com.memo.common.context
                .ActorScope(
                    com.memo.common.context.Actor
                        .User(branchAdmin.id.value),
                    null,
                ),
        ) {
            com.memo.common.tenant.TenantContext.call(DemoTenant.ID) {
                val manage = com.memo.common.authz.Permission.ORG_MANAGE
                assertThat(
                    policy.allows(
                        manage,
                        com.memo.common.authz.Resource
                            .Unit(DemoOrg.unitId("BR-GUL")),
                    ),
                ).isTrue()
                assertThat(
                    policy.allows(
                        manage,
                        com.memo.common.authz.Resource
                            .Unit(DemoOrg.unitId("REG-S")),
                    ),
                ).isFalse()
            }
        }
    }
}
