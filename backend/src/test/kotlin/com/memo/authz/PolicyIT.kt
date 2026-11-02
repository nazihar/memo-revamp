package com.memo.authz

import com.memo.common.authz.AccessAdministration
import com.memo.common.authz.BuiltInRole
import com.memo.common.authz.Permission
import com.memo.common.authz.Policy
import com.memo.common.authz.Resource
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.identity.UserAccount
import com.memo.identity.UserAccounts
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.TestTenant
import com.memo.testsupport.aPassword
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.anActiveUser
import com.memo.testsupport.login
import com.memo.testsupport.onHost
import com.memo.testsupport.signIn
import org.assertj.core.api.Assertions.assertThat
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MockMvcTester
import java.util.UUID

/** The policy service over role grants (ADR-0027; test plan T1). Unit scopes are covered with the org tree (T3). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PolicyIT : IntegrationTest() {
    @Autowired lateinit var mvc: MockMvcTester

    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var accounts: UserAccounts

    @Autowired lateinit var access: AccessAdministration

    @Autowired lateinit var policy: Policy

    private val password = aPassword()

    private val tenant: TestTenant by lazy { registry.aTestTenant(transactions, settings, "policy", mfaRequired = false) }

    private fun <T> asUser(
        user: UserAccount,
        block: () -> T,
    ): T =
        TenantContext.call(tenant.id) {
            ActorContext.call(ActorScope(Actor.User(user.id.value), null), block)
        }

    @Test
    fun `a user without grants holds only the implicit user role`() {
        val user = accounts.anActiveUser(transactions, tenant, password)

        asUser(user) {
            assertThat(policy.allows(Permission.DIRECTORY_READ)).isTrue()
            assertThat(policy.allows(Permission.ORG_MANAGE, Resource.Unit(UUID.randomUUID()))).isFalse()
            assertThat(policy.holdsAnywhere(Permission.IDENTITY_USERS_MANAGE)).isFalse()
            assertThat(policy.condition(Permission.ORG_MANAGE)).isEqualTo(DSL.falseCondition())
            assertThat(policy.clearanceRank()).isZero()
        }
        assertThat(transactions.readInTenant(tenant.id) { policy.isPrivileged(user.id.value) }).isFalse()
    }

    @Test
    fun `a tenant admin holds every permission tenant-wide, and classified resources need clearance`() {
        val admin = accounts.anActiveUser(transactions, tenant, password, tenantAdmin = true)

        asUser(admin) {
            Permission.entries.forEach { assertThat(policy.allows(it)).describedAs(it.key).isTrue() }
            assertThat(policy.allows(Permission.ORG_MANAGE, Resource.Unit(UUID.randomUUID()))).isTrue()
            assertThat(policy.condition(Permission.ORG_MANAGE)).isEqualTo(DSL.trueCondition())
            // Clearance is the lowest level (no clearance row): rank 0 objects only.
            assertThat(policy.allows(Permission.DIRECTORY_READ, Resource.Classified(null, 0))).isTrue()
            assertThat(policy.allows(Permission.DIRECTORY_READ, Resource.Classified(null, 1))).isFalse()
        }
        assertThat(transactions.readInTenant(tenant.id) { policy.isPrivileged(admin.id.value) }).isTrue()
    }

    @Test
    fun `nothing is allowed without a signed-in user`() {
        TenantContext.call(tenant.id) {
            ActorContext.asSystem("test") {
                assertThat(policy.allows(Permission.DIRECTORY_READ)).isFalse()
                assertThat(policy.grants()).isEmpty()
            }
        }
    }

    @Test
    fun `a grant takes effect at once, also for a user whose access was cached`() {
        val user = accounts.anActiveUser(transactions, tenant, password)
        asUser(user) { assertThat(policy.allows(Permission.AUDIT_READ)).isFalse() }

        transactions.inTenant(tenant.id) { access.grantBuiltIn(user.id.value, BuiltInRole.AUDITOR) }

        asUser(user) { assertThat(policy.allows(Permission.AUDIT_READ)).isTrue() }
    }

    @Test
    fun `a privileged user must use MFA even where the tenant requires it only for admins`() {
        val user = accounts.anActiveUser(transactions, tenant, password)
        assertThat(mvc.login(tenant, user.email, password)).bodyJson().extractingPath("$.state").isEqualTo("AUTHENTICATED")

        transactions.inTenant(tenant.id) { access.grantBuiltIn(user.id.value, BuiltInRole.ORG_ADMIN) }

        assertThat(mvc.login(tenant, user.email, password)).bodyJson().extractingPath("$.state").isEqualTo("MFA_ENROLMENT_REQUIRED")
    }

    @Test
    fun `my access lists permissions with scope and the clearance level`() {
        val user = accounts.anActiveUser(transactions, tenant, password)
        val cookie = mvc.signIn(tenant, user.email, password)

        val result =
            mvc
                .get()
                .uri("/api/v1/me/access")
                .with(onHost(tenant.slug))
                .cookie(cookie)
                .exchange()

        assertThat(result).hasStatus(HttpStatus.OK)
        assertThat(result)
            .bodyJson()
            .extractingPath("$.permissions[*].key")
            .asArray()
            .containsExactly("directory.read")
        assertThat(result).bodyJson().extractingPath("$.permissions[0].tenantWide").isEqualTo(true)
        assertThat(result).bodyJson().extractingPath("$.clearance.name").isEqualTo("Public")
    }

    @Test
    fun `a user who is not a tenant admin is refused user administration with a forbidden problem`() {
        val user = accounts.anActiveUser(transactions, tenant, password)
        val cookie = mvc.signIn(tenant, user.email, password)

        val result =
            mvc
                .get()
                .uri("/api/v1/admin/users")
                .with(onHost(tenant.slug))
                .cookie(cookie)
                .exchange()

        assertThat(result).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(result).bodyJson().extractingPath("$.type").isEqualTo("urn:memo:problem:forbidden")
        assertThat(result).bodyJson().extractingPath("$.permission").isEqualTo("identity.users.manage")
    }
}
