package com.memo.demo

import com.memo.common.authz.AccessAdministration
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.identity.MfaSeeding
import com.memo.identity.UserAccounts
import com.memo.identity.UserLifecycle
import com.memo.identity.operator.OperatorMfa
import com.memo.memo.MemoSeeding
import com.memo.memo.internal.MemoBox
import com.memo.memo.internal.MemoInbox
import com.memo.org.OrgSeeding
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.FilesIntegrationTest
import com.memo.testsupport.aPassword
import com.memo.workflow.WorkflowQueries
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.env.Environment

/** Demo memos (design E01 §15): every state of the D1 scenario, once, through the real services. */
class DemoMemosIT : FilesIntegrationTest() {
    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var environment: Environment

    @Autowired lateinit var accounts: UserAccounts

    @Autowired lateinit var mfaSeeding: MfaSeeding

    @Autowired lateinit var access: AccessAdministration

    @Autowired lateinit var orgSeeding: OrgSeeding

    @Autowired lateinit var operators: OperatorMfa

    @Autowired lateinit var lifecycle: UserLifecycle

    @Autowired lateinit var memoSeeding: MemoSeeding

    @Autowired lateinit var inbox: MemoInbox

    @Autowired lateinit var queries: WorkflowQueries

    @Test
    fun `the demo memos cover every state, including a task waiting for reassignment, and are seeded once`() {
        val password = aPassword()
        DemoTenantSeeder(
            registry,
            settings,
            transactions,
            environment,
            accounts,
            password,
            mfaSeeding,
            "",
            access,
            orgSeeding,
            operators,
        ).seed()
        val seeder = DemoMemoSeeder(accounts, lifecycle, memoSeeding, transactions, password)
        seeder.seed()
        seeder.seed()

        val author = transactions.inTenant(DemoTenant.ID) { accounts.findByEmail(DemoMemoSeeder.AUTHOR)!!.id.value }

        fun box(box: MemoBox) = asUser(author) { inbox.box(box, null, 50).items }
        assertThat(box(MemoBox.DRAFTS)).hasSize(1)
        assertThat(box(MemoBox.SENT).map { it.status.name }).containsOnly("IN_REVIEW", "RETURNED").hasSize(5)
        assertThat(box(MemoBox.CLOSED).map { it.status.name }).containsExactlyInAnyOrder("APPROVED", "REJECTED", "WITHDRAWN")
        assertThat(box(MemoBox.SENT).single { it.subject == "Office lease extension" }.round).isEqualTo(2)

        val admin = transactions.inTenant(DemoTenant.ID) { accounts.findByEmail(DemoMemoSeeder.ADMIN)!!.id.value }
        assertThat(asUser(admin) { queries.openTasks(blockedOnly = true, assignee = null, cursor = null, limit = 10).items }).hasSize(1)
        val credit = transactions.inTenant(DemoTenant.ID) { accounts.findByEmail(DemoMemoSeeder.HEAD_CREDIT)!!.id.value }
        assertThat(asUser(credit) { inbox.tasks(null, 25).items }).hasSize(2)
    }

    private fun <T> asUser(
        user: java.util.UUID,
        block: () -> T,
    ): T =
        ActorContext.call(ActorScope(Actor.User(user), null)) {
            TenantContext.call(DemoTenant.ID) { transactions.readInTenant(DemoTenant.ID, block) }
        }
}
