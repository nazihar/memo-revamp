package com.memo.memo

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantTransactions
import com.memo.identity.UserAccounts
import com.memo.memo.internal.MemoLinkStore
import com.memo.memo.internal.MemoNumberingAdmin
import com.memo.memo.internal.NumberKey
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
import java.util.UUID

/**
 * The numbering register and the link lookup at the design's volume (design E01 §8): 100,000 numbers in one period of
 * one series, every 50th withdrawn (void). Budgets have 2x headroom for shared CI runners; the p95 values and the plans
 * are printed for the epic log.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NumberingPerformanceIT : IntegrationTest() {
    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var tenantDsl: TenantDsl

    @Autowired lateinit var accounts: UserAccounts

    @Autowired lateinit var numbering: MemoNumberingAdmin

    @Autowired lateinit var links: MemoLinkStore

    private lateinit var tenant: TestTenant
    private lateinit var reader: UUID

    @BeforeAll
    fun volume() {
        tenant = registry.aTestTenant(transactions, settings, "numberperf")
        reader = accounts.anActiveUser(transactions, tenant, aPassword()).id.value
        transactions.inTenant(tenant.id) {
            val dsl = tenantDsl.current()
            val t = tenant.id.value
            val type = dsl.fetchValue("select id from memo_type where key = 'general'") as UUID
            val level = dsl.fetchValue("select id from authz_classification_level where rank = 0") as UUID
            val at = "now() - (i || ' seconds')::interval"
            dsl.execute(
                """
                insert into memo (id, tenant_id, reference, number_series, number_period, number_scope, number_sequence,
                                  memo_type_id, subject, classification_id, classification_rank, initiator_id, status,
                                  workflow_instance_id, current_round, created_at, updated_at)
                select md5('n' || i)::uuid, '$t', 'P-2026-' || lpad(i::text, 6, '0'), '$SERIES', '$PERIOD', '', i,
                       '$type', 'Numbered memo ' || i, '$level', 0, '$reader',
                       case when i % 50 = 0 then 'WITHDRAWN' else 'APPROVED' end, md5('w' || i)::uuid, 1, $at, $at
                from generate_series(1, $NUMBERS) i
                """,
            )
            dsl.execute(
                """
                insert into memo_participant (tenant_id, memo_id, user_id, kind, created_at)
                select '$t', md5('n' || i)::uuid, '$reader', 'INITIATOR', $at from generate_series(1, $NUMBERS) i
                on conflict do nothing
                """,
            )
            dsl.execute(
                """
                insert into memo_number_counter (tenant_id, series, period, scope, last_number)
                values ('$t', '$SERIES', '$PERIOD', '', $NUMBERS)
                """,
            )
            dsl.execute("analyze memo, memo_participant, memo_number_counter")
        }
    }

    @Test
    fun `a register page with the completeness check answers within budget at 100,000 numbers`() {
        val first = asReader { numbering.register(KEY, 0, PAGE, reader) }
        assertThat(first.entries).hasSize(PAGE)
        assertThat(first.missingCount).isZero()
        assertThat(first.entries.filter { it.void }.map { it.sequence }).containsExactly(50L)
        // A page deep in the register, by keyset.
        val deep = asReader { numbering.register(KEY, DEEP, PAGE, reader) }
        assertThat(deep.entries.first().sequence).isEqualTo(DEEP + 1)

        val p95 = p95 { asReader { numbering.register(KEY, DEEP, PAGE, reader) } }
        println("NumberingPerformanceIT register page p95: $p95 ms")
        assertThat(p95).isLessThan(REGISTER_BUDGET_MS * 2)

        val plan =
            explain(
                """
                select m.id, m.reference, m.number_sequence from memo m
                where m.number_series = '$SERIES' and m.number_period = '$PERIOD' and m.number_scope = '' and m.number_sequence > $DEEP
                order by m.number_sequence limit ${PAGE + 1}
                """,
            )
        println("NumberingPerformanceIT register page plan:\n$plan")
        assertThat(plan).contains("memo_number_uk").doesNotContain("Seq Scan on memo")
    }

    @Test
    fun `a memo is found by its number within budget`() {
        val reference = "P-2026-" + DEEP.toString().padStart(REFERENCE_DIGITS, '0')
        assertThat(asReader { links.byReference(reference) }).isNotNull()

        val p95 = p95 { asReader { links.byReference(reference) } }
        println("NumberingPerformanceIT lookup by number p95: $p95 ms")
        assertThat(p95).isLessThan(LOOKUP_BUDGET_MS * 2)

        val plan = explain("select id from memo where reference = '$reference'")
        println("NumberingPerformanceIT lookup by number plan:\n$plan")
        assertThat(plan).contains("Index").doesNotContain("Seq Scan on memo")
    }

    private fun explain(sql: String): String =
        asReader {
            tenantDsl
                .current()
                .fetch("explain (analyze, buffers) ${sql.trimIndent()}")
                .joinToString("\n") { it.get(0, String::class.java) }
        }

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

    private fun <T> asReader(block: () -> T): T =
        ActorContext.call(ActorScope(Actor.User(reader), null)) {
            TenantContext.call(tenant.id) { transactions.readInTenant(tenant.id, block) }
        }

    private companion object {
        const val SERIES = "perf"
        const val PERIOD = "2026"
        val KEY = NumberKey(SERIES, PERIOD, "")
        const val NUMBERS = 100_000
        const val DEEP = 73_000L
        const val PAGE = 50
        const val REFERENCE_DIGITS = 6
        const val WARM_UP = 5
        const val RUNS = 20
        const val NANOS_PER_MILLI = 1_000_000L
        const val REGISTER_BUDGET_MS = 300L
        const val LOOKUP_BUDGET_MS = 50L
    }
}
