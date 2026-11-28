package com.memo.demo

import com.memo.common.authz.AccessAdministration
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.files.FileRenditions
import com.memo.files.FileUploads
import com.memo.files.RenditionState
import com.memo.files.internal.RenditionRetry
import com.memo.files.internal.RenditionScheduler
import com.memo.files.internal.RenditionWorker
import com.memo.identity.MfaSeeding
import com.memo.identity.UserAccounts
import com.memo.identity.operator.OperatorMfa
import com.memo.imports.ImportSeeding
import com.memo.imports.internal.report.ImportJobs
import com.memo.imports.internal.report.ImportReports
import com.memo.imports.internal.report.ImportVerification
import com.memo.imports.internal.run.ImportRunControl
import com.memo.imports.internal.run.ImportRunner
import com.memo.memo.MemoAccessSeeding
import com.memo.memo.MemoSeeding
import com.memo.memo.MemoTemplateSeeding
import com.memo.memo.MemoWatchSeeding
import com.memo.memo.internal.MemoCopies
import com.memo.memo.internal.MemoCopyStore
import com.memo.memo.internal.MemoNotes
import com.memo.memo.internal.MemoShares
import com.memo.memo.internal.MemoWatchStore
import com.memo.memo.internal.Memos
import com.memo.memo.internal.NoteKind
import com.memo.org.OrgSeeding
import com.memo.search.SearchSeeding
import com.memo.search.internal.SavedSearches
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.AppRoleJdbc.long
import com.memo.testsupport.FilesIntegrationTest
import com.memo.testsupport.aPassword
import com.memo.workflow.WorkflowSeeding
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.env.Environment
import java.time.Duration
import java.util.UUID

/**
 * Phase 3 E03 demo data (T9): the unit reader, the letterhead, and a memo with notes, a share and read copies, once. Phase
 * 3 E05 (T8): Demo User follows their memos and has one saved search. Phase 3 E06 (T10): the admin is cleared to import,
 * and the synthetic legacy sample is imported once as a completed batch with a balanced report.
 */
class DemoAccessIT : FilesIntegrationTest() {
    @Autowired lateinit var committees: DemoForumSeeder

    @Autowired lateinit var forumDirectory: com.memo.forum.ForumDirectory

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

    @Autowired lateinit var watchSeeding: MemoWatchSeeding

    @Autowired lateinit var searchSeeding: SearchSeeding

    @Autowired lateinit var watchStore: MemoWatchStore

    @Autowired lateinit var savedSearches: SavedSearches

    @Autowired lateinit var policy: com.memo.common.authz.Policy

    @Autowired lateinit var importSeeding: ImportSeeding

    @Autowired lateinit var importControl: ImportRunControl

    @Autowired lateinit var importRunner: ImportRunner

    @Autowired lateinit var importJobs: ImportJobs

    @Autowired lateinit var verification: ImportVerification

    @Autowired lateinit var reports: ImportReports

    @Autowired lateinit var fileRenditions: FileRenditions

    @Autowired internal lateinit var renditionScheduling: RenditionScheduler

    @Autowired internal lateinit var renditionWorker: RenditionWorker

    @Test
    fun `the auditor reads the Credit Division, Credit facility has a letterhead, and one memo shows notes, a share and copies`() {
        val password = seedDemo()

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

        // Phase 4 E08 T8: the auditor is the committees' oversight (forum.read), so the register and analytics show it.
        assertThat(asUser(id(DemoAccessSeeder.AUDITOR)) { policy.allows(com.memo.common.authz.Permission.FORUM_READ) }).isTrue()

        val admin = id(DemoAccessSeeder.ADMIN)
        // E06: the demo admin holds memo.import (a privileged permission no built-in role carries).
        assertThat(asUser(admin) { policy.allows(com.memo.common.authz.Permission.MEMO_IMPORT) }).isTrue()
        // ... and is cleared to Confidential, the highest level the legacy sample writes (IMPORTER_NOT_CLEARED otherwise).
        assertThat(asUser(admin) { policy.clearanceRank() }).isGreaterThanOrEqualTo(DemoAccessSeeder.IMPORTER_CLEARANCE)
        val type = asUser(admin) { memoSeeding.fixedType(DemoWorkflowSeeder.NAME, DemoWorkflowSeeder.KEY) }
        assertThat(asUser(admin) { templateSeeding.hasTemplate(type) }).isTrue()

        // E05: following and a saved search, once.
        val following = DemoFollowingSeeder(watchSeeding, searchSeeding, accounts, transactions, password)
        following.seed()
        following.seed()
        assertThat(asUser(author) { watchStore.reason(memo, author) }).isNotNull()
        assertThat(asUser(author) { savedSearches.list() }.map { it.name }).containsOnlyOnce("Lease and rent")
    }

