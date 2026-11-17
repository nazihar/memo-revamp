package com.memo.search

import com.memo.common.authz.AccessAdministration
import com.memo.common.authz.Permission
import com.memo.common.context.ActorContext
import com.memo.common.tenant.TenantContext
import com.memo.files.RenditionKind
import com.memo.files.internal.RenditionRetry
import com.memo.files.internal.RenditionScheduler
import com.memo.files.internal.RenditionWorker
import com.memo.memo.ImportedMemoTest
import com.memo.memo.LegacyApproval
import com.memo.memo.LegacyDates
import com.memo.memo.LegacyFileRole
import com.memo.memo.LegacyOriginal
import com.memo.memo.LegacyPerson
import com.memo.search.internal.ReaderScope
import com.memo.search.internal.SearchCriteria
import com.memo.search.internal.SearchFinder
import com.memo.search.internal.SearchIndexing
import com.memo.testsupport.Audit
import com.memo.testsupport.Person
import com.memo.testsupport.TestDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.JsonNode
import java.sql.DriverManager
import java.time.LocalDate
import java.util.UUID

/**
 * Imported memos in search (ADR-0054, ADR-0055; test plan E06 T4 `ImportedSearchIT`): found by their legacy number, by
 * the names of people who left, and by their originals' text at the originals' effective level; the Origin filter both
 * ways; the date filter by the raised-on date; the backfill for memos imported before; and originals whose text was ready
 * before their memo existed. Rendition and chunk tasks are run here by hand (test contexts share the scheduler table).
 */
class ImportedSearchIT : ImportedMemoTest() {
    @Autowired lateinit var access: AccessAdministration

    @Autowired lateinit var finder: SearchFinder

    @Autowired lateinit var indexing: SearchIndexing

    @Autowired internal lateinit var worker: RenditionWorker

    @Autowired internal lateinit var scheduling: RenditionScheduler

    @BeforeEach
    fun noJobs() {
        scheduling.jobs = false
        indexing.jobs = false
    }

    @AfterEach
    fun jobsAgain() {
        scheduling.jobs = true
        indexing.jobs = true
    }

    /** A reader of the import's unit (`memo.read.unit`) cleared to [clearance]. */
    private fun unitReader(clearance: Int = 2): Person =
        person(clearance).also { asUser(admin) { access.grantCustom(it.id, "Credit reader", setOf(Permission.MEMO_READ_UNIT), unit) } }

    @Test
    fun `an imported memo is found by its legacy number, a departed person's name and its original's text, under the read rule`() {
        val row =
            row(
                number = "CR/2019/0042",
                rank = 1,
                initiator = LegacyPerson("Rahim Uddin"),
                approvals = listOf(LegacyApproval(1, LegacyPerson("Karim Ahmed"), "Approved", "Head of Credit")),
                originalName = "wombat minutes.pdf",
            )
        val id = import(row)
        text(row.originals.single().fileId!!)
        val reader = unitReader(clearance = 1)

        // The legacy initiator and approver are linked to nobody here: their names still find the memo.
        listOf("Rahim", "Karim Ahmed").forEach { name -> assertThat(ids(search(reader, """{"q":"$name"}"""))).containsExactly(id) }
        // The legacy number, in any spelling or by its last digits, first and "found in the number".
        listOf("CR/2019/0042", "cr-2019-0042", "0042").forEach { number ->
            val first = search(reader, """{"q":"$number"}""")["results"][0]
            assertThat(first["memoId"].asString()).describedAs(number).isEqualTo(id.toString())
            assertThat(first["foundIn"]["kind"].asString()).isEqualTo("NUMBER")
            assertThat(first["origin"].asString()).isEqualTo("IMPORTED")
        }
        // The original's text, found in the main original as in a document.
        val byText = search(reader, """{"q":"wombat"}""")["results"]
        assertThat(byText.size()).isEqualTo(1)
        assertThat(byText[0]["foundIn"]["kind"].asString()).isEqualTo("DOCUMENT")

        // Nobody else: a stranger, and a unit reader below the memo's level.
        assertThat(ids(search(person(clearance = 2), """{"q":"Rahim"}"""))).isEmpty()
        assertThat(ids(search(unitReader(clearance = 0), """{"q":"Rahim"}"""))).isEmpty()
    }

