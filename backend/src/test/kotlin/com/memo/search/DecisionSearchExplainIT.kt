package com.memo.search

import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantTransactions
import com.memo.search.RegisterPlans.Profile
import com.memo.search.RegisterPlans.Shape
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
 * The register's privileged path (ADR-0069, ADR-0054; test plan E08 T1 performance gate, persistence/RLS): at
 * `DECISION_VOLUME` decisions (54,000 by default: 30 committees × 10 years × 12 meetings × 15 items), the plans of
 * `search_find_decisions`' query, as the owner the definer function runs as, use the indexes for the five reader profiles
 * (a secretary of three committees, a director member of two, an auditor with `forum.read`, a recused director, a
 * `forums.manage` holder) in newest-first, oldest-first, one committee, number order, text, number lookup and memo-number
 * lookup, with the facets and totals in the same statement; a sequential scan of `search_decision` fails the test.
 * Every function refuses another tenant's argument, a tenant's rows are never returned or counted for another, and the
 * application role holds no privilege on the table and EXECUTE on the entry points only.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DecisionSearchExplainIT : IntegrationTest() {
    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var tenantDsl: TenantDsl

    private val committees = (System.getenv("DECISION_VOLUME")?.toIntOrNull() ?: DEFAULT_DECISIONS) / PER_COMMITTEE
    private lateinit var tenant: TestTenant
    private lateinit var other: TestTenant
    private lateinit var forums: List<UUID>
    private lateinit var recusedItems: List<UUID>
    private val type = UUID.randomUUID()
    private val viewer = UUID.randomUUID()

    private lateinit var profiles: Map<String, Profile>

    @BeforeAll
    fun generate() {
        tenant = registry.aTestTenant(transactions, settings, "registerplan")
        other = registry.aTestTenant(transactions, settings, "registerother")
        forums = List(committees) { UUID.randomUUID() }
        val started = System.nanoTime()
        val items =
            forums.mapIndexed { i, forum ->
                DecisionVolume.committee(tenant.id.value, forum, type, "C$i", MEETINGS, viewer, needles = if (i < 3) 1 else 0)
            }
        DecisionVolume.committee(other.id.value, UUID.randomUUID(), type, "OTH", 2, viewer, needles = 5)
        DecisionVolume.analyse()
        val millis = (System.nanoTime() - started) / 1_000_000
        println("DecisionSearchExplainIT generated ${committees * PER_COMMITTEE} decisions in $millis ms")
        recusedItems = items[0].take(RECUSED) + items[1].take(RECUSED)
        profiles =
            mapOf(
                "secretary of three" to Profile(false, forums.take(3), emptyList(), false, viewer = viewer),
                "director of two" to Profile(false, emptyList(), forums.take(2), false, viewer = viewer),
                "auditor" to Profile(false, emptyList(), emptyList(), true, viewer = viewer),
                "recused director" to Profile(false, emptyList(), forums.take(2), false, recusedItems, viewer = viewer),
                "forums.manage" to Profile(true, emptyList(), emptyList(), false, viewer = viewer),
            )
    }

    private val shapes: List<Shape>
        get() = RegisterPlans.shapes(DecisionVolume.NEEDLE, "treasury limits", "C0-2017-0001", "14", "CP-2018-00042", ::firstOf)

    private fun firstOf(p: Profile): UUID = (p.officers + p.members).firstOrNull() ?: forums.first()

    @Test
    fun `the register's query uses the indexes for every reader profile and shape`() {
        owner { c ->
            val scans = RegisterPlans.searchScans(c, tenant.id.value, profiles, shapes, "auditor", "DecisionSearchExplainIT")
            assertThat(scans).describedAs("sequential scans of search_decision").isEmpty()
        }
    }

    @Test
    fun `the decision statistics use the indexes for every reader profile, over the summary's and the detail's periods`() {
        owner { c ->
            // The summary (every committee in scope, the last 12 months) and one committee's detail over five years.
            val shapes =
                mapOf<String, (Profile) -> Pair<List<UUID>?, Long>>(
                    "summary" to { _ -> null to 1L },
                    "detail" to { p -> listOf(firstOf(p)) to 5L },
                )
            val scans = RegisterPlans.statsScans(c, tenant.id.value, profiles, shapes, "auditor", "DecisionSearchExplainIT")
            assertThat(scans).describedAs("sequential scans of search_decision").isEmpty()
        }
    }

    @Test
    fun `every function refuses another tenant, and another tenant's rows are never returned or counted`() {
        val calls =
            listOf(
                "select search_put_decisions_final(?::uuid, gen_random_uuid(), now(), now())",
                "select search_put_decision_items(?::uuid, gen_random_uuid(), null, current_date, '[]'::jsonb, now())",
                "select * from search_decision_unindexed(?::uuid, 10)",
                "select * from search_decision_stats(?::uuid, gen_random_uuid(), 2, true, null, null, false, null, null, null, null)",
                "select * from search_find_decisions(?::uuid, gen_random_uuid(), 2, true, null, null, true, null, " +
                    "null, null, null, null, null, null, null, null, null, false, null, null, null, current_date, " +
                    "'NEWEST', null, null, null, null, null, null, 10, 100)",
                "select search_put_decision(?::uuid, gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), " +
                    "null, current_date, gen_random_uuid(), null, null, 0, null, false, false, 't', 'NOTED', 'Noted', false, " +
                    "null, null, null, null, null, null, null, 'RECORDED', now(), null, 1, null, null, null, false, 'CONSENSUS', " +
                    "false, null, null, null, null, null, null, null, 0, now())",
            )
        calls.forEach { sql ->
            assertThatThrownBy {
                TenantContext.call(other.id) {
                    transactions.inTenant(other.id) { tenantDsl.current().fetch(sql, tenant.id.value) }
                }
            }.describedAs(sql).rootCause().hasMessageContaining("tenant mismatch")
        }
        // The other tenant sees its own five needles only, counted alone; this tenant's three are never among them.
        val found =
            TenantContext.call(other.id) {
                transactions.readInTenant(other.id) {
                    tenantDsl.current().fetch(
                        "select kind, decision_id, total from search_find_decisions(?::uuid, ?::uuid, 2, true, null, null, " +
                            "true, null, ?, null, null, null, null, null, null, null, null, false, null, null, null, " +
                            "current_date, 'RELEVANCE', null, null, null, null, null, null, 100, 2000)",
                        other.id.value,
                        viewer,
                        DecisionVolume.NEEDLE,
                    )
                }
            }
        assertThat(found.filter { it.get("kind") == "ROW" }).hasSize(5)
        assertThat(found.single { it.get("kind") == "META" }.get("total")).isEqualTo(5)
        val all =
            TenantContext.call(other.id) {
                transactions.readInTenant(other.id) {
                    tenantDsl.current().fetchValue(
                        "select total from search_find_decisions(?::uuid, ?::uuid, 2, true, null, null, true, null, null, " +
                            "null, null, null, null, null, null, null, null, false, null, null, null, current_date, " +
                            "'NEWEST', null, null, null, null, null, null, 1, 100) where kind = 'META'",
                        other.id.value,
                        viewer,
                    )
                }
            }
        assertThat(all).isEqualTo(MEETINGS_OTHER * DecisionVolume.ITEMS_PER_MEETING)
    }

    @Test
    fun `the application role holds no privilege on the table and executes the entry points only`() {
        transactions.readInTenant(tenant.id) {
            assertThatThrownBy { tenantDsl.current().fetch("select * from search_decision limit 1") }
                .rootCause()
                .hasMessageContaining("permission denied")
        }
        owner { c ->
            val grants =
                c.lines(
                    "select privilege_type from information_schema.role_table_grants " +
                        "where table_schema = 'memo' and table_name = 'search_decision' and grantee in ('memo_app', 'PUBLIC')",
                )
            assertThat(grants).isEmpty()
            val executable =
                c.lines(
                    "select p.proname from pg_proc p join pg_namespace n on n.oid = p.pronamespace " +
                        "where n.nspname = 'memo' and (p.proname like 'search_%decision%') " +
                        "and has_function_privilege('memo_app', p.oid, 'EXECUTE') order by 1",
                )
            assertThat(executable).containsExactly(
                "search_decision_stats",
                "search_decision_unindexed",
                "search_find_decisions",
                "search_put_decision",
                "search_put_decision_items",
                "search_put_decisions_final",
            )
        }
    }

    /** A transaction as the schema owner, bound to the tenant. */
    private fun owner(block: (Connection) -> Unit) =
        DriverManager.getConnection(TestDatabase.jdbcUrl, "memo_migrator", TestDatabase.migratorPassword).use { c ->
            c.autoCommit = false
            c.createStatement().use { it.execute("set local statement_timeout = '10min'") }
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

    private companion object {
        const val DEFAULT_DECISIONS = 54_000
        const val MEETINGS = 120
        const val MEETINGS_OTHER = 2
        const val PER_COMMITTEE = MEETINGS * DecisionVolume.ITEMS_PER_MEETING
        const val RECUSED = 50
    }
}
