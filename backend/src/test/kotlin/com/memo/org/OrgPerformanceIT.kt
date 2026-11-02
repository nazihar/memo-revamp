package com.memo.org

import com.memo.common.authz.Permission
import com.memo.common.authz.Policy
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantTransactions
import com.memo.identity.UserAccounts
import com.memo.org.internal.DirectorySearch
import com.memo.org.internal.OrgViews
import com.memo.org.internal.UnitStore
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.TestTenant
import com.memo.testsupport.aPassword
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.anActiveUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Org reads at the design's large-tenant volume (design E04 §11): 1,510 units, 2,000 positions, 5,000 users, 1,800
 * holders. Budgets are checked with 2x headroom for shared CI runners; the measured p95 values are printed for the log.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrgPerformanceIT : IntegrationTest() {
    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var tenantDsl: TenantDsl

    @Autowired lateinit var accounts: UserAccounts

    @Autowired lateinit var units: UnitStore

    @Autowired lateinit var views: OrgViews

    @Autowired lateinit var search: DirectorySearch

    @Autowired lateinit var policy: Policy

    private lateinit var tenant: TestTenant
    private lateinit var admin: UUID
    private lateinit var someUnit: UUID
    private val start = LocalDate.of(2026, 1, 1)
    private val day = start.toString()
    private val now = OffsetDateTime.now(ZoneOffset.UTC).toString()

    @BeforeAll
    fun volume() {
        tenant = registry.aTestTenant(transactions, settings, "perf")
        admin = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true).id.value
        transactions.inTenant(tenant.id) {
            val entity = UUID.randomUUID()
            tenantDsl.current().execute(
                "insert into org_legal_entity (id, tenant_id, name, valid_from, created_at, updated_at) " +
                    "values (?::uuid, ?::uuid, 'Perf Bank', ?::date, ?::timestamptz, ?::timestamptz)",
                entity,
                tenant.id.value,
                day,
                now,
                now,
            )
            val unitIds = insertUnits(entity)
            someUnit = unitIds[3]
            val users =
                insert(
                    USER_SQL,
                    (1..5_000).map { i ->
                        listOf("perf$i@bank.example", "Person ${NAMES[i % NAMES.size]} $i", "ACTIVE")
                    },
                )
            val positions =
                insert(
                    POSITION_SQL,
                    (1..2_000).map { i -> listOf(unitIds[i % unitIds.size], "${TITLES[i % TITLES.size]} $i", day) },
                )
            insert(ASSIGNMENT_SQL, positions.take(1_800).mapIndexed { i, p -> listOf(users[i], p, day) })
            tenantDsl.current().execute("analyze org_unit, org_position, org_assignment, identity_user")
        }
    }

    /** A tree of 1 + 10 + 100 + 1,500 units. */
    private fun insertUnits(entity: UUID): List<UUID> {
        val rows = mutableListOf<List<Any?>>()

        fun unit(ancestors: List<UUID>): UUID {
            val id = UUID.randomUUID()
            rows +=
                listOf(id, entity, ancestors.lastOrNull(), ancestors.joinToString(",", "{", "}"), "U${rows.size}", "Unit ${rows.size}", day)
            return id
        }
        val root = unit(emptyList())
        repeat(DIVISIONS) {
            val division = unit(listOf(root))
            repeat(DEPARTMENTS) {
                val department = unit(listOf(root, division))
                repeat(TEAMS) { unit(listOf(root, division, department)) }
            }
        }
        val batch = tenantDsl.current().batch(UNIT_SQL)
        rows.forEach { batch.bind(it[0], tenant.id.value, *it.drop(1).toTypedArray(), now, now) }
        batch.execute()
        return rows.map { it[0] as UUID }
    }

    /** Inserts one row per entry of [values] (after a fresh id and the tenant; created and updated now). */
    private fun insert(
        sql: String,
        values: List<List<Any?>>,
    ): List<UUID> {
        val batch = tenantDsl.current().batch(sql)
        val ids = values.map { UUID.randomUUID() }
        values.forEachIndexed { i, row -> batch.bind(ids[i], tenant.id.value, *row.toTypedArray(), now, now) }
        batch.execute()
        return ids
    }

    private fun <T> asAdmin(block: () -> T): T =
        ActorContext.call(ActorScope(Actor.User(admin), null)) {
            TenantContext.call(tenant.id) { transactions.readInTenant(tenant.id, block) }
        }

    /** p95 in milliseconds over [runs] after [warmup] runs. */
    private fun p95(
        warmup: Int = 3,
        runs: Int = 20,
        block: () -> Unit,
    ): Double {
        repeat(warmup) { block() }
        val samples =
            (1..runs)
                .map {
                    val started = System.nanoTime()
                    block()
                    (System.nanoTime() - started) / 1_000_000.0
                }.sorted()
        return samples[(runs * 95 / 100).coerceAtMost(runs - 1)]
    }

    @Test
    fun `org reads meet their budgets at the large-tenant volume`() {
        val tree = p95 { asAdmin { assertThat(units.units()).hasSizeGreaterThanOrEqualTo(1_510) } }
        val positionsOfUnit = p95 { asAdmin { views.positionsOf(someUnit, start.plusDays(1)) } }
        val picker = p95 { asAdmin { search.search("person 4", setOf("user", "position"), 20) } }
        val pickerTitle = p95 { asAdmin { search.search("analyst", setOf("position"), 20) } }
        val check = p95(runs = 200) { asAdmin { policy.allows(Permission.ORG_MANAGE) } }
        println("ORG PERF p95 ms: tree=$tree unitPositions=$positionsOfUnit picker=$picker pickerTitle=$pickerTitle policyCheck=$check")

        assertThat(tree).isLessThan(2 * 150.0)
        assertThat(positionsOfUnit).isLessThan(2 * 100.0)
        assertThat(picker).isLessThan(2 * 100.0)
        assertThat(pickerTitle).isLessThan(2 * 100.0)
        // A policy check reads the cached grants: no query once warm (the transaction is the cost here).
        assertThat(check).isLessThan(2 * 5.0)
    }

    @Test
    fun `the picker queries use the prefix indexes`() {
        val plan =
            transactions.readInTenant(tenant.id) {
                tenantDsl
                    .current()
                    .fetch(
                        "explain select id from identity_user where tenant_id = ? and lower(display_name) like 'person 4%' " +
                            "order by lower(display_name) limit 20",
                        tenant.id.value,
                    ).map { it.get(0).toString() }
            }
        println("PICKER PLAN:\n" + plan.joinToString("\n"))
        assertThat(plan.joinToString("\n")).contains("identity_user_tenant_name")
    }

    private companion object {
        const val DIVISIONS = 10
        const val DEPARTMENTS = 10
        const val TEAMS = 15
        const val UNIT_SQL =
            "insert into org_unit (id, tenant_id, legal_entity_id, parent_id, ancestors, code, name, valid_from, created_at, updated_at) " +
                "values (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::uuid[], ?, ?, ?::date, ?::timestamptz, ?::timestamptz)"
        const val USER_SQL =
            "insert into identity_user (id, tenant_id, email, display_name, status, created_at, updated_at) " +
                "values (?::uuid, ?::uuid, ?, ?, ?, ?::timestamptz, ?::timestamptz)"
        const val POSITION_SQL =
            "insert into org_position (id, tenant_id, unit_id, title, valid_from, created_at, updated_at) " +
                "values (?::uuid, ?::uuid, ?::uuid, ?, ?::date, ?::timestamptz, ?::timestamptz)"
        const val ASSIGNMENT_SQL =
            "insert into org_assignment (id, tenant_id, user_id, position_id, valid_from, created_at, updated_at) " +
                "values (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::date, ?::timestamptz, ?::timestamptz)"
        val NAMES = listOf("Ahmed", "Rahman", "Karim", "Hossain", "Islam", "Begum", "Akter", "Chowdhury", "Das", "Saha")
        val TITLES = listOf("Officer", "Analyst", "Manager", "Engineer", "Clerk", "Advisor")
    }
}
