package com.memo.memo

import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.review.ReviewState
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.RefusedException
import com.memo.identity.UserAccount
import com.memo.identity.UserAccounts
import com.memo.memo.internal.MemoRejectedException
import com.memo.memo.internal.MemoTypeFields
import com.memo.memo.internal.MemoTypeLifecycle
import com.memo.memo.internal.MemoTypeSettings
import com.memo.memo.internal.MemoTypes
import com.memo.memo.internal.RoutePolicy
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Memo-type versions (test plan E01, T1; ADR-0043): a draft is made, changed behind its revision and submitted by one
 * administrator, and published or sent back by another; publishing retires the previous version and changes the type that
 * memos use; every step is audited. The Phase 2 direct edit records a published version too.
 */
class MemoTypeVersionsIT : IntegrationTest() {
    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var accounts: UserAccounts

    @Autowired lateinit var types: MemoTypes

    @Autowired lateinit var lifecycle: MemoTypeLifecycle

    private lateinit var tenant: TestTenant
    private lateinit var maker: UserAccount
    private lateinit var checker: UserAccount

    @BeforeEach
    fun people() {
        tenant = registry.aTestTenant(transactions, settings, "typeversions", mfaRequired = false)
        maker = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
        checker = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
    }

    @Test
    fun `every type starts with a published version 1, a code, and the built-in type has GM`() {
        val general = asUser(maker) { types.active() }.single()
        assertThat(general.code).isEqualTo("GM")
        assertThat(general.publishedVersion).isEqualTo(1)
        val version = asUser(maker) { lifecycle.list(general.id) }.single()
        assertThat(version.state).isEqualTo(ReviewState.PUBLISHED)
        assertThat(version.createdBy).isNull()
        assertThat(version.settings.name).isEqualTo("General memo")

        val created = asUser(maker) { types.create(MemoTypeFields("Credit facility", null, true, true)) }
        assertThat(created.code).isEqualTo("CF")
        val second = asUser(maker) { types.create(MemoTypeFields("Cash forecast", null, true, true)) }
        assertThat(second.code).isEqualTo("CF2")
        assertThat(asUser(maker) { lifecycle.list(created.id) }.single().createdBy).isEqualTo(maker.id.value)
    }

    @Test
    fun `a draft is made, changed, submitted and published by someone else, and the type follows`() {
        val type = asUser(maker) { types.create(MemoTypeFields("Expense approval", null, true, true)) }

        val draft = asUser(maker) { lifecycle.newDraft(type.id, null) }
        assertThat(draft.number).isEqualTo(2)
        assertThat(draft.state).isEqualTo(ReviewState.DRAFT)
        Audit.assertAudited(tenant.id, "memo.type.draft_created", type.id.toString(), maker.id.value.toString())
        refused("DRAFT_EXISTS") { asUser(maker) { lifecycle.newDraft(type.id, null) } }

        val changed = settings("Expense request", "EXP", stepUp = false)
        val saved = asUser(maker) { lifecycle.replace(type.id, 2, draft.revision, changed) }
        assertThat(saved.revision).isEqualTo(draft.revision + 1)
        Audit.assertAudited(tenant.id, "memo.type.draft_updated", type.id.toString(), maker.id.value.toString())
        assertThatThrownBy { asUser(maker) { lifecycle.replace(type.id, 2, draft.revision, changed) } }
            .isInstanceOf(ConcurrentModificationException::class.java)
        // Nothing applies before publishing.
        assertThat(asUser(maker) { types.get(type.id) }.name).isEqualTo("Expense approval")

        val submitted = asUser(maker) { lifecycle.submit(type.id, 2, saved.revision) }
        assertThat(submitted.state).isEqualTo(ReviewState.IN_REVIEW)
        assertThat(submitted.submittedBy).isEqualTo(maker.id.value)
        Audit.assertAudited(tenant.id, "memo.type.submitted", type.id.toString(), maker.id.value.toString())
        refused("WRONG_STATE") { asUser(maker) { lifecycle.replace(type.id, 2, submitted.revision, changed) } }

        refused("SAME_PERSON") { asUser(maker) { lifecycle.approve(type.id, 2, null) } }
        val published = asUser(checker) { lifecycle.approve(type.id, 2, "Fine") }
        assertThat(published.state).isEqualTo(ReviewState.PUBLISHED)
        assertThat(published.reviewedBy).isEqualTo(checker.id.value)
        assertThat(published.reviewRemark).isEqualTo("Fine")
        Audit.assertAudited(tenant.id, "memo.type.published", type.id.toString(), checker.id.value.toString())

        val versions = asUser(maker) { lifecycle.list(type.id) }
        assertThat(versions.map { it.number to it.state }).containsExactly(2 to ReviewState.PUBLISHED, 1 to ReviewState.RETIRED)
        val after = asUser(maker) { types.get(type.id) }
        assertThat(after.name).isEqualTo("Expense request")
        assertThat(after.code).isEqualTo("EXP")
        assertThat(after.stepUpOnApprove).isFalse()
        assertThat(after.publishedVersion).isEqualTo(2)
        refused("WRONG_STATE") { asUser(checker) { lifecycle.approve(type.id, 2, null) } }
    }

