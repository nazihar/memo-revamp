package com.memo.demo

import com.memo.common.authz.AccessAdministration
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.forum.ForumDirectory
import com.memo.forum.ForumRole
import com.memo.forum.ForumSeeding
import com.memo.forum.Quorum
import com.memo.forum.QuorumPresence
import com.memo.forum.QuorumSeat
import com.memo.forum.internal.ForumTypeVersionStore
import com.memo.identity.MfaSeeding
import com.memo.identity.UserAccounts
import com.memo.identity.operator.OperatorMfa
import com.memo.org.OrgSeeding
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.aPassword
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.env.Environment

/**
 * The demo committees (test plan E01, T6): three committee types and six committees with the company secretary in each,
 * seeded once; the board committees are quorate when their members attend.
 */
class DemoForumsIT : IntegrationTest() {
    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var environment: Environment

    @Autowired lateinit var accounts: UserAccounts

    @Autowired lateinit var mfaSeeding: MfaSeeding

    @Autowired lateinit var access: AccessAdministration

    @Autowired lateinit var orgSeeding: OrgSeeding

    @Autowired lateinit var operators: OperatorMfa

    @Autowired lateinit var forumSeeding: ForumSeeding

    @Autowired lateinit var directory: ForumDirectory

    @Autowired lateinit var versions: ForumTypeVersionStore

    @Test
    fun `six representative committees with their secretary, seeded once, and the risk committee is quorate`() {
        DemoTenantSeeder(
            registry,
            settings,
            transactions,
            environment,
            accounts,
            aPassword(),
            mfaSeeding,
            "",
            access,
            orgSeeding,
            operators,
        ).seed()
        val seeder = DemoForumSeeder(forumSeeding, accounts, transactions)
        seeder.seed()
        seeder.seed()

        val admin = transactions.inTenant(DemoTenant.ID) { accounts.findByEmail(DemoForumSeeder.ADMIN)!!.id.value }
        val secretary = transactions.inTenant(DemoTenant.ID) { accounts.findByEmail(DemoForumSeeder.SECRETARY)!!.id.value }
        ActorContext.call(ActorScope(Actor.User(admin), null)) {
            TenantContext.call(DemoTenant.ID) {
                transactions.inTenant(DemoTenant.ID) {
                    val keys =
                        listOf(
                            DemoForumSeeder.BOARD,
                            DemoForumSeeder.RISK,
                            DemoForumSeeder.AUDIT,
                            DemoForumSeeder.CREDIT,
                            DemoForumSeeder.ALCO,
                            DemoForumSeeder.EXCO,
                        )
                    for (key in keys) {
                        val forum = directory.forumByKey(key)
                        assertThat(forum).describedAs(key).isNotNull()
                        val members = directory.membersOn(forum!!.id, DemoForumSeeder.START.plusDays(1))
                        assertThat(members.filter { it.role == ForumRole.SECRETARY }.map { it.userId }).containsExactly(secretary)
                        assertThat(members.count { it.role == ForumRole.CHAIR }).describedAs(key).isEqualTo(1)
                    }
                    assertThat(
                        directory.forumByKey(DemoForumSeeder.RISK)?.parentId,
                    ).isEqualTo(directory.forumByKey(DemoForumSeeder.BOARD)?.id)

                    // Everyone on the risk committee attends: the preset's rule (chair or vice-chair, a majority of
                    // independents) holds.
                    val risk = directory.forumByKey(DemoForumSeeder.RISK)!!
                    val seats =
                        directory.membersOn(risk.id, DemoForumSeeder.START.plusDays(1)).map {
                            QuorumSeat(it.memberId, it.role, it.voting, it.attributes)
                        }
                    val rule = versions.published(risk.typeId)!!.settings.quorum
                    val result = Quorum.evaluate(rule, seats, seats.associate { it.memberId to QuorumPresence() })
                    assertThat(result.quorate).describedAs(result.toString()).isTrue()
                }
            }
        }
    }
}
