package com.memo.search

import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantTransactions
import com.memo.search.internal.ReaderScope
import com.memo.search.internal.SearchCriteria
import com.memo.search.internal.SearchFinder
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.TestDatabase
import com.memo.testsupport.TestTenant
import com.memo.testsupport.aTestTenant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * The E05 T1 search spike (ADR-0054, test plan T1): [SearchVolume] at `SEARCH_VOLUME` memos (100,000 by default; the V1
 * run sets 1,000,000) and the four reader profiles of the test plan. Records p95 per profile over a query mix, checks that
 * the search query plans use the indexes for rare words for every profile, and measures the candidate cap (GIN
 * `fastupdate` was measured once in the T1 spike, E05 log). Budgets have 2x headroom for shared runners; the measured values
 * are printed for the log.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SearchPerformanceIT : IntegrationTest() {
    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var tenantDsl: TenantDsl

    @Autowired lateinit var finder: SearchFinder

    private val memos = System.getenv("SEARCH_VOLUME")?.toIntOrNull() ?: DEFAULT_MEMOS
    private lateinit var tenant: TestTenant
    private lateinit var volume: SearchVolume
    private lateinit var profiles: Map<String, ReaderScope>

    @BeforeAll
    fun generate() {
        tenant = registry.aTestTenant(transactions, settings, "searchperf")
        val (type, level) =
            transactions.inTenant(tenant.id) {
                val dsl = tenantDsl.current()
                (dsl.fetchValue("select id from memo_type where key = 'general'") as UUID) to
                    (dsl.fetchValue("select id from authz_classification_level where rank = 0") as UUID)
            }
        volume = SearchVolume(tenant.id.value, memos, type, level)
        val narrow = SearchVolume.uuid("reader-narrow")
        val head = SearchVolume.uuid("reader-head")
        val auditor = SearchVolume.uuid("reader-auditor")
        val low = SearchVolume.uuid("reader-low")
        val heavy = SearchVolume.uuid("reader-heavy")
        profiles =
            mapOf(
                // A person who reads memos only as a participant: 300 copies over the volume.
                "narrow participant" to ReaderScope(narrow, 2, false, emptySet()),
                // A unit head with memo.read.unit on 5 of the 20 units (a quarter of the memos) and 2,000 memos acted on.
                "unit head" to ReaderScope(head, 2, false, (0 until HEAD_UNITS).map(volume::unit).toSet()),
                // An auditor reading the whole tenant.
                "auditor" to ReaderScope(auditor, 2, true, emptySet()),
                // Tenant-wide but at the lowest clearance: ranked memos and documents drop out.
                "low clearance" to ReaderScope(low, 0, true, emptySet()),
                // A participant of 20,000 memos (an approver of many years), above the lookup limit.
                "heavy participant" to ReaderScope(heavy, 2, false, emptySet()),
            )
        val started = System.nanoTime()
        volume.generate(mapOf(narrow to NARROW_ROWS, head to HEAD_ROWS, heavy to HEAVY_ROWS))
        println("SearchPerformanceIT generated $memos memos in ${(System.nanoTime() - started) / NANOS_PER_MILLI} ms")
    }

    @Test
    fun `needles are found and nothing outside the reader's rule comes back`() {
        val auditor = profiles.getValue("auditor")
        val low = profiles.getValue("low clearance")
        assertThat(search(SearchVolume.NEEDLE_SUBJECT, auditor)).hasSize(SearchVolume.NEEDLES)
        assertThat(search(SearchVolume.NEEDLE_NOTE, auditor)).isNotEmpty.allMatch { it.inNotes }
        assertThat(search(SearchVolume.NEEDLE_DOCUMENT, auditor)).isNotEmpty.allMatch { it.inDocuments }
        // Every twentieth memo is ranked 2: none of them for the low clearance reader.
        assertThat(search(SearchVolume.NEEDLE_SUBJECT, low)).hasSizeLessThan(SearchVolume.NEEDLES)
        val head = profiles.getValue("unit head")
        val headUnits = head.units
        val headIds = participantMemos(head.reader)
        assertThat(search("loan", head)).allMatch { it.originUnitId in headUnits || it.memoId in headIds }
        val narrow = profiles.getValue("narrow participant")
        val narrowIds = participantMemos(narrow.reader)
        assertThat(search("loan", narrow)).isNotEmpty.allMatch { it.memoId in narrowIds }
        // A number finds its memo first, in full or by its last digits.
        val number = memos / 2
        assertThat(search("M-2026-${number.toString().padStart(7, '0')}", auditor).first().memoId).isEqualTo(volume.memo(number))
        assertThat(search("00$number", auditor).first().memoId).isEqualTo(volume.memo(number))
    }

    @Test
    fun `search answers within budget for every reader profile`() {
        val results =
            profiles.mapValues { (_, scope) ->
                p95 { QUERIES.forEach { (text, prefix) -> search(text, scope, prefix) } } / QUERIES.size
            }
        results.forEach { (profile, ms) -> println("SearchPerformanceIT $memos memos, $profile: mean of query mix p95 $ms ms") }
        profiles.forEach { (profile, scope) ->
            QUERIES.forEach { (text, prefix) ->
                val p95 = p95 { search(text, scope, prefix) }
                println("SearchPerformanceIT $memos memos, $profile, \"$text\"${prefix?.let { " prefix $it" } ?: ""}: p95 $p95 ms")
                assertThat(p95).describedAs("$profile: $text").isLessThan(BUDGET_MS * 2)
            }
        }
    }

    @Test
    fun `the candidate cap bounds the cost of a common word`() {
        val auditor = profiles.getValue("auditor")
        listOf(CAP_SMALL, SearchFinder.DEFAULT_CAP, CAP_LARGE).forEach { cap ->
            val p95 = p95 { transactions.readInTenant(tenant.id) { finder.find(SearchCriteria(text = "loan"), auditor, cap) } }
            println("SearchPerformanceIT $memos memos, auditor, \"loan\", cap $cap: p95 $p95 ms")
        }
        assertThat(search("loan", auditor)).hasSize(SearchFinder.DEFAULT_CAP)
    }

    @Test
    fun `the search query uses the indexes for rare words for every reader profile`() {
        owner { c ->
            c.exec("set plan_cache_mode = force_custom_plan")
            c.exec("prepare find(${FIND_TYPES.joinToString()}) as ${c.lines("select search_find_sql(true)").single()}")
            c.exec("prepare find_many(${FIND_TYPES.joinToString()}) as ${c.lines("select search_find_sql(false)").single()}")
            profiles.forEach { (profile, scope) ->
                listOf(SearchVolume.NEEDLE_SUBJECT, SearchVolume.NEEDLE_NOTE, SearchVolume.NEEDLE_DOCUMENT).forEach { word ->
                    val statement = if (profile == "heavy participant") "find_many" else "find"
                    val plan = c.lines("explain execute $statement(${arguments(scope, word)})")
                    val scans = plan.filter { line -> SEARCH_TABLES.any { line.contains("Seq Scan on $it ") } }
                    assertThat(scans).describedAs("$profile, $word:\n${plan.joinToString("\n")}").isEmpty()
                    if (scope.allUnits || scope.units.isNotEmpty()) {
                        assertThat(plan).describedAs("$profile, $word").anyMatch { it.contains("search_memo_tsv_idx") }
                    }
                }
                val statement = if (profile == "heavy participant") "find_many" else "find"
                // The Origin filter (E06 T4) narrows the rows an index finds; it is never a reason to scan.
                val filtered = c.lines("explain execute $statement(${arguments(scope, SearchVolume.NEEDLE_SUBJECT, "array['IMPORTED']")})")
                assertThat(filtered.filter { line -> SEARCH_TABLES.any { line.contains("Seq Scan on $it ") } })
                    .describedAs("$profile, origin filter:\n${filtered.joinToString("\n")}")
                    .isEmpty()
                // A word in most memos may be read sequentially (the planner's choice), but never past the cap.
                listOf("loan", "\"loan renewal\"").forEach { word ->
                    val analysed = c.lines("explain (analyze) execute $statement(${arguments(scope, word)})")
                    println("SearchPerformanceIT plan, $profile, $word:\n" + analysed.joinToString("\n"))
                }
            }
        }
    }

    @Test
    fun `the application role cannot read the search tables, and the functions refuse another tenant`() {
        transactions.readInTenant(tenant.id) {
            assertThatThrownBy { tenantDsl.current().fetch("select * from search_memo limit 1") }
                .rootCause()
                .hasMessageContaining("permission denied")
        }
        val other = registry.aTestTenant(transactions, settings, "searchother")
        assertThatThrownBy {
            TenantContext.call(other.id) {
                transactions.readInTenant(other.id) {
                    tenantDsl.current().fetch(
                        "select * from search_find(?::uuid, ?::uuid, 2, null, true, now(), 'loan', null, null, null, " +
                            "null, null, null, null, null, null, null, true, true, true, 10)",
                        tenant.id.value,
                        UUID.randomUUID(),
                    )
                }
            }
        }.rootCause().hasMessageContaining("tenant mismatch")
    }

    private fun search(
        text: String,
        scope: ReaderScope,
        prefix: String? = null,
    ) = transactions.readInTenant(tenant.id) { finder.find(SearchCriteria(text = text, prefix = prefix), scope) }

    private fun participantMemos(reader: UUID): Set<UUID> =
        transactions.readInTenant(tenant.id) {
            tenantDsl
                .current()
                .fetch("select memo_id from memo_participant where user_id = ?::uuid", reader)
                .map { it.get(0, UUID::class.java) }
                .toSet()
        }

    /**
     * The values of `search_find_sql()`'s parameters \$1 to \$22 for [scope] searching [word] in [origins] (SQL for a text
     * array, or null for both), as search_find passes them.
     */
    private fun arguments(
        scope: ReaderScope,
        word: String,
        origins: String = "null",
    ): String =
        listOf(
            "'${tenant.id.value}'",
            "'${scope.reader}'",
            "${scope.clearanceRank}",
            "array[${scope.units.joinToString { "'$it'" }}]::uuid[]",
            "${scope.allUnits}",
            "${scope.allUnits || scope.units.isNotEmpty()}",
            "now()",
            "websearch_to_tsquery('english', '$word')",
        ).plus(List(FILTERS) { "null" })
            .plus(listOf("true", "true", "true", "${SearchFinder.DEFAULT_CAP}", "$origins::text[]"))
            .joinToString()

    private fun p95(call: () -> Unit): Long {
        repeat(WARM_UP) { call() }
        val times =
            (1..RUNS)
                .map {
                    val start = System.nanoTime()
                    call()
                    (System.nanoTime() - start) / NANOS_PER_MILLI
                }.sorted()
        return times[(RUNS * 95 / 100) - 1]
    }

    /** A transaction as the schema owner, bound to the tenant (transaction-local, as the application binds it). */
    private fun owner(block: (Connection) -> Unit) =
        DriverManager.getConnection(TestDatabase.jdbcUrl, "memo_migrator", TestDatabase.migratorPassword).use { c ->
            c.autoCommit = false
            // A lock wait fails the test (with this statement) instead of stalling the whole suite.
            c.createStatement().use { it.execute("set local lock_timeout = '30s'") }
            c.prepareStatement("select set_config('app.tenant_id', ?, true)").use {
                it.setString(1, tenant.id.value.toString())
                it.execute()
            }
            block(c)
            c.commit()
        }

    private fun Connection.exec(sql: String) {
        createStatement().use { it.execute(sql) }
    }

    private fun Connection.lines(sql: String): List<String> =
        createStatement().use { st -> st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } }

    companion object {
        const val DEFAULT_MEMOS = 100_000
        const val HEAD_UNITS = 5
        const val NARROW_ROWS = 300
        const val HEAD_ROWS = 2_000
        const val HEAVY_ROWS = 20_000
        const val BUDGET_MS = 300L
        const val CAP_SMALL = 1_000
        const val CAP_LARGE = 5_000
        const val WARM_UP = 3
        const val RUNS = 20
        const val NANOS_PER_MILLI = 1_000_000L
        val SEARCH_TABLES = listOf("search_memo", "search_note", "search_chunk")

        /** The query mix: a common word, a mid word, a rare word, two words, a phrase, a prefix, a number, its digits. */
        val QUERIES =
            listOf(
                "loan" to null,
                "collateral" to null,
                "sukuk" to null,
                "rent schedule" to null,
                "\"loan renewal\"" to null,
                "branch" to "guaran",
                "M-2026-0004242" to null,
                "4242" to null,
            )

        /** The number keys and filters (\$9 to \$17): none in the plan gate. */
        const val FILTERS = 9

        /** The types of `search_find_sql()`'s parameters \$1 to \$22 (the origins, E06 T4). */
        val FIND_TYPES =
            listOf(
                "uuid",
                "uuid",
                "int",
                "uuid[]",
                "boolean",
                "boolean",
                "timestamptz",
                "tsquery",
                "text",
                "text",
                "text[]",
                "uuid[]",
                "uuid[]",
                "timestamptz",
                "timestamptz",
                "numeric",
                "numeric",
                "boolean",
                "boolean",
                "boolean",
                "int",
                "text[]",
            )
    }
}
