package com.memo.demo

import com.memo.common.authz.AccessAdministration
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.files.FileUploads
import com.memo.identity.MfaSeeding
import com.memo.identity.UserAccounts
import com.memo.identity.operator.OperatorMfa
import com.memo.memo.MemoAccessSeeding
import com.memo.memo.MemoSeeding
import com.memo.memo.MemoTemplateSeeding
import com.memo.memo.internal.MemoCopies
import com.memo.memo.internal.MemoCopyStore
import com.memo.memo.internal.MemoNotes
import com.memo.memo.internal.MemoShares
import com.memo.memo.internal.Memos
import com.memo.memo.internal.NoteKind
import com.memo.org.OrgSeeding
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.FilesIntegrationTest
import com.memo.testsupport.aPassword
import com.memo.workflow.WorkflowSeeding
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.env.Environment
import java.util.UUID

/** Phase 3 E03 demo data (T9): the unit reader, the letterhead, and a memo with notes, a share and read copies, once. */
class DemoAccessIT : FilesIntegrationTest() {
    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var environment: Environment

    @Autowired lateinit var accounts: UserAccounts

    @Autowired lateinit var mfaSeeding: MfaSeeding

    @Autowired lateinit var grants: AccessAdministration

    @Autowired lateinit var orgSeeding: OrgSeeding

    @Autowired lateinit var operators: OperatorMfa

    @Autowired lateinit var workflowSeeding: WorkflowSeeding

    @Autowired lateinit var memoSeeding: MemoSeeding

    @Autowired lateinit var accessSeeding: MemoAccessSeeding

    @Autowired lateinit var templateSeeding: MemoTemplateSeeding

    @Autowired lateinit var uploads: FileUploads

    @Autowired lateinit var lifecycle: com.memo.identity.UserLifecycle

    @Autowired lateinit var clock: java.time.Clock

    @Autowired lateinit var memos: Memos

    @Autowired lateinit var notes: MemoNotes

    @Autowired lateinit var shares: MemoShares

    @Autowired lateinit var copies: MemoCopies

    @Autowired lateinit var copyStore: MemoCopyStore

    @Test
    fun `the auditor reads the Credit Division, Credit facility has a letterhead, and one memo shows notes, a share and copies`() {
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
            grants,
            orgSeeding,
            operators,
        ).seed()
        DemoWorkflowSeeder(accounts, workflowSeeding, memoSeeding, transactions).seed()
        // As at start-up: the demo memos first (DemoMemosIT expects them, whichever test runs first).
        DemoMemoSeeder(accounts, lifecycle, memoSeeding, transactions, uploads, password).seed()
        val seeder = DemoAccessSeeder(accounts, clock, transactions, grants, memoSeeding, accessSeeding, templateSeeding, password)
        seeder.seed()
        seeder.seed()

        fun id(email: String) = transactions.inTenant(DemoTenant.ID) { accounts.findByEmail(email)!!.id.value }
        val author = id(DemoAccessSeeder.AUTHOR)
        val memo = checkNotNull(asUser(author) { accessSeeding.find(author, DemoAccessSeeder.SUBJECT) })

        val sheet = asUser(author) { notes.sheet(memo).notes }
        assertThat(sheet.map { it.kind }).containsExactly(NoteKind.COMMENT, NoteKind.COMMENT, NoteKind.CORRECTION)
        assertThat(sheet.first().text).contains("@Demo User")
        assertThat(
            asUser(author) { shares.list(memo) }.map { it.reason },
        ).containsExactly("Check of the branch premises offered as security")
        assertThat(asUser(author) { copies.list(memo) }).singleElement().satisfies({ assertThat(it.sentAt).isNotNull() })
        assertThat(asUser(author) { copyStore.receipts(memo) }.keys).containsExactly(id(DemoAccessSeeder.HEAD_FINANCE))

        // Unit reader: the author sits in Retail Credit, under the Credit Division.
        assertThat(asUser(id(DemoAccessSeeder.AUDITOR)) { memos.get(memo).subject }).isEqualTo(DemoAccessSeeder.SUBJECT)

        val admin = id(DemoAccessSeeder.ADMIN)
        val type = asUser(admin) { memoSeeding.fixedType(DemoWorkflowSeeder.NAME, DemoWorkflowSeeder.KEY) }
        assertThat(asUser(admin) { templateSeeding.hasTemplate(type) }).isTrue()
    }

    private fun <T> asUser(
        user: UUID,
        block: () -> T,
    ): T =
        ActorContext.call(ActorScope(Actor.User(user), null)) {
            TenantContext.call(DemoTenant.ID) { transactions.inTenant(DemoTenant.ID, block) }
        }
}
