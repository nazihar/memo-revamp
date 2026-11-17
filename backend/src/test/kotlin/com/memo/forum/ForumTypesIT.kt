package com.memo.forum

import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.review.ReviewState
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.RefusedException
import com.memo.forum.internal.ForumRejectedException
import com.memo.forum.internal.ForumTypeLifecycle
import com.memo.forum.internal.ForumTypePreset
import com.memo.forum.internal.ForumTypes
import com.memo.identity.UserAccount
import com.memo.identity.UserAccounts
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
 * Forum types (test plan E01, T1; ADR-0056): created from a preset as draft 1, changed behind its revision, submitted by
 * one administrator and published or sent back by another; every step audited; the policy is checked field by field.
 */
class ForumTypesIT : IntegrationTest() {
    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var accounts: UserAccounts

    @Autowired lateinit var types: ForumTypes

    @Autowired lateinit var lifecycle: ForumTypeLifecycle

    private lateinit var tenant: TestTenant
    private lateinit var maker: UserAccount
    private lateinit var checker: UserAccount

    @BeforeEach
    fun people() {
        tenant = registry.aTestTenant(transactions, settings, "forumtypes", mfaRequired = false)
        maker = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
        checker = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
    }

    @Test
    fun `each preset makes a valid draft 1, and a code is taken once`() {
        ForumTypePreset.entries.forEachIndexed { i, preset ->
            val created = asUser(maker) { types.create("T$i", preset.displayName, preset) }
            assertThat(created.open?.number).isEqualTo(1)
            assertThat(created.open?.state).isEqualTo(ReviewState.DRAFT)
            assertThat(created.published).isNull()
            assertThat(created.open?.settings?.quorum).isEqualTo(preset.settings.quorum)
            Audit.assertAudited(tenant.id, "forum.type.created", created.type.id.toString(), maker.id.value.toString())
        }
        assertThat(problem { types.create("T0", "Again", null) }).isEqualTo("code:CODE_TAKEN")
        assertThat(problem { types.create("bad code", "Lower", null) }).isEqualTo("code:FORMAT")
    }

    @Test
    fun `a draft is changed, submitted and published by someone else, and a new draft copies the published policy`() {
        val type = asUser(maker) { types.create("BC", "Board committee", ForumTypePreset.BOARD_COMMITTEE) }.type
        val draft = asUser(maker) { lifecycle.get(type.id, 1) }
        val changed = draft.settings.copy(quorum = draft.settings.quorum.copy(threshold = QuorumThreshold(ThresholdKind.FRACTION, 2, 3)))
        val saved = asUser(maker) { lifecycle.replace(type.id, 1, draft.revision, changed) }
        assertThat(saved.revision).isEqualTo(draft.revision + 1)
        Audit.assertAudited(tenant.id, "forum.type.version.saved", type.id.toString(), maker.id.value.toString())
        assertThatThrownBy { asUser(maker) { lifecycle.replace(type.id, 1, draft.revision, changed) } }
            .isInstanceOf(ConcurrentModificationException::class.java)

        val submitted = asUser(maker) { lifecycle.submit(type.id, 1, saved.revision) }
        assertThat(submitted.state).isEqualTo(ReviewState.IN_REVIEW)
        Audit.assertAudited(tenant.id, "forum.type.version.submitted", type.id.toString(), maker.id.value.toString())
        refused("WRONG_STATE") { asUser(maker) { lifecycle.replace(type.id, 1, submitted.revision, changed) } }
        refused("SAME_PERSON") { asUser(maker) { lifecycle.approve(type.id, 1, null) } }

        val published = asUser(checker) { lifecycle.approve(type.id, 1, "Matches the charter") }
        assertThat(published.state).isEqualTo(ReviewState.PUBLISHED)
        assertThat(published.reviewedBy).isEqualTo(checker.id.value)
        Audit.assertAudited(tenant.id, "forum.type.version.published", type.id.toString(), checker.id.value.toString())
        assertThat(asUser(maker) { types.get(type.id) }.type.publishedVersion).isEqualTo(1)
        assertThat(
            asUser(maker) { types.published(type.id) }
                ?.settings
                ?.quorum
                ?.threshold
                ?.kind,
        ).isEqualTo(ThresholdKind.FRACTION)

        val second = asUser(maker) { lifecycle.newDraft(type.id) }
        assertThat(second.number).isEqualTo(2)
        assertThat(second.settings).isEqualTo(published.settings)
        Audit.assertAudited(tenant.id, "forum.type.draft_created", type.id.toString(), maker.id.value.toString())
        refused("DRAFT_EXISTS") { asUser(maker) { lifecycle.newDraft(type.id) } }
        val again = asUser(maker) { lifecycle.submit(type.id, 2, second.revision) }
        assertThat(problem(checker) { lifecycle.sendBack(type.id, 2, " ") }).isEqualTo("remark:REQUIRED")
        val back = asUser(checker) { lifecycle.sendBack(type.id, 2, "Say who counts") }
        assertThat(back.state).isEqualTo(ReviewState.DRAFT)
        assertThat(back.revision).isGreaterThan(again.revision)
        Audit.assertAudited(tenant.id, "forum.type.version.sent_back", type.id.toString(), checker.id.value.toString())
        assertThat(asUser(maker) { lifecycle.list(type.id) }.map { it.number to it.state })
            .containsExactly(2 to ReviewState.DRAFT, 1 to ReviewState.PUBLISHED)
    }

