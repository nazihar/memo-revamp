package com.memo.org

import com.memo.common.authz.AccessAdministration
import com.memo.common.authz.BuiltInRole
import com.memo.common.authz.ForbiddenException
import com.memo.common.authz.Permission
import com.memo.common.authz.Policy
import com.memo.common.authz.Resource
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.RefusedException
import com.memo.identity.UserAccount
import com.memo.identity.UserAccounts
import com.memo.org.internal.LegalEntities
import com.memo.org.internal.LegalEntity
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
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDate
import java.util.UUID

/** Legal entities and the units tree (ADR-0026; test plan T3: OrgTreeIT, OrgScopeIT, LegalEntityIT). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrgUnitsIT : IntegrationTest() {
    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var accounts: UserAccounts

    @Autowired lateinit var access: AccessAdministration

    @Autowired lateinit var units: OrgUnits

    @Autowired lateinit var entities: LegalEntities

    @Autowired lateinit var policy: Policy

    private val start: LocalDate = LocalDate.of(2026, 1, 1)

    private val tenant: TestTenant by lazy { registry.aTestTenant(transactions, settings, "org") }

    private val admin: UserAccount by lazy { accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true) }

    private fun <T> asUser(
        user: UserAccount,
        on: TestTenant = tenant,
        block: () -> T,
    ): T =
        ActorContext.call(ActorScope(Actor.User(user.id.value), null)) {
            TenantContext.call(on.id) { transactions.inTenant(on.id, block) }
        }

    private fun code() = "U" + UUID.randomUUID().toString().take(8)

    /** An entity with a root, two regions under it and a branch under the first region. */
    private inner class Tree {
        val entity: LegalEntity = asUser(admin) { entities.create("Bank ${code()}", null, null, start) }
        val root: OrgUnit = create(null, "Head office")
        val north: OrgUnit = create(root.id, "North region")
        val south: OrgUnit = create(root.id, "South region")
        val branch: OrgUnit = create(north.id, "North branch")

        fun create(
            parent: UUID?,
            name: String,
            user: UserAccount = admin,
        ): OrgUnit = asUser(user) { units.createUnit(entity.id, parent, code(), name, start) }

        fun reload(unit: OrgUnit) = asUser(admin) { units.unit(unit.id) }
    }

    @Test
    fun `units keep their ancestors, root first, and a move rewrites the whole subtree`() {
        val tree = Tree()
        val leaf = tree.create(tree.branch.id, "Credit desk")
        assertThat(leaf.ancestors).containsExactly(tree.root.id, tree.north.id, tree.branch.id)

        asUser(admin) { units.moveUnit(tree.branch.id, tree.branch.version, tree.south.id) }

        assertThat(tree.reload(tree.branch).parentId).isEqualTo(tree.south.id)
        assertThat(tree.reload(tree.branch).ancestors).containsExactly(tree.root.id, tree.south.id)
        assertThat(tree.reload(leaf).ancestors).containsExactly(tree.root.id, tree.south.id, tree.branch.id)
        Audit.assertAudited(tenant.id, "org.unit.moved", tree.branch.id.toString(), admin.id.toString())
    }

    @Test
    fun `a unit cannot move under itself or its own descendants, nor into another legal entity`() {
        val tree = Tree()
        val other = Tree()

        assertThatThrownBy { asUser(admin) { units.moveUnit(tree.north.id, tree.north.version, tree.branch.id) } }
            .isInstanceOf(RefusedException::class.java)
            .extracting("reason")
            .isEqualTo("CYCLE")
        assertThatThrownBy { asUser(admin) { units.moveUnit(tree.north.id, tree.north.version, tree.north.id) } }
            .extracting("reason")
            .isEqualTo("CYCLE")
        assertThatThrownBy { asUser(admin) { units.moveUnit(tree.north.id, tree.north.version, other.root.id) } }
            .extracting("reason")
            .isEqualTo("OTHER_ENTITY")
        assertThat(tree.reload(tree.north).parentId).isEqualTo(tree.root.id)
    }

    @Test
    fun `a unit closes only when its sub-units are closed by then, and codes are unique among open units`() {
        val tree = Tree()
        assertThatThrownBy { asUser(admin) { units.closeUnit(tree.north.id, tree.north.version, start.plusDays(10)) } }
            .extracting("reason")
            .isEqualTo("NOT_EMPTY")

        val closed = asUser(admin) { units.closeUnit(tree.branch.id, tree.branch.version, start.plusDays(10)) }
        assertThat(closed.validTo).isEqualTo(start.plusDays(10))
        asUser(admin) { units.closeUnit(tree.north.id, tree.north.version, start.plusDays(20)) }

        // The closed branch's code is free again; an open unit's code is not.
        asUser(admin) { units.createUnit(tree.entity.id, tree.south.id, tree.branch.code, "Reused code", start) }
        assertThatThrownBy { asUser(admin) { units.createUnit(tree.entity.id, tree.south.id, tree.south.code, "Clash", start) } }
            .extracting("reason")
            .isEqualTo("DUPLICATE_CODE")
        Audit.assertAudited(tenant.id, "org.unit.closed", tree.branch.id.toString())
    }

    @Test
    fun `a scoped administrator manages their subtree only, and cannot move or close its root`() {
        val tree = Tree()
        val branchAdmin = accounts.anActiveUser(transactions, tenant, aPassword())
        transactions.inTenant(tenant.id) { access.grantBuiltIn(branchAdmin.id.value, BuiltInRole.ORG_ADMIN, tree.north.id) }

        val desk = tree.create(tree.branch.id, "Desk", branchAdmin)
        assertThat(desk.parentId).isEqualTo(tree.branch.id)
        asUser(branchAdmin) { units.updateUnit(tree.north.id, tree.north.version, tree.north.code, "North region (renamed)") }

        assertThatThrownBy { tree.create(tree.south.id, "Outside", branchAdmin) }.isInstanceOf(ForbiddenException::class.java)
        assertThatThrownBy { tree.create(null, "New root", branchAdmin) }.isInstanceOf(ForbiddenException::class.java)
        assertThatThrownBy { asUser(branchAdmin) { units.moveUnit(tree.north.id, tree.reload(tree.north).version, tree.south.id) } }
            .isInstanceOf(ForbiddenException::class.java)
        assertThatThrownBy { asUser(branchAdmin) { units.moveUnit(tree.branch.id, tree.branch.version, tree.south.id) } }
            .isInstanceOf(ForbiddenException::class.java)
        assertThatThrownBy { asUser(branchAdmin) { units.closeUnit(tree.north.id, tree.reload(tree.north).version, start.plusDays(5)) } }
            .isInstanceOf(ForbiddenException::class.java)
        assertThatThrownBy { asUser(branchAdmin) { entities.create("Not mine", null, null, start) } }
            .isInstanceOf(ForbiddenException::class.java)
    }

    @Test
    fun `scope follows the tree, so a unit moved out of a subtree leaves the scoped administrator's reach at once`() {
        val tree = Tree()
        val branchAdmin = accounts.anActiveUser(transactions, tenant, aPassword())
        transactions.inTenant(tenant.id) { access.grantBuiltIn(branchAdmin.id.value, BuiltInRole.ORG_ADMIN, tree.north.id) }
        asUser(branchAdmin) { assertThat(policy.allows(Permission.ORG_MANAGE, Resource.Unit(tree.branch.id))).isTrue() }

        asUser(admin) { units.moveUnit(tree.branch.id, tree.branch.version, tree.south.id) }

        asUser(branchAdmin) {
            assertThat(policy.allows(Permission.ORG_MANAGE, Resource.Unit(tree.branch.id))).isFalse()
            assertThat(policy.allows(Permission.ORG_MANAGE, Resource.Unit(tree.north.id))).isTrue()
            // The scoped grant confers only scopable permissions: directory.read comes from the implicit user role.
            assertThat(policy.allows(Permission.ORG_MANAGE)).isFalse()
        }
    }

    @Test
    fun `legal entities are created and renamed with audit, closed only when their units are closed`() {
        val tree = Tree()
        val renamed = asUser(admin) { entities.update(tree.entity.id, tree.entity.version, "Renamed bank", "RB", "REG-1") }
        assertThat(renamed.shortName).isEqualTo("RB")
        assertThatThrownBy { asUser(admin) { entities.close(tree.entity.id, renamed.version, start.plusDays(30)) } }
            .extracting("reason")
            .isEqualTo("NOT_EMPTY")
        Audit.assertAudited(tenant.id, "org.legal_entity.created", tree.entity.id.toString())
        Audit.assertAudited(tenant.id, "org.legal_entity.updated", tree.entity.id.toString())
    }

    @Test
    fun `another tenant's units do not exist here`() {
        val tree = Tree()
        val other = registry.aTestTenant(transactions, settings, "org-other")
        val otherAdmin = accounts.anActiveUser(transactions, other, aPassword(), tenantAdmin = true)

        assertThatThrownBy { asUser(otherAdmin, other) { units.unit(tree.root.id) } }.isInstanceOf(OrgNotFound::class.java)
    }
}

private typealias OrgNotFound = com.memo.org.internal.OrgNotFoundException
