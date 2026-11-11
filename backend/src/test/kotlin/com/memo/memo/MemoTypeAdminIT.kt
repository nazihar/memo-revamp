package com.memo.memo

import com.memo.authz.internal.ClassificationAdmin
import com.memo.common.authz.Clearances
import com.memo.common.authz.ForbiddenException
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.RefusedException
import com.memo.identity.UserAccount
import com.memo.identity.UserAccounts
import com.memo.memo.internal.MemoRejectedException
import com.memo.memo.internal.MemoTypeFields
import com.memo.memo.internal.MemoTypes
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
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MockMvcTester

/**
 * Memo types (test plan E01, T3): every tenant gets "General memo"; `memo.types.manage` holders create and edit types
 * with unique names and real classification levels, audited, behind a version guard, and cannot deactivate the last one.
 * The clearance lookup other modules use ([Clearances]) follows clearance changes at once.
 */
class MemoTypeAdminIT : IntegrationTest() {
    @Autowired lateinit var mvc: MockMvcTester

    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var accounts: UserAccounts

    @Autowired lateinit var types: MemoTypes

    @Autowired lateinit var clearances: Clearances

    @Autowired lateinit var classification: ClassificationAdmin

    private lateinit var tenant: TestTenant
    private lateinit var admin: UserAccount
    private lateinit var person: UserAccount
    private lateinit var personPassword: String

    @BeforeEach
    fun people() {
        tenant = registry.aTestTenant(transactions, settings, "types", mfaRequired = false)
        admin = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
        personPassword = aPassword()
        person = accounts.anActiveUser(transactions, tenant, personPassword)
    }

    @Test
    fun `a new tenant has the General memo type, and only type managers see the admin list`() {
        val general = asUser(person) { types.active() }.single()
        assertThat(general.name).isEqualTo("General memo")
        assertThat(general.key).isEqualTo("general")
        assertThat(general.defaultClassificationId).isNull()
        assertThat(general.stepUpOnApprove).isTrue()
        assertThat(general.definitionKey).isEqualTo("sequential-approval")

        assertThatThrownBy { asUser(person) { types.all() } }.isInstanceOf(ForbiddenException::class.java)
        assertThatThrownBy { asUser(person) { types.create(fields("Mine")) } }.isInstanceOf(ForbiddenException::class.java)
        val session = mvc.signIn(tenant, person.email, personPassword)
        assertThat(
            mvc
                .get()
                .uri("/api/v1/admin/memo-types")
                .with(onHost(tenant.slug))
                .cookie(session)
                .exchange(),
        ).hasStatus(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `types have unique names and real levels, edits are guarded and audited, and one type stays active`() {
        val levels = asUser(admin) { clearances.levels() }
        val created = asUser(admin) { types.create(fields("Payment memo", levels[2].id)) }
        Audit.assertAudited(tenant.id, "memo.type.created", created.id.toString(), admin.id.value.toString())
        assertThat(problem { types.create(fields("payment MEMO")) }).isEqualTo("name:duplicate")
        assertThat(problem { types.create(fields("Other", java.util.UUID.randomUUID())) }).isEqualTo("defaultClassificationId:unknown")

        val renamed = asUser(admin) { types.update(created.id, created.version, fields("Payment request", levels[1].id, stepUp = false)) }
        assertThat(renamed.version).isEqualTo(created.version + 1)
        assertThat(renamed.stepUpOnApprove).isFalse()
        Audit.assertAudited(tenant.id, "memo.type.updated", created.id.toString(), admin.id.value.toString())
        assertThatThrownBy { asUser(admin) { types.update(created.id, created.version, fields("Stale")) } }
            .isInstanceOf(ConcurrentModificationException::class.java)

        val general = asUser(admin) { types.all() }.first { it.key == "general" }
        asUser(admin) { types.update(general.id, general.version, fields(general.name, active = false)) }
        assertThat(asUser(person) { types.active() }.map { it.name }).containsExactly("Payment request")
        val last = asUser(admin) { types.get(created.id) }
        assertThatThrownBy { asUser(admin) { types.update(last.id, last.version, fields(last.name, active = false)) } }
            .isInstanceOfSatisfying(RefusedException::class.java) { assertThat(it.reason).isEqualTo("LAST_ACTIVE") }
    }

    @Test
    fun `clearances of other people follow clearance changes at once`() {
        val levels = asUser(admin) { clearances.levels() }
        assertThat(levels.map { it.rank }).containsExactly(0, 1, 2, 3)
        assertThat(asUser(admin) { clearances.lowest() }.id).isEqualTo(levels[0].id)
        assertThat(asUser(admin) { clearances.rankOf(listOf(person.id.value, admin.id.value)) })
            .containsEntry(person.id.value, 0)
            .containsEntry(admin.id.value, 0)

        asUser(admin) { classification.setClearance(person.id.value, levels[3].id) }
        assertThat(asUser(admin) { clearances.rankOf(listOf(person.id.value)) }).containsEntry(person.id.value, 3)
        asUser(admin) { classification.setClearance(person.id.value, levels[1].id) }
        assertThat(asUser(admin) { clearances.rankOf(listOf(person.id.value)) }).containsEntry(person.id.value, 1)
    }

    private fun fields(
        name: String,
        level: java.util.UUID? = null,
        stepUp: Boolean = true,
        active: Boolean = true,
    ) = MemoTypeFields(name, level, stepUp, active)

    private fun problem(block: () -> Any): String {
        val rejected = runCatching { asUser(admin, block) }.exceptionOrNull() as? MemoRejectedException ?: error("expected a rejection")
        return rejected.problems.joinToString { "${it.field}:${it.code}" }
    }

    private fun <T> asUser(
        user: UserAccount,
        block: () -> T,
    ): T =
        ActorContext.call(ActorScope(Actor.User(user.id.value), null)) {
            TenantContext.call(tenant.id) { transactions.inTenant(tenant.id, block) }
        }
}