    @Test
    fun `the synthetic legacy sample is imported once, as a completed batch with a balanced report`() {
        val password = seedDemo()
        // Test contexts share the scheduler table: the import's jobs are run here, as the scheduler would run them.
        importControl.jobs = false
        importJobs.jobs = false
        renditionScheduling.jobs = false
        try {
            val seeder = DemoLegacySeeder(accounts, transactions, importSeeding, password)
            val seeded = checkNotNull(seeder.seed())
            assertThat(listOf(seeded.created, seeded.state, seeded.originalsNotReceived)).containsExactly(true, "IMPORTING", 0)
            val batch = seeded.batchId
            TenantContext.call(DemoTenant.ID) {
                var steps = 0
                while (importRunner.step(batch) == Duration.ZERO) check(++steps < MAX_STEPS) { "the import did not end" }
                verification.verify(batch)
            }
            renditionsDone(batch)
            assertThat(TenantContext.call(DemoTenant.ID) { reports.step(batch) }).describedAs("the report is stored").isNull()

            val again = checkNotNull(seeder.seed())
            assertThat(listOf(again.created, again.batchId, again.state)).containsExactly(false, batch, "COMPLETED")
            AppRoleJdbc.inTransaction(DemoTenant.ID) { c ->
                assertThat(c.long("select count(*) from imports_batch where name = ?", SyntheticLegacy.BATCH)).isOne()
                val results = c.pairs("select legacy_number, result from imports_row where batch_id = ? order by row_number", batch)
                assertThat(results.filter { it.second == "IMPORTED" }.map { it.first }).containsExactlyElementsOf(SyntheticLegacy.IMPORTED)
                assertThat(results.filter { it.second == "REJECTED" }.map { it.first }.distinct())
                    .containsExactlyElementsOf(SyntheticLegacy.REJECTED)
                assertThat(c.pairs("select code, level from imports_problem where batch_id = ?", batch).map { it.first }).contains(
                    "NUMBER_DUPLICATE_IN_BATCH",
                    "DATE_INVALID",
                    "IN_FLIGHT_NOT_IMPORTED",
                    "PERSON_NOT_LINKED",
                    "PATH_CASE_MATCH",
                    "PATH_CASE_ONLY",
                    "FILE_UNREFERENCED",
                )
                val report = c.pairs("select report->>'balanced', report->>'complete' from imports_batch where id = ?", batch).single()
                assertThat(report).describedAs("balanced and complete").isEqualTo("true" to "true")
                assertThat(c.long(IMPORTED_MEMOS, batch)).isEqualTo(SyntheticLegacy.IMPORTED.size.toLong())
                // The leaver is linked by email; the departed initiator given by name only is not.
                assertThat(c.long(INITIATORS + "initiator_email = ? and initiator_user_id is not null", batch, LEAVER)).isOne()
                assertThat(c.long(INITIATORS + "initiator_name = ? and initiator_user_id is null", batch, SyntheticLegacy.DEPARTED)).isOne()
            }
            // Demo User recommended the departed initiator's memo, so they read it (a participant, cleared to Internal).
            val departed =
                AppRoleJdbc.inTransaction(DemoTenant.ID) { c ->
                    c.pairs("select memo_id, result from imports_row where batch_id = ? and legacy_number = 'HO/2020/0003'", batch).single()
                }
            val author = transactions.inTenant(DemoTenant.ID) { accounts.findByEmail(DemoAccessSeeder.AUTHOR)!!.id.value }
            assertThat(asUser(author) { memos.get(UUID.fromString(departed.first)).subject })
                .isEqualTo("Sponsorship of the regional cricket league")
        } finally {
            importControl.jobs = true
            importJobs.jobs = true
            renditionScheduling.jobs = true
        }
    }