    @Test
    fun `an original above the memo's level is searched only by readers cleared to it`() {
        val row = row(originalName = "aardvark ledger.pdf", stored = false)
        val stricter =
            LegacyOriginal(
                LegacyFileRole.MAIN,
                "Ledger",
                "memos/ledger.pdf",
                classificationId = levels()[2].id,
                fileId = original(row.memoId, name = "aardvark ledger.pdf"),
            )
        val id = import(row.copy(originals = listOf(stricter)))
        text(stricter.fileId!!)

        assertThat(scoped(unitReader(clearance = 2), "aardvark").map { it.memoId }).containsExactly(id)
        val below = unitReader(clearance = 1)
        assertThat(scoped(below, "aardvark")).isEmpty()
        // The memo itself is within their clearance: its details still find it.
        assertThat(scoped(below, "Rahim").map { it.memoId }).containsExactly(id)
    }

    @Test
    fun `the Origin filter returns imported or native memos, and the date filter uses the raised-on date`() {
        val reader = unitReader()
        val imported = import(row(dates = LegacyDates(LocalDate.of(2019, 3, 14), LocalDate.of(2019, 3, 20))))
        val level = levels()[0].id
        val native =
            create(reader.session, """{"subject":"Capital plan","typeId":"${generalType(reader.session)}","classificationId":"$level"}""")
                .json()["id"]
                .asString()
                .let(UUID::fromString)

        assertThat(ids(search(reader, """{"q":"capital"}"""))).containsExactlyInAnyOrder(imported, native)
        assertThat(ids(search(reader, """{"q":"capital","origin":["IMPORTED"]}"""))).containsExactly(imported)
        assertThat(ids(search(reader, """{"q":"capital","origin":["NATIVE"]}"""))).containsExactly(native)
        assertThat(ids(search(reader, """{"q":"capital","origin":["NATIVE","IMPORTED"]}"""))).containsExactlyInAnyOrder(imported, native)
        val results = search(reader, """{"q":"capital"}""")["results"]
        assertThat((0 until results.size()).associate { results[it]["memoId"].asString() to results[it]["origin"].asString() })
            .containsEntry(imported.toString(), "IMPORTED")
            .containsEntry(native.toString(), "NATIVE")

        // Raised on 14 March 2019 (in the batch's zone), imported today.
        assertThat(ids(search(reader, """{"q":"capital","from":"2019-03-01","to":"2019-03-31"}"""))).containsExactly(imported)
        val today = LocalDate.now()
        assertThat(ids(search(reader, """{"q":"capital","from":"${today.minusDays(1)}"}"""))).containsExactly(native)
        // The filter in the audit entry.
        val audited = Audit.entries(tenant.id).last { it.action == "search.performed" }
        assertThat(audited.canonical).doesNotContain("IMPORTED")
        search(reader, """{"q":"capital","origin":["IMPORTED"]}""")
        assertThat(Audit.entries(tenant.id).last { it.action == "search.performed" }.canonical).contains("IMPORTED")
    }

    @Test
    fun `the backfill indexes memos imported before search knew them`() {
        val reader = unitReader()
        val id = import(row(initiator = LegacyPerson("Selina Haque")))
        owner("delete from search_memo where memo_id = '$id'")
        assertThat(ids(search(reader, """{"q":"Selina"}"""))).isEmpty()

        assertThat(asUser(admin) { indexing.backfill() }).isEqualTo(1)
        assertThat(ids(search(reader, """{"q":"Selina"}"""))).containsExactly(id)
        assertThat(owner("select count(*) from search_memo where memo_id = '$id' and origin = 'IMPORTED' and initiator_id is null"))
            .isEqualTo(1L)
        assertThat(asUser(admin) { indexing.backfill() }).isZero()
    }

