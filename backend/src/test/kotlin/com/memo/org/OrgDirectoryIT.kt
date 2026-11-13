package com.memo.org

import com.memo.authz.internal.RoleStore
import com.memo.common.authz.AccessAdministration
import com.memo.common.authz.BuiltInRole
import com.memo.common.authz.RoleHolders
import com.memo.common.context.ActorContext
import com.memo.common.tenant.TenantId
import com.memo.common.tenant.TenantTransactions
import com.memo.tenancy.TenantRegistry
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.aTenant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDate
import java.util.UUID

/**
 * The read facades other modules resolve assignees with (design E04 §1, test plan T1): position holders on a date, a
 * person's position, unit heads and ancestors, the reporting line upwards, and who holds a role in a unit.
 */
class OrgDirectoryIT : IntegrationTest() {
    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var seeding: OrgSeeding

    @Autowired lateinit var directory: OrgDirectory

    @Autowired lateinit var access: AccessAdministration

    @Autowired lateinit var roles: RoleStore

    @Autowired lateinit var roleHolders: RoleHolders

    private var tenant = TenantId(UUID(0, 0))
    private val start = LocalDate.of(2026, 1, 1)
    private val entity = UUID.randomUUID()
    private val head = UUID.randomUUID()
    private val credit = UUID.randomUUID()
    private val branch = UUID.randomUUID()
    private val sibling = UUID.randomUUID()
    private val md = UUID.randomUUID()
    private val gm = UUID.randomUUID()
    private val manager = UUID.randomUUID()
    private val officer = UUID.randomUUID()
    private val vacant = UUID.randomUUID()
    private val alice = UUID.randomUUID()
    private val bob = UUID.randomUUID()
    private val carol = UUID.randomUUID()
    private val dave = UUID.randomUUID()

    @BeforeEach
    fun organisation() {
        tenant = registry.aTenant("orgdir")
        tx {
            seeding.legalEntity(entity, "Bank", start)
            seeding.unit(head, entity, null, "HO", "Head office", start)
            seeding.unit(credit, entity, head, "CR", "Credit", start)
            seeding.unit(branch, entity, credit, "BR", "Branch", start)
            seeding.unit(sibling, entity, head, "FI", "Finance", start)
            seeding.position(md, head, "Managing Director", head = true, start)
            seeding.position(gm, credit, "GM Credit", head = true, start)
            seeding.position(manager, branch, "Branch Manager", head = true, start)
            seeding.position(officer, branch, "Credit Officer", head = false, start)
            seeding.position(vacant, sibling, "Head of Finance", head = true, start)
            seeding.assignment(md, alice, start)
            seeding.assignment(gm, bob, start)
            seeding.assignment(manager, carol, start)
            // Dave starts next year: not a holder today.
            seeding.assignment(officer, dave, directory.today().plusYears(1))
            seeding.reportingLine(officer, manager, start)
            seeding.reportingLine(manager, gm, start)
            seeding.reportingLine(gm, md, start)
        }
    }

    @Test
    fun `holders and positions on a date, heads, ancestors and the line upwards`() {
        tx {
            assertThat(directory.holders(listOf(md, gm, officer, vacant))).containsOnly(entry(md, alice), entry(gm, bob))
            assertThat(directory.holders(listOf(officer), directory.today().plusYears(1).plusDays(1))).containsOnly(entry(officer, dave))
            assertThat(directory.positionsOf(listOf(carol, dave)).mapValues { it.value.id }).containsOnly(entry(carol, manager))

            assertThat(directory.headOf(branch)?.id).isEqualTo(manager)
            assertThat(directory.headOf(sibling)?.id).isEqualTo(vacant)
            assertThat(directory.ancestors(branch)).containsExactly(credit, head)
            assertThat(directory.ancestors(head)).isEmpty()

            assertThat(directory.reportsTo(officer, 1)?.id).isEqualTo(manager)
            assertThat(directory.reportsTo(officer, 3)?.id).isEqualTo(md)
            assertThat(directory.reportsTo(officer, 4)).isNull()
            assertThat(directory.reportsTo(vacant, 1)).isNull()
        }
        assertThatThrownBy { tx { directory.reportsTo(officer, 0) } }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a role is held tenant-wide or for a unit and everything below it`() {
        tx {
            val orgAdmin = checkNotNull(roles.builtInRoleId(BuiltInRole.ORG_ADMIN))
            access.grantBuiltIn(alice, BuiltInRole.ORG_ADMIN)
            access.grantBuiltIn(bob, BuiltInRole.ORG_ADMIN, credit)
            access.grantBuiltIn(carol, BuiltInRole.ORG_ADMIN, sibling)

            assertThat(roleHolders.holders(orgAdmin, branch)).containsExactlyInAnyOrder(alice, bob)
            assertThat(roleHolders.holders(orgAdmin, sibling)).containsExactlyInAnyOrder(alice, carol)
            assertThat(roleHolders.holders(orgAdmin, null)).containsExactly(alice)
            assertThat(roleHolders.exists(orgAdmin)).isTrue()
            assertThat(roleHolders.exists(UUID.randomUUID())).isFalse()
        }
    }

    private fun <T> tx(block: () -> T): T = ActorContext.asSystem("org-directory-test") { transactions.inTenant(tenant, block) }

    private fun entry(
        key: UUID,
        value: UUID,
    ) = org.assertj.core.api.Assertions
        .entry(key, value)
}
