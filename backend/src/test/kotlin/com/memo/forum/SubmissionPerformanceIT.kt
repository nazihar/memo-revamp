package com.memo.forum

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantTransactions
import com.memo.forum.internal.ForumInput
import com.memo.forum.internal.ForumSubmissions
import com.memo.forum.internal.ForumTypePreset
import com.memo.forum.internal.ForumTypes
import com.memo.forum.internal.Forums
import com.memo.forum.internal.SubmissionResponses
import com.memo.forum.internal.SubmissionStore
import com.memo.identity.UserAccounts
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
 * Submissions at the design's volume (design E02 §8): 50,000 in a tenant, 2,000 of them open in one committee's queue,
 * five per memo. Budgets have 2x headroom for shared CI runners; the p95 values and the plans are printed for the epic log.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SubmissionPerformanceIT : IntegrationTest() {
    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var tenantDsl: TenantDsl

    @Autowired lateinit var accounts: UserAccounts

    @Autowired lateinit var types: ForumTypes

    @Autowired lateinit var forums: Forums

    @Autowired lateinit var submissions: ForumSubmissions

    @Autowired lateinit var responses: SubmissionResponses

    @Autowired lateinit var store: SubmissionStore

    private lateinit var tenant: TestTenant
    private lateinit var admin: UUID
    private lateinit var busy: UUID

    @BeforeAll
    fun volume() {
        tenant = registry.aTestTenant(transactions, settings, "subperf")
        admin = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true).id.value
        val type =
            asAdmin(write = true) { types.create("MC", "Management committee", ForumTypePreset.MANAGEMENT_COMMITTEE, publish = true) }
        busy = asAdmin(write = true) { forums.create(ForumInput("busy", "BUSY", "Busy committee", "Busy", type.type.id)) }.id
        val quiet = asAdmin(write = true) { forums.create(ForumInput("quiet", "QUIET", "Quiet committee", "Quiet", type.type.id)) }.id
        transactions.inTenant(tenant.id) {
            val at = "now() - (i || ' seconds')::interval"
            val open = "i <= $OPEN"
            tenantDsl.current().execute(
                """
                insert into forum_submission (id, tenant_id, forum_id, subject_type, subject_id, instance_id, purpose, status,
                                              submitted_by, submitted_at, outcome, decided_by, decided_at)
                select md5('s' || i)::uuid, '${tenant.id.value}',
                       case when $open then '$busy'::uuid else '$quiet'::uuid end,
                       'memo', md5('m' || (i % $MEMOS))::uuid, md5('i' || i)::uuid, 'DECISION',
                       case when $open then (case when i % 2 = 0 then 'QUEUED' else 'ACCEPTED' end) else 'DECIDED' end,
                       '$admin', $at,
                       case when $open then null else 'APPROVED' end,
                       case when $open then null else '$admin'::uuid end,
                       case when $open then null else now() end
                from generate_series(1, $TOTAL) i
                """,
            )
            tenantDsl.current().execute("analyze forum_submission")
        }
    }

    @Test
    fun `a committee's queue answers within budget with 2,000 open of 50,000`() {
        assertThat(asAdmin { responses.of(submissions.queue(busy, emptySet())) }).hasSize(ForumSubmissions.MAX_QUEUE)
        val p95 = p95 { asAdmin { responses.of(submissions.queue(busy, emptySet())) } }
        println("SubmissionPerformanceIT queue p95: $p95 ms")
        assertThat(p95).isLessThan(QUEUE_BUDGET_MS * 2)
        val plan =
            explain(
                """
                select * from forum_submission where forum_id = '$busy' and status in ('QUEUED', 'ACCEPTED', 'DEFERRED')
                order by submitted_at, id limit ${ForumSubmissions.MAX_QUEUE}
                """,
            )
        println("SubmissionPerformanceIT queue plan:\n$plan")
        assertThat(plan).contains("Index").doesNotContain("Seq Scan on forum_submission")
    }

    @Test
    fun `a memo's submissions are found within budget`() {
        val subject = asAdmin { tenantDsl.current().fetchValue("select md5('m' || 7)::uuid") as UUID }
        assertThat(asAdmin { store.ofSubject("memo", subject) }).hasSize(TOTAL / MEMOS)
        val p95 = p95 { asAdmin { store.ofSubject("memo", subject) } }
        println("SubmissionPerformanceIT memo panel p95: $p95 ms")
        assertThat(p95).isLessThan(PANEL_BUDGET_MS * 2)
        val plan = explain("select * from forum_submission where subject_type = 'memo' and subject_id = '$subject'")
        println("SubmissionPerformanceIT memo panel plan:\n$plan")
        assertThat(plan).contains("Index").doesNotContain("Seq Scan on forum_submission")
    }

    private fun explain(sql: String): String =
        asAdmin {
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

    private fun <T> asAdmin(
        write: Boolean = false,
        block: () -> T,
    ): T =
        ActorContext.call(ActorScope(Actor.User(admin), null)) {
            TenantContext.call(tenant.id) {
                if (write) transactions.inTenant(tenant.id, block) else transactions.readInTenant(tenant.id, block)
            }
        }

    private companion object {
        const val TOTAL = 50_000
        const val OPEN = 2_000
        const val MEMOS = 10_000
        const val WARM_UP = 5
        const val RUNS = 20
        const val NANOS_PER_MILLI = 1_000_000L
        const val QUEUE_BUDGET_MS = 150L
        const val PANEL_BUDGET_MS = 50L
    }
}