    @Test
    fun `an original whose text was ready before its memo existed is chunked when the memo is imported`() {
        val row = row(originalName = "quetzal report.pdf")
        val fileId = row.originals.single().fileId!!
        // The import job promotes originals before it creates their memo: here their text is extracted in between, when
        // the file is no memo's yet, so the rendition schedules nothing.
        text(fileId, chunk = false)
        val version = requireNotNull(transactions.readInTenant(tenant.id) { files.info(fileId) }?.currentVersionId)
        val instance = "$fileId:$version"
        assertThat(owner("select count(*) from system_scheduled_task where task_name = 'search-chunks' and task_instance = '$instance'"))
            .isZero()

        indexing.jobs = true
        val id = import(row)
        indexing.jobs = false
        // Creating the memo scheduled its chunk job. The scheduler of any test context may pick it up (they share the
        // table), so the test takes it back and runs it here.
        val taken =
            owner(
                "with d as (delete from system_scheduled_task where task_name = 'search-chunks' and task_instance = '$instance' " +
                    "returning 1) select count(*) from d",
            )
        assertThat(taken + owner("select count(*) from search_chunk where file_id = '$fileId'")).isPositive()
        ActorContext.asSystem("search-chunks") { TenantContext.call(tenant.id) { indexing.chunk(fileId, version) } }
        assertThat(scoped(unitReader(), "quetzal").map { it.memoId }).containsExactly(id)
    }

    private fun search(
        who: Person,
        body: String,
    ): JsonNode {
        val result = post("/api/v1/search", who.session, body)
        assertThat(result).hasStatusOk()
        return result.json()
    }

    private fun ids(page: JsonNode): List<UUID> {
        val results = page["results"]
        return (0 until results.size()).map { UUID.fromString(results[it]["memoId"].asString()) }
    }

    /** Through the finder, as [who] with their clearance and the unit scope. */
    private fun scoped(
        who: Person,
        text: String,
    ) = asUser(who.account) {
        finder.find(SearchCriteria(text = text), ReaderScope(who.id, clearances.rankOf(listOf(who.id))[who.id] ?: 0, false, setOf(unit)))
    }

    /** Builds the TEXT rendition of [fileId]'s current version and, unless [chunk] is false, runs its chunk job. */
    private fun text(
        fileId: UUID,
        chunk: Boolean = true,
    ) {
        val version = requireNotNull(transactions.readInTenant(tenant.id) { files.info(fileId) }?.currentVersionId)
        ActorContext.asSystem("files-rendition") {
            TenantContext.call(tenant.id) {
                repeat(RenditionWorker.MAX_ATTEMPTS) {
                    try {
                        worker.run(version, RenditionKind.TEXT)
                        return@call
                    } catch (_: RenditionRetry) {
                        // As the scheduler would: again.
                    }
                }
            }
        }
        if (chunk) ActorContext.asSystem("search-chunks") { TenantContext.call(tenant.id) { indexing.chunk(fileId, version) } }
    }

    /** A statement as the schema owner (the search tables grant nothing to the application role); a count, or 0. */
    private fun owner(sql: String): Long =
        DriverManager.getConnection(TestDatabase.jdbcUrl, "memo_migrator", TestDatabase.migratorPassword).use { c ->
            c.autoCommit = false
            c.createStatement().use { it.execute("set local lock_timeout = '30s'") }
            c.prepareStatement("select set_config('app.tenant_id', ?, true)").use {
                it.setString(1, tenant.id.value.toString())
                it.execute()
            }
            c.createStatement().use { st -> count(st, sql) }.also { c.commit() }
        }

    private fun count(
        st: java.sql.Statement,
        sql: String,
    ): Long = if (st.execute(sql)) st.resultSet.use { rs -> if (rs.next()) rs.getLong(1) else 0L } else 0L
}
