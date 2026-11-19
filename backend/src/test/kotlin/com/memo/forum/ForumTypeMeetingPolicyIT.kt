package com.memo.forum

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.review.ReviewState
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.forum.internal.ForumRejectedException
import com.memo.forum.internal.ForumTypeLifecycle
import com.memo.forum.internal.ForumTypePreset
import com.memo.forum.internal.ForumTypes
import com.memo.identity.UserAccount
import com.memo.identity.UserAccounts
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.Audit
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.TestTenant
import com.memo.testsupport.aPassword
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.anActiveUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * A committee type's meeting policy (design E05 §1.8; test plan T1 `ForumTypeMeetingPolicyIT`): the defaults for every
 * preset and for versions stored before it existed, its checks, and the maker-checker path with its audit; the pinned
 * version's policy is what the meeting module reads.
 */
class ForumTypeMeetingPolicyIT : IntegrationTest() {
    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var accounts: UserAccounts

    @Autowired lateinit var types: ForumTypes

    @Autowired lateinit var lifecycle: ForumTypeLifecycle

    @Autowired lateinit var policies: ForumPolicies

    private lateinit var tenant: TestTenant
    private lateinit var maker: UserAccount
    private lateinit var checker: UserAccount

    @BeforeEach
    fun people() {
        tenant = registry.aTestTenant(transactions, settings, "meetingpolicy", mfaRequired = false)
        maker = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
        checker = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
    }

    @Test
    fun `every preset uses the committee's name and never overrides a quorum, and null reads as the defaults`() {
        ForumTypePreset.entries.forEachIndexed { i, preset ->
            val created = asUser(maker) { types.create("P$i", preset.displayName, preset) }
            assertThat(created.open?.settings?.meetings).isEqualTo(MeetingPolicy())
        }
        val type = asUser(maker) { types.create("OLD", "Stored before E05", ForumTypePreset.BOARD) }.type
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            c.prepareStatement("update forum_type_version set meetings = null where forum_type_id = ?").use {
                it.setObject(1, type.id)
                it.executeUpdate()
            }
        }

        val read = asUser(maker) { lifecycle.get(type.id, 1) }.settings.meetings
        assertThat(read).isEqualTo(MeetingPolicy())
        assertThat(read.invitationTitle).isEqualTo(InvitationTitle.NAME)
        assertThat(read.neutralTitle).isEqualTo("Committee meeting")
        assertThat(read.replyDeadlineWorkingDays).isEqualTo(2)
        assertThat(read.replyReminder && read.meetingReminder).isTrue()
        assertThat(read.quorumOverride).isEqualTo(QuorumOverride.NEVER)
    }

    @Test
    fun `the policy's checks name their fields`() {
        val type = asUser(maker) { types.create("BC", "Board committee", ForumTypePreset.BOARD_COMMITTEE) }.type
        val draft = asUser(maker) { lifecycle.get(type.id, 1) }

        fun save(policy: MeetingPolicy) = problem { lifecycle.replace(type.id, 1, draft.revision, draft.settings.copy(meetings = policy)) }

        assertThat(save(MeetingPolicy(invitationTitle = InvitationTitle.NEUTRAL, neutralTitle = " ")))
            .isEqualTo("meetings.neutralTitle:NEUTRAL_TITLE_REQUIRED")
        assertThat(save(MeetingPolicy(neutralTitle = "x".repeat(101)))).isEqualTo("meetings.neutralTitle:LENGTH")
        assertThat(save(MeetingPolicy(replyDeadlineWorkingDays = 11))).isEqualTo("meetings.replyDeadlineWorkingDays:RANGE")
        assertThat(save(MeetingPolicy(replyDeadlineWorkingDays = -1))).isEqualTo("meetings.replyDeadlineWorkingDays:RANGE")
        // A blank neutral title is fine while invitations use the name; 0 working days means no deadline.
        val fine = MeetingPolicy(neutralTitle = "", replyDeadlineWorkingDays = 0)
        assertThat(asUser(maker) { lifecycle.replace(type.id, 1, draft.revision, draft.settings.copy(meetings = fine)) }.settings.meetings)
            .isEqualTo(fine)
    }

    @Test
    fun `a change is reviewed by a second administrator, audited, and pinned for meetings`() {
        val type = asUser(maker) { types.create("BCC", "Board credit committee", ForumTypePreset.BOARD_COMMITTEE) }.type
        val draft = asUser(maker) { lifecycle.get(type.id, 1) }
        val neutral =
            MeetingPolicy(
                invitationTitle = InvitationTitle.NEUTRAL,
                neutralTitle = "Board committee meeting",
                replyDeadlineWorkingDays = 3,
                replyReminder = false,
                quorumOverride = QuorumOverride.WITH_REASON,
            )
        val saved = asUser(maker) { lifecycle.replace(type.id, 1, draft.revision, draft.settings.copy(meetings = neutral)) }
        val row = Audit.assertAudited(tenant.id, "forum.type.version.saved", type.id.toString(), maker.id.value.toString())
        assertThat(row.canonical).contains("NEUTRAL").contains("WITH_REASON")
        val submitted = asUser(maker) { lifecycle.submit(type.id, 1, saved.revision) }
        assertThat(asUser(checker) { lifecycle.approve(type.id, 1, null) }.state).isEqualTo(ReviewState.PUBLISHED)
        assertThat(submitted.settings.meetings).isEqualTo(neutral)

        val pinned = asUser(maker) { policies.version(type.id, 1) }
        assertThat(pinned?.meetings).isEqualTo(neutral)
        val next = asUser(maker) { lifecycle.newDraft(type.id) }
        assertThat(next.settings.meetings).isEqualTo(neutral)
        asUser(maker) { lifecycle.replace(type.id, 2, next.revision, next.settings.copy(meetings = MeetingPolicy())) }
        assertThat(asUser(maker) { policies.version(type.id, 1) }?.meetings).describedAs("version 1 is unchanged").isEqualTo(neutral)
        assertThat(asUser(maker) { policies.published(type.id) }?.meetings).isEqualTo(neutral)
    }

    private fun problem(block: () -> Any): String {
        val rejected = runCatching { asUser(maker, block) }.exceptionOrNull() as? ForumRejectedException ?: error("expected a rejection")
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