    @Test
    fun `sending back needs a remark and returns the version to draft`() {
        val type = asUser(maker) { types.create(MemoTypeFields("Procurement", null, true, true)) }
        val draft = asUser(maker) { lifecycle.newDraft(type.id, settings("Procurement", "PRC")) }
        val submitted = asUser(maker) { lifecycle.submit(type.id, draft.number, draft.revision) }

        assertThat(problem(checker) { lifecycle.sendBack(type.id, submitted.number, " ") }).isEqualTo("remark:REQUIRED")
        refused("SAME_PERSON") { asUser(maker) { lifecycle.sendBack(type.id, submitted.number, "Mine") } }
        val back = asUser(checker) { lifecycle.sendBack(type.id, submitted.number, "Use the procurement workflow") }
        assertThat(back.state).isEqualTo(ReviewState.DRAFT)
        assertThat(back.reviewRemark).isEqualTo("Use the procurement workflow")
        Audit.assertAudited(tenant.id, "memo.type.sent_back", type.id.toString(), checker.id.value.toString())
        // The maker can change it and submit again.
        val again = asUser(maker) { lifecycle.submit(type.id, back.number, back.revision) }
        assertThat(again.state).isEqualTo(ReviewState.IN_REVIEW)
    }

    @Test
    fun `drafts are checked for code format, unique names and codes, and a published definition that fits`() {
        val type = asUser(maker) { types.create(MemoTypeFields("Policy approval", null, true, true)) }
        asUser(maker) { types.create(MemoTypeFields("Travel", null, true, true)) }
        assertThat(problem { lifecycle.newDraft(type.id, settings("Policy approval", "pa")) }).isEqualTo("code:FORMAT")
        assertThat(problem { lifecycle.newDraft(type.id, settings("travel", "PA")) }).isEqualTo("name:duplicate")
        assertThat(problem { lifecycle.newDraft(type.id, settings("Policy approval", "T")) }).isEqualTo("code:duplicate")
        assertThat(problem { lifecycle.newDraft(type.id, settings("Policy approval", "PA", definition = "no-such")) })
            .isEqualTo("definitionKey:unknown")
        assertThat(problem { lifecycle.newDraft(type.id, settings("Policy approval", "PA", policy = RoutePolicy.FIXED)) })
            .isEqualTo("routePolicy:mismatch")
    }

    @Test
    fun `a direct edit from the Phase 2 screen records a new published version, an unchanged one does not`() {
        val type = asUser(maker) { types.create(MemoTypeFields("Board paper", null, true, true)) }
        val renamed = asUser(maker) { types.update(type.id, type.version, MemoTypeFields("Board papers", null, true, true)) }
        assertThat(renamed.publishedVersion).isEqualTo(2)
        val switchedOff = asUser(maker) { types.update(type.id, renamed.version, MemoTypeFields("Board papers", null, true, false)) }
        assertThat(switchedOff.publishedVersion).isEqualTo(2)
        assertThat(switchedOff.active).isFalse()
        assertThat(asUser(maker) { lifecycle.list(type.id) }.map { it.state })
            .containsExactly(ReviewState.PUBLISHED, ReviewState.RETIRED)
    }

    @Test
    fun `a type stays offered for new memos, unchanged, while a new draft is open`() {
        val type = asUser(maker) { types.create(MemoTypeFields("Draft open", null, true, true)) }
        asUser(maker) { lifecycle.newDraft(type.id, settings("Draft renamed", "DO")) }
        assertThat(asUser(maker) { types.active() }.map { it.name }).contains("Draft open").doesNotContain("Draft renamed")
    }

    private fun settings(
        name: String,
        code: String,
        stepUp: Boolean = true,
        definition: String = "sequential-approval",
        policy: RoutePolicy = RoutePolicy.INITIATOR_DEFINED,
    ) = MemoTypeSettings(name, code, null, definition, policy, stepUpOnApprove = stepUp)

    private fun refused(
        reason: String,
        block: () -> Any,
    ) = assertThatThrownBy { block() }.isInstanceOfSatisfying(RefusedException::class.java) { assertThat(it.reason).isEqualTo(reason) }

    private fun problem(
        user: UserAccount = maker,
        block: () -> Any,
    ): String {
        val rejected = runCatching { asUser(user, block) }.exceptionOrNull() as? MemoRejectedException ?: error("expected a rejection")
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
