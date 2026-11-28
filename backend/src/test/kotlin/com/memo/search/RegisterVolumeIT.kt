package com.memo.search

import com.memo.analytics.CommitteeVolumeSupport
import com.memo.search.RegisterPlans.Profile
import com.memo.search.RegisterPlans.lines
import com.memo.search.internal.SearchReferences
import com.memo.search.internal.decision.DecisionExportRequest
import com.memo.search.internal.decision.DecisionExports
import com.memo.search.internal.decision.DecisionIndex
import com.memo.search.internal.decision.DecisionRegister
import com.memo.search.internal.decision.DecisionRegisterAccess
import com.memo.search.internal.decision.DecisionSearchRequest
import com.memo.search.internal.decision.RegisterExportFormat
import com.memo.search.internal.decision.RegisterPdf
import com.memo.search.internal.decision.RegisterWorkbook
import com.memo.testsupport.Latency
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import java.io.OutputStream
import java.time.LocalDate
import java.util.UUID

/**
 * The decision register at the design's volume (test plan E08 T5 `RegisterVolumeIT`, design E08 §8): the committee volume
 * ([CommitteeVolumeSupport]) at `COMMITTEE_VOLUME` meetings, 3,600 for about 54,000 decisions and 7,200 for about 108,000
 * (the weekly `load` stage runs both; the test is skipped without it), its register filled by the backfill. Measured
 * through the services the endpoints call (`POST /decision-register/search` and `/export`), for the five reader profiles
 * of design §8: newest first, one committee, number order, rare and common words, a resolution number; the indexing a
 * decision adds to its recording and an agenda revision's refresh; the exports at their row limits. Every statement is
 * `EXPLAIN ANALYZE`d (no sequential scan of a large table), and the statement the definer function plans per call is
 * explained with each profile's real parameters (no sequential scan of `search_decision`). Budgets have 2x headroom for
 * shared CI runners; the p95 values are printed for the epic log.
 */