    @Test
    fun `the synthetic legacy sample is the same bytes every time`() {
        fun digest(bytes: ByteArray) =
            java.util.HexFormat
                .of()
                .formatHex(
                    java.security.MessageDigest
                        .getInstance("SHA-256")
                        .digest(bytes),
                )
        val first = SyntheticLegacy.folder().mapValues { digest(it.value) } + ("workbook" to digest(SyntheticLegacy.workbook()))
        val second = SyntheticLegacy.folder().mapValues { digest(it.value) } + ("workbook" to digest(SyntheticLegacy.workbook()))
        assertThat(second).isEqualTo(first)
        assertThat(SyntheticLegacy.sample().originals.keys).doesNotContain("Thumbs.db").hasSize(first.size - 2)
    }

    /** The demo tenant, its workflows, memos and access, as at start-up; returns the demo password used. */
    private fun seedDemo(): String {
        val password = aPassword()
        DemoTenantSeeder(registry, settings, transactions, environment, accounts, password, mfaSeeding, "", grants, orgSeeding, operators)
            .seed()
        DemoWorkflowSeeder(accounts, workflowSeeding, memoSeeding, transactions, committees, forumDirectory).seed()
        // As at start-up: the demo memos first (DemoMemosIT expects them, whichever test runs first).
        DemoMemoSeeder(accounts, lifecycle, memoSeeding, transactions, uploads, password).seed()
        val seeder = DemoAccessSeeder(accounts, clock, transactions, grants, memoSeeding, accessSeeding, templateSeeding, password)
        seeder.seed()
        seeder.seed()
        return password
    }

    /** Builds every pending rendition of the originals [batch] stored, as the rendition job would (ImportEndTest). */
    private fun renditionsDone(batch: UUID) {
        val versions =
            AppRoleJdbc.inTransaction(DemoTenant.ID) { c ->
                c.pairs(STORED_VERSIONS, batch).map { UUID.fromString(checkNotNull(it.first)) }
            }
        val pending =
            transactions.readInTenant(DemoTenant.ID) { fileRenditions.states(versions) }.flatMap { (version, kinds) ->
                kinds.filterValues { it == RenditionState.PENDING }.keys.map { version to it }
            }
        ActorContext.asSystem("files-rendition") {
            TenantContext.call(DemoTenant.ID) {
                pending.forEach { (version, kind) ->
                    run attempts@{
                        repeat(RenditionWorker.MAX_ATTEMPTS) {
                            try {
                                renditionWorker.run(version, kind)
                                return@attempts
                            } catch (_: RenditionRetry) {
                                // As the scheduler would: again.
                            }
                        }
                    }
                }
            }
        }
    }

    /** The first two columns of each row of [sql], as text. */
    private fun java.sql.Connection.pairs(
        sql: String,
        vararg params: Any,
    ): List<Pair<String?, String?>> =
        prepareStatement(sql).use { st ->
            params.forEachIndexed { i, p -> st.setObject(i + 1, p) }
            st.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) to rs.getString(2) else null }.toList() }
        }

    private fun <T> asUser(
        user: UUID,
        block: () -> T,
    ): T =
        ActorContext.call(ActorScope(Actor.User(user), null)) {
            TenantContext.call(DemoTenant.ID) { transactions.inTenant(DemoTenant.ID, block) }
        }

    private companion object {
        const val MAX_STEPS = 50
        const val LEAVER = "leaver@demo.example"
        const val IMPORTED_MEMOS =
            "select count(*) from memo where origin = 'IMPORTED' and id in (select memo_id from imports_row where batch_id = ?)"
        const val INITIATORS = "select count(*) from memo_legacy where batch_id = ? and "
        const val STORED_VERSIONS =
            "select stored_version_id, batch_id from imports_row_file where batch_id = ? and stored_version_id is not null"
    }
}