    @Test
    fun `the policy is checked field by field`() {
        val type = asUser(maker) { types.create("MC", "Management committee", ForumTypePreset.MANAGEMENT_COMMITTEE) }.type
        val draft = asUser(maker) { lifecycle.get(type.id, 1) }
        val s = draft.settings

        fun save(settings: com.memo.forum.internal.ForumTypeSettings) = problem { lifecycle.replace(type.id, 1, draft.revision, settings) }

        assertThat(save(s.copy(quorum = s.quorum.copy(composition = listOf(QuorumComposition("independent", atLeast = 1))))))
            .isEqualTo("quorum.composition[0].attribute:UNKNOWN_ATTRIBUTE")
        assertThat(
            save(s.copy(quorum = s.quorum.copy(composition = listOf(QuorumComposition("management", atLeast = 1, majority = true))))),
        ).isEqualTo("quorum.composition[0]:QUORUM_INVALID")
        assertThat(save(s.copy(quorum = s.quorum.copy(threshold = QuorumThreshold(ThresholdKind.FRACTION, 4, 3)))))
            .isEqualTo("quorum.threshold:QUORUM_INVALID")
        assertThat(save(s.copy(quorum = s.quorum.copy(basis = QuorumBasis.SEATS)))).isEqualTo("quorum.seats:RANGE")
        assertThat(save(s.copy(quorum = s.quorum.copy(required = listOf(emptyList()))))).isEqualTo("quorum.required[0]:QUORUM_INVALID")
        assertThat(
            save(s.copy(outcomes = s.outcomes + s.outcomes.first())),
        ).isEqualTo("outcomes[${s.outcomes.size}].kind:OUTCOME_DUPLICATE")
        assertThat(save(s.copy(outcomes = s.outcomes.filter { it.kind == OutcomeKind.REJECTED }))).isEqualTo("outcomes:NO_POSITIVE")
        assertThat(save(s.copy(memberAttributes = s.memberAttributes + MemberAttribute("Bad Key", "x"))))
            .isEqualTo("memberAttributes[1].key:FORMAT")
        assertThat(save(s.copy(termPolicy = TermPolicy(reminderDays = listOf(42, 42))))).isEqualTo("termPolicy.reminderDays:RANGE")
        assertThat(save(s.copy(name = " "))).isEqualTo("name:LENGTH")
        // Nothing was saved by any of them.
        assertThat(asUser(maker) { lifecycle.get(type.id, 1) }.revision).isEqualTo(draft.revision)
    }

    @Test
    fun `a type is switched off and on, audited, never deleted`() {
        val type = asUser(maker) { types.create("BOARD", "Board", ForumTypePreset.BOARD) }.type
        assertThat(asUser(maker) { types.setActive(type.id, false) }.type.active).isFalse()
        Audit.assertAudited(tenant.id, "forum.type.deactivated", type.id.toString(), maker.id.value.toString())
        assertThat(asUser(maker) { types.setActive(type.id, true) }.type.active).isTrue()
        Audit.assertAudited(tenant.id, "forum.type.activated", type.id.toString(), maker.id.value.toString())
        assertThat(asUser(maker) { types.list() }.map { it.type.code }).containsExactly("BOARD")
    }

    private fun refused(
        reason: String,
        block: () -> Any,
    ) = assertThatThrownBy { block() }.isInstanceOfSatisfying(RefusedException::class.java) { assertThat(it.reason).isEqualTo(reason) }

    private fun problem(
        user: UserAccount = maker,
        block: () -> Any,
    ): String {
        val rejected = runCatching { asUser(user, block) }.exceptionOrNull() as? ForumRejectedException ?: error("expected a rejection")
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