@EnabledIfEnvironmentVariable(named = CommitteeVolumeSupport.VOLUME_VARIABLE, matches = "[1-9][0-9]*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class RegisterVolumeIT : CommitteeVolumeSupport() {
    @Autowired lateinit var register: DecisionRegister

    @Autowired lateinit var access: DecisionRegisterAccess

    @Autowired lateinit var exports: DecisionExports

    @Autowired lateinit var workbook: RegisterWorkbook

    @Autowired lateinit var pdf: RegisterPdf

    @Autowired lateinit var index: DecisionIndex

    /** Each profile's first person. */
    private lateinit var readers: Map<String, UUID>

    @BeforeAll
    fun generate() {
        generateCommittees("registervol")
        readers = profiles.byName().mapValues { it.value.first() }
    }

    @Test
    @Order(1)
    fun `the backfill indexes every decision within budget`() {
        assertThat(decisions).isGreaterThan(meetings.toLong() * MIN_DECISIONS_PER_MEETING)
        // Design §8 sets ten minutes for 54,000 decisions: a larger volume's budget grows with it.
        val budget = BACKFILL_BUDGET_MS * maxOf(decisions, DESIGN_DECISIONS) / DESIGN_DECISIONS
        budget("backfill ($decisions decisions)", backfillMillis, budget)
    }

    @Test
    @Order(2)
    fun `every profile's searches answer within budget`() {
        readers.forEach { (name, user) ->
            val first = firstCommittee(user)
            val number = numberIn(first)
            val newest = asUser(user, write = true) { register.search(DecisionSearchRequest()) }
            assertThat(newest.rows).describedAs(name).hasSize(PAGE)
            println("$label $name: ${newest.total} decisions (capped ${newest.capped}), facets ${newest.facets.forums.size} committees")
            search(name, "newest first", NEWEST_BUDGET_MS, user, DecisionSearchRequest())
            search(name, "one committee", NEWEST_BUDGET_MS, user, DecisionSearchRequest(forumIds = listOf(first)))
            val byNumber = DecisionSearchRequest(forumIds = listOf(first), sort = DecisionSort.NUMBER)
            search(name, "number order", NEWEST_BUDGET_MS, user, byNumber)
            search(name, "rare words", TEXT_BUDGET_MS, user, DecisionSearchRequest(text = RARE))
            search(name, "common words", TEXT_BUDGET_MS, user, DecisionSearchRequest(text = COMMON))
            val found = asUser(user, write = true) { register.search(DecisionSearchRequest(text = number)) }
            assertThat(found.rows.map { it.resolutionNumber }).describedAs("$name: $number").contains(number)
            search(name, "resolution number", NUMBER_BUDGET_MS, user, DecisionSearchRequest(text = number))
        }
    }

    @Test
    @Order(3)
    fun `the register's statements use the indexes for every profile's own parameters`() {
        val params =
            readers.mapValues { (_, user) ->
                val viewer = checkNotNull(asUser(user) { access.parameters() })
                Profile(
                    all = viewer.scope.manager,
                    officers = viewer.scope.officerForums.toList(),
                    members = viewer.scope.memberForums.toList(),
                    reader = viewer.scope.reader,
                    recused = viewer.recused.toList(),
                    clearance = viewer.clearanceRank,
                    viewer = user,
                )
            }
        assertThat(params.getValue(RECUSED).recused).isNotEmpty
        val first = { p: Profile -> p.inScope.firstOrNull() ?: committees.first().first }
        val sample = numberIn(committees.first().first)
        val norm = SearchReferences.keys(sample)?.first ?: sample
        // The words of the plan gate are in some rows: "covenants" in one decision with conditions in five (under 4 % of
        // the decisions), deferrals in one submission in ten. A word in a fifth of the submissions (the conditions' words
        // once were) may be planned for a reader of every committee as a read of the table in order, as the CI runner chose
        // (load run 46156574343); a word in nearly every row ("proposal", in the latency checks) is planned so, cheaper
        // than the index there, and bounded (E08 T5 log).
        val shapes = RegisterPlans.shapes(RARE, PLANNED_COMMON, norm, "14", "CP-2020-00001", first)
        owner { c ->
            val scans = RegisterPlans.searchScans(c, tenant.id.value, params, shapes, AUDITOR, label)
            assertThat(scans).describedAs("sequential scans of search_decision").isEmpty()
            val stats =
                mapOf<String, (Profile) -> Pair<List<UUID>?, Long>>(
                    "summary" to { _ -> null to 1L },
                    "detail" to { p -> listOf(first(p)) to DETAIL_YEARS },
                )
            assertThat(RegisterPlans.statsScans(c, tenant.id.value, params, stats, AUDITOR, label))
                .describedAs("sequential scans of search_decision")
                .isEmpty()
        }
    }

    @Test
    @Order(4)
    fun `indexing a decision and refreshing an agenda's items add within budget`() {
        val decided = owner { c -> c.lines("select decision_id from search_decision where meeting_id = '${volume.held}' limit 1").single() }
        measure("indexing a recorded decision (DecisionRecorded)", INDEX_BUDGET_MS, people.secretary, write = true) {
            index.put(UUID.fromString(decided))
        }
        val items = owner { c -> c.lines("select count(*) from search_decision where meeting_id = '${volume.held}'").single() }
        measure("an agenda revision's refresh ($items decided items, AgendaPublished)", AGENDA_BUDGET_MS, people.secretary, write = true) {
            index.putItems(volume.held)
        }
    }

    @Test
    @Order(5)
    fun `the exports answer within budget at their row limits`() {
        val manager = readers.getValue(MANAGER)
        listOf(RegisterExportFormat.XLSX to XLSX_BUDGET_MS, RegisterExportFormat.PDF to PDF_BUDGET_MS).forEach { (format, budget) ->
            val from = fromFor(format.maximum)
            val request = DecisionExportRequest(format = format, from = from)
            val rows = asUser(manager, write = true) { exports.export(request) }.rows.size
            assertThat(rows).isBetween(format.maximum * 3 / 4, format.maximum)
            val write = { out: OutputStream ->
                val export = exports.export(request)
                if (format == RegisterExportFormat.XLSX) workbook.write(export, out) else pdf.write(export, out)
            }
            val p95 = Latency.p95(1, EXPORT_RUNS, 1) { asUser(manager, write = true) { write(OutputStream.nullOutputStream()) } }
            budget("export ${format.name} ($rows rows since $from, with the file)", p95, budget)
        }
    }

    /** A committee the [user] reads: their first officer or member committee, else the first. */
    private fun firstCommittee(user: UUID): UUID =
        checkNotNull(asUser(user) { access.parameters() }).scope.let { (it.officerForums + it.memberForums).firstOrNull() }
            ?: committees.first().first

    /** The latest resolution number of [forumId]. */
    private fun numberIn(forumId: UUID): String =
        owner { c ->
            c
                .lines(
                    "select resolution_number from search_decision where forum_id = '$forumId' and resolution_number is not null " +
                        "order by decided_at desc limit 1",
                ).single()
        }

    /** The meeting date from which the register holds at most [rows] decisions. */
    private fun fromFor(rows: Int): LocalDate =
        owner { c ->
            LocalDate.parse(c.lines("select meeting_date from search_decision order by meeting_date desc offset $rows limit 1").single())
        }.plusDays(1)

    private fun search(
        profile: String,
        shape: String,
        budget: Long,
        user: UUID,
        request: DecisionSearchRequest,
    ) = measure("$profile, $shape", budget, user, write = true) { register.search(request) }

    private val RegisterExportFormat.maximum: Int
        get() = if (this == RegisterExportFormat.XLSX) XLSX_ROWS else PDF_ROWS

    private companion object {
        const val PAGE = 50
        const val DESIGN_DECISIONS = 54_000L
        const val MIN_DECISIONS_PER_MEETING = 13
        const val RARE = "covenants"
        const val COMMON = "proposal"
        const val PLANNED_COMMON = "deferred next meeting"
        const val DETAIL_YEARS = 5L
        const val EXPORT_RUNS = 3
        const val XLSX_ROWS = 20_000
        const val PDF_ROWS = 2_000

        // Design E08 §8.
        const val NEWEST_BUDGET_MS = 150L
        const val TEXT_BUDGET_MS = 300L
        const val NUMBER_BUDGET_MS = 50L
        const val INDEX_BUDGET_MS = 20L
        const val AGENDA_BUDGET_MS = 50L
        const val XLSX_BUDGET_MS = 10_000L
        const val PDF_BUDGET_MS = 15_000L
    }
}
