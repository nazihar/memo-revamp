package com.memo.authz

import com.memo.authz.internal.ClassificationAdmin
import com.memo.authz.internal.GrantAdmin
import com.memo.authz.internal.RoleAdmin
import com.memo.authz.internal.RoleCatalog
import com.memo.common.authz.BuiltInRole
import com.memo.common.authz.Permission
import com.memo.common.authz.Policy
import com.memo.common.authz.Resource
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.RefusedException
import com.memo.identity.LegacyTenantAdminFlag
import com.memo.identity.UserAccount
import com.memo.identity.UserAccounts
import com.memo.jooq.tables.references.ORG_UNIT
import com.memo.org.internal.LegalEntities
import com.memo.org.internal.OrgUnit
import com.memo.org.internal.OrgUnits
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.Audit
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.TestTenant
import com.memo.testsupport.aPassword
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.anActiveUser
import com.memo.testsupport.onHost
import com.memo.testsupport.signIn
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MockMvcTester
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Roles, grants, classification levels and clearances (ADR-0027; test plan T5). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccessAdminIT : IntegrationTest() {
    @Autowired lateinit var mvc: MockMvcTester

    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var tenantDsl: TenantDsl

    @Autowired lateinit var accounts: UserAccounts

    @Autowired lateinit var legacyFlag: LegacyTenantAdminFlag

    @Autowired lateinit var roles: RoleAdmin

    @Autowired lateinit var catalog: RoleCatalog

    @Autowired lateinit var grants: GrantAdmin

    @Autowired lateinit var classification: ClassificationAdmin

    @Autowired lateinit var policy: Policy

    @Autowired lateinit var entities: LegalEntities

    @Autowired lateinit var units: OrgUnits

    private val password = aPassword()

    /** MFA only for administrators, so plain users sign in with a password. */
    private val tenant: TestTenant by lazy { registry.aTestTenant(transactions, settings, "access", mfaRequired = false) }

    private val admin: UserAccount by lazy { accounts.anActiveUser(transactions, tenant, password, tenantAdmin = true) }

    private fun <T> asUser(
        user: UserAccount,
        on: TestTenant = tenant,
        block: () -> T,
    ): T =
        ActorContext.call(ActorScope(Actor.User(user.id.value), null)) {
            TenantContext.call(on.id) { transactions.inTenant(on.id, block) }
        }

    private fun refusal(block: () -> Any?): String =
        (runCatching { block() }.exceptionOrNull() as? RefusedException ?: error("expected a refusal")).reason

    private fun builtIn(role: BuiltInRole) = asUser(admin) { catalog.all().first { it.builtIn == role } }

    private fun aUnit(parent: OrgUnit? = null): OrgUnit =
        asUser(admin) {
            val entity = parent?.legalEntityId ?: entities.create("Entity ${UUID.randomUUID()}", null, null, LocalDate.of(2026, 1, 1)).id
            units.createUnit(entity, parent?.id, "U" + UUID.randomUUID().toString().take(8), "Unit", LocalDate.of(2026, 1, 1))
        }

    @Test
    fun `custom roles combine catalogue permissions, built-in roles cannot change and used roles cannot go`() {
        val role = asUser(admin) { roles.create("Branch auditor ${UUID.randomUUID()}", null, setOf(Permission.AUDIT_READ)) }
        assertThat(
            refusal {
                asUser(admin) { roles.create(role.name.uppercase(), null, setOf(Permission.AUDIT_READ)) }
            },
        ).isEqualTo("DUPLICATE_NAME")

        val auditor = builtIn(BuiltInRole.AUDITOR)
        assertThat(refusal { asUser(admin) { roles.update(auditor.id, auditor.version, "x", null, setOf(Permission.ORG_MANAGE)) } })
            .isEqualTo("BUILT_IN")
        assertThat(refusal { asUser(admin) { roles.delete(auditor.id, auditor.version) } }).isEqualTo("BUILT_IN")

        val person = accounts.anActiveUser(transactions, tenant, password)
        val grant = asUser(admin) { grants.grant(person.id.value, role.id, null) }
        assertThat(refusal { asUser(admin) { roles.delete(role.id, role.version) } }).isEqualTo("IN_USE")
        Audit.assertAudited(tenant.id, "authz.role.created", role.id.toString(), admin.id.toString())
        Audit.assertAudited(tenant.id, "authz.grant.created", grant.id.toString(), admin.id.toString())
        val unused = asUser(admin) { roles.create("Unused ${UUID.randomUUID()}", null, setOf(Permission.AUDIT_READ)) }
        asUser(admin) { roles.delete(unused.id, unused.version) }
        Audit.assertAudited(tenant.id, "authz.role.deleted", unused.id.toString(), admin.id.toString())
        asUser(person) { assertThat(policy.allows(Permission.AUDIT_READ)).isTrue() }

        // Changing the role's permissions changes what its holders may do at once.
        asUser(admin) { roles.update(role.id, role.version, role.name, null, setOf(Permission.DIRECTORY_READ)) }
        asUser(person) { assertThat(policy.allows(Permission.AUDIT_READ)).isFalse() }
        Audit.assertAudited(tenant.id, "authz.role.updated", role.id.toString(), admin.id.toString())
    }

    @Test
    fun `grants are refused when they cannot apply, and the tenant admin grant keeps the old flag in step`() {
        val person = accounts.anActiveUser(transactions, tenant, password)
        val unit = aUnit()
        assertThat(refusal { asUser(admin) { grants.grant(person.id.value, builtIn(BuiltInRole.AUDITOR).id, unit.id) } })
            .isEqualTo("NOT_SCOPABLE")
        assertThat(refusal { asUser(admin) { grants.grant(person.id.value, builtIn(BuiltInRole.ORG_ADMIN).id, UUID.randomUUID()) } })
            .isEqualTo("UNKNOWN_UNIT")
        assertThat(refusal { asUser(admin) { grants.grant(person.id.value, builtIn(BuiltInRole.USER).id, null) } }).isEqualTo("IMPLICIT")

        val grant = asUser(admin) { grants.grant(person.id.value, builtIn(BuiltInRole.TENANT_ADMIN).id, null) }
        assertThat(refusal { asUser(admin) { grants.grant(person.id.value, builtIn(BuiltInRole.TENANT_ADMIN).id, null) } })
            .isEqualTo("DUPLICATE")
        assertThat(transactions.readInTenant(tenant.id) { legacyFlag.flagged() }).contains(person.id)

        asUser(admin) { grants.revoke(grant.id) }
        assertThat(transactions.readInTenant(tenant.id) { legacyFlag.flagged() }).doesNotContain(person.id)
        Audit.assertAudited(tenant.id, "authz.grant.revoked", grant.id.toString(), admin.id.toString())
    }

    @Test
    fun `two administrators revoking each other's admin grant at once leave one administrator`() {
        val solo = registry.aTestTenant(transactions, settings, "last-admin")
        val first = accounts.anActiveUser(transactions, solo, password, tenantAdmin = true)
        val second = accounts.anActiveUser(transactions, solo, password, tenantAdmin = true)
        val grantOf = { user: UserAccount -> asUser(first, solo) { grants.grantsOf(user.id.value).single().id } }
        val targets = listOf(first to grantOf(second), second to grantOf(first))
        val ready = CountDownLatch(2)
        val go = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val outcomes =
            targets
                .map { (actor, grant) ->
                    pool.submit<Result<Unit>> {
                        ready.countDown()
                        go.await()
                        runCatching { asUser(actor, solo) { grants.revoke(grant) } }
                    }
                }.also {
                    ready.await(5, TimeUnit.SECONDS)
                    go.countDown()
                }.map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        assertThat(outcomes.count { it.isSuccess }).isEqualTo(1)
        assertThat((outcomes.single { it.isFailure }.exceptionOrNull() as RefusedException).reason).isEqualTo("LAST_ADMIN")
    }

    @Test
    fun `levels keep dense ranks, clearance decides access to classified objects`() {
        val levelsTenant = registry.aTestTenant(transactions, settings, "levels")
        val levelsAdmin = accounts.anActiveUser(transactions, levelsTenant, password, tenantAdmin = true)
        val secret = asUser(levelsAdmin, levelsTenant) { classification.create("Board secret", null) }
        assertThat(secret.rank).isEqualTo(4)

        val reordered =
            asUser(levelsAdmin, levelsTenant) {
                val all = classification.list()
                classification.reorder(listOf(all[0].id, all[1].id, secret.id, all[2].id, all[3].id))
            }
        assertThat(reordered.map { it.name }).containsExactly("Public", "Internal", "Board secret", "Confidential", "Strictly Confidential")
        assertThat(reordered.map { it.rank }).containsExactly(0, 1, 2, 3, 4)

        val person = accounts.anActiveUser(transactions, levelsTenant, password)
        asUser(person, levelsTenant) { assertThat(policy.allows(Permission.DIRECTORY_READ, Resource.Classified(null, 2))).isFalse() }
        asUser(levelsAdmin, levelsTenant) { classification.setClearance(person.id.value, secret.id) }
        asUser(person, levelsTenant) {
            assertThat(policy.allows(Permission.DIRECTORY_READ, Resource.Classified(null, 2))).isTrue()
            assertThat(policy.allows(Permission.DIRECTORY_READ, Resource.Classified(null, 3))).isFalse()
        }
        assertThat(refusal { asUser(levelsAdmin, levelsTenant) { classification.delete(secret.id) } }).isEqualTo("IN_USE")
        Audit.assertAudited(levelsTenant.id, "authz.clearance.changed", person.id.toString(), levelsAdmin.id.toString())
        Audit.assertAudited(levelsTenant.id, "authz.classification.created", secret.id.toString(), levelsAdmin.id.toString())
        Audit.assertAudited(levelsTenant.id, "authz.classification.reordered", null, levelsAdmin.id.toString())

        val spare = asUser(levelsAdmin, levelsTenant) { classification.create("Spare", null) }
        asUser(levelsAdmin, levelsTenant) {
            classification.rename(spare.id, spare.version, "Spare level", null)
            classification.delete(spare.id)
        }
        Audit.assertAudited(levelsTenant.id, "authz.classification.updated", spare.id.toString(), levelsAdmin.id.toString())
        Audit.assertAudited(levelsTenant.id, "authz.classification.deleted", spare.id.toString(), levelsAdmin.id.toString())
    }

    @Test
    fun `a user who becomes privileged where only administrators need MFA is signed out`() {
        val person = accounts.anActiveUser(transactions, tenant, password)
        val session = mvc.signIn(tenant, person.email, password)
        val me = {
            mvc
                .get()
                .uri("/api/v1/me")
                .with(onHost(tenant.slug))
                .cookie(session)
                .exchange()
        }
        assertThat(me()).hasStatus(HttpStatus.OK)

        asUser(admin) { grants.grant(person.id.value, builtIn(BuiltInRole.ORG_ADMIN).id, null) }

        assertThat(me()).hasStatus(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `the SQL filter selects exactly the units the in-memory decision allows`() {
        val root = aUnit()
        val left = aUnit(root)
        val leftChild = aUnit(left)
        val right = aUnit(root)
        val scoped = accounts.anActiveUser(transactions, tenant, password)
        asUser(admin) { grants.grant(scoped.id.value, builtIn(BuiltInRole.ORG_ADMIN).id, left.id) }
        val candidates = listOf(root, left, leftChild, right).map { it.id }

        for (user in listOf(scoped, admin, accounts.anActiveUser(transactions, tenant, password))) {
            asUser(user) {
                val bySql =
                    tenantDsl
                        .current()
                        .select(ORG_UNIT.ID)
                        .from(ORG_UNIT)
                        .where(ORG_UNIT.ID.`in`(candidates))
                        .and(policy.condition(Permission.ORG_MANAGE, ORG_UNIT.ID))
                        .fetchSet(ORG_UNIT.ID)
                val inMemory = candidates.filter { policy.allows(Permission.ORG_MANAGE, Resource.Unit(it)) }.toSet()
                assertThat(bySql).describedAs("user ${user.id}").isEqualTo(inMemory)
            }
        }
        asUser(scoped) {
            assertThat(candidates.filter { policy.allows(Permission.ORG_MANAGE, Resource.Unit(it)) }).containsExactly(left.id, leftChild.id)
        }
    }
}
