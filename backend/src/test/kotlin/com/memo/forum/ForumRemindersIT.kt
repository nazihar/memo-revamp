package com.memo.forum

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.forum.internal.ForumInput
import com.memo.forum.internal.ForumMembers
import com.memo.forum.internal.ForumTermReminders
import com.memo.forum.internal.ForumTypePreset
import com.memo.forum.internal.ForumTypes
import com.memo.forum.internal.Forums
import com.memo.forum.internal.MemberInput
import com.memo.identity.UserAccount
import com.memo.identity.UserAccounts
import com.memo.org.OrgDirectory
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.AppRoleJdbc.long
import com.memo.testsupport.Audit
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.TestTenant
import com.memo.testsupport.aPassword
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.anActiveUser
import com.memo.workflow.internal.DefinitionChecks
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * Term reminders and the workflow link (test plan E01, T3; ADR-0056): the nearest reminder day before a planned term end
 * is sent once to the secretaries (administrators when there are none); an escalate-to-forum step must name an active
 * committee.
 */
class ForumRemindersIT : IntegrationTest() {
    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var accounts: UserAccounts

    @Autowired lateinit var types: ForumTypes

    @Autowired lateinit var forums: Forums

    @Autowired lateinit var members: ForumMembers

    @Autowired lateinit var reminders: ForumTermReminders

    @Autowired lateinit var org: OrgDirectory

    @Autowired lateinit var checks: DefinitionChecks

    @Autowired lateinit var json: JsonMapper

    private lateinit var tenant: TestTenant
    private lateinit var admin: UserAccount
    private lateinit var secretary: UserAccount
    private lateinit var member: UserAccount
    private lateinit var typeId: UUID

    @BeforeEach
    fun setUp() {
        tenant = registry.aTestTenant(transactions, settings, "forumremind", mfaRequired = false)
        admin = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
        secretary = accounts.anActiveUser(transactions, tenant, aPassword())
        member = accounts.anActiveUser(transactions, tenant, aPassword())
        typeId = asUser(admin) { types.create("BC", "Board committee", ForumTypePreset.BOARD_COMMITTEE, publish = true) }.type.id
    }

    private fun told(user: UserAccount): Long =
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            c.long("select count(*) from notification_item where user_id = ? and kind = 'FORUM_TERM_ENDING'", user.id.value)
        }

    @Test
    fun `the nearest reminder is sent once, to the secretaries, and again only at the next reminder day`() {
        val forum = asUser(admin) { forums.create(ForumInput("board-risk", "BRC", "Board Risk Committee", "Risk", typeId)) }
        asUser(admin) { members.add(forum.id, MemberInput(userId = secretary.id.value, role = ForumRole.SECRETARY)) }
        val today = asUser(admin) { org.today() }
        val row =
            asUser(admin) {
                members.add(
                    forum.id,
                    MemberInput(userId = member.id.value, startsOn = today.minusYears(1), termEndsOn = today.plusDays(40)),
                )
            }.row

        assertThat(asUser(admin) { reminders.remindDue() }).isEqualTo(1)
        assertThat(told(secretary)).isEqualTo(1)
        Audit.assertAudited(tenant.id, "forum.term.reminded", row.id.toString(), null)
        // The same day again: nothing new.
        assertThat(asUser(admin) { reminders.remindDue() }).isZero()
        assertThat(told(secretary)).isEqualTo(1)
        // Moving the end to tomorrow makes the one-day reminder due.
        asUser(admin) {
            members.edit(
                forum.id,
                row.id,
                row.version,
                com.memo.forum.internal
                    .MemberEdit(true, emptySet(), null, today.plusDays(1), null),
            )
        }
        assertThat(asUser(admin) { reminders.remindDue() }).isEqualTo(1)
        assertThat(told(secretary)).isEqualTo(2)
    }

    @Test
    fun `without a secretary the administrators are told, and ended rows are left alone`() {
        val forum = asUser(admin) { forums.create(ForumInput("alco", "ALCO", "Asset-Liability Committee", "ALCO", typeId)) }
        val today = asUser(admin) { org.today() }
        asUser(admin) {
            members.add(forum.id, MemberInput(userId = member.id.value, startsOn = today.minusYears(1), termEndsOn = today.plusDays(1)))
        }
        assertThat(asUser(admin) { reminders.remindDue() }).isEqualTo(1)
        assertThat(told(admin)).isEqualTo(1)
        assertThat(told(member)).isZero()
    }

    @Test
    fun `an escalate-to-forum step must name an active committee`() {
        fun problems(key: String) =
            asUser(admin) {
                checks
                    .validate(
                        json.readTree(
                            """
                        {"schemaVersion":2,"start":"board","steps":[
                          {"id":"board","type":"escalate-to-forum","forum":"$key","next":"end"}
                        ]}
                        """,
                        ),
                    ).problems
                    .map { "${it.pointer}:${it.code}" }
            }
        assertThat(problems("board-risk")).containsExactly("/steps/0/forum:UNKNOWN_FORUM")
        val forum = asUser(admin) { forums.create(ForumInput("board-risk", "BRC", "Board Risk Committee", "Risk", typeId)) }
        assertThat(problems("board-risk")).isEmpty()
        asUser(admin) { forums.dissolve(forum.id, forum.version, null, "Merged") }
        assertThat(problems("board-risk")).containsExactly("/steps/0/forum:UNKNOWN_FORUM")
    }

    private fun <T> asUser(
        user: UserAccount,
        block: () -> T,
    ): T =
        ActorContext.call(ActorScope(Actor.User(user.id.value), null)) {
            TenantContext.call(tenant.id) { transactions.inTenant(tenant.id, block) }
        }
}
