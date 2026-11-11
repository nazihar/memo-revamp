package com.memo.memo

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantTransactions
import com.memo.identity.UserAccounts
import com.memo.memo.internal.MemoBox
import com.memo.memo.internal.MemoInbox
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
 * Inbox and sent box at the design's volume (design E01 §10): 10,000 memos in review with one open task each, 300 of
 * them for the reader, 500 sent by the reader. Budgets have 2x headroom for shared CI runners; the measured p95 values
 * are printed for the log. E05 extends the generator with timers.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemoPerformanceIT : IntegrationTest() {
    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var tenantDsl: TenantDsl

    @Autowired lateinit var accounts: UserAccounts

    @Autowired lateinit var inbox: MemoInbox

    private lateinit var tenant: TestTenant
    private lateinit var reader: UUID

    @BeforeAll
    fun volume() {
        tenant = registry.aTestTenant(transactions, settings, "memoperf")
        reader = accounts.anActiveUser(transactions, tenant, aPassword()).id.value
        transactions.inTenant(tenant.id) {
            val dsl = tenantDsl.current()
            val t = tenant.id.value
            val type = dsl.fetchValue("select id from memo_type where key = 'general'") as UUID
            val level = dsl.fetchValue("select id from authz_classification_level where rank = 0") as UUID
            val definition = dsl.fetchValue("select id from workflow_definition_version limit 1") as UUID
            // Memo i: sent by the reader for i <= 500, else by one of 50 others; its open task is the reader's for i <= 300.
            val initiator = "case when i <= $SENT then '$reader'::uuid else md5('u' || (i % 50))::uuid end"
            val assignee = "case when i <= $MINE then '$reader'::uuid else md5('a' || (i % 97))::uuid end"
            val at = "now() - (i || ' seconds')::interval"
            dsl.execute(
                """
                insert into memo (id, tenant_id, reference, memo_type_id, subject, classification_id, classification_rank,
                                  initiator_id, status, workflow_instance_id, current_round, created_at, updated_at)
                select md5('m' || i)::uuid, '$t', 'M-2025-' || lpad(i::text, 5, '0'), '$type', 'Performance memo ' || i, '$level', 0,
                       $initiator, 'IN_REVIEW', md5('i' || i)::uuid, 1, $at, $at
                from generate_series(1, $MEMOS) i
                """,
            )
            dsl.execute(
                """
                insert into memo_participant (tenant_id, memo_id, user_id, kind, created_at)
                select '$t', md5('m' || i)::uuid, $assignee, 'APPROVER', $at from generate_series(1, $MEMOS) i
                on conflict do nothing
                """,
            )
            dsl.execute(
                """
                insert into workflow_instance (id, tenant_id, subject_type, subject_id, definition_version_id, initiator_id, status,
                                               current_round, current_step_id, current_assignees, created_at, updated_at)
                select md5('i' || i)::uuid, '$t', 'memo', md5('m' || i)::uuid, '$definition', $initiator, 'RUNNING', 1,
                       md5('s' || i)::uuid, array[$assignee], $at, $at
                from generate_series(1, $MEMOS) i
                """,
            )
            dsl.execute(
                """
                insert into workflow_round (id, tenant_id, instance_id, number, outcome, started_by, started_at)
                select md5('r' || i)::uuid, '$t', md5('i' || i)::uuid, 1, 'OPEN', $initiator, $at from generate_series(1, $MEMOS) i
                """,
            )
            dsl.execute(
                """
                insert into workflow_step (id, tenant_id, instance_id, round_id, ordinal, type, assignee_id, state)
                select md5('s' || i)::uuid, '$t', md5('i' || i)::uuid, md5('r' || i)::uuid, 1, 'APPROVE', $assignee, 'ACTIVE'
                from generate_series(1, $MEMOS) i
                """,
            )
            dsl.execute(
                """
                insert into workflow_task (id, tenant_id, instance_id, step_id, assignee_id, state, created_at)
                select md5('k' || i)::uuid, '$t', md5('i' || i)::uuid, md5('s' || i)::uuid, $assignee, 'OPEN', $at
                from generate_series(1, $MEMOS) i
                """,
            )
            dsl.execute("analyze memo, memo_participant, workflow_instance, workflow_task")
        }
    }

    @Test
    fun `the first inbox page answers within budget at 10,000 open tasks`() {
        val first = asReader { inbox.tasks(null, PAGE) }
        assertThat(first.items).hasSize(PAGE)
        assertThat(first.items.map { it.memo.restricted }).containsOnly(false)
        assertThat(first.items.map { it.assignedAt }).isSortedAccordingTo(compareByDescending { it })
        val second = asReader { inbox.tasks(first.nextCursor, PAGE) }
        assertThat(second.items.map { it.taskId }).doesNotContainAnyElementsOf(first.items.map { it.taskId })

        val p95 = p95 { asReader { inbox.tasks(null, PAGE) } }
        println("MemoPerformanceIT inbox first page p95: $p95 ms")
        assertThat(p95).isLessThan(INBOX_BUDGET_MS * 2)
    }

    @Test
    fun `the sent box with holders answers within budget at 500 sent memos`() {
        val first = asReader { inbox.box(MemoBox.SENT, null, PAGE) }
        assertThat(first.items).hasSize(PAGE)
        assertThat(first.items).allSatisfy { assertThat(it.holders).hasSize(1) }

        val p95 = p95 { asReader { inbox.box(MemoBox.SENT, null, PAGE) } }
        println("MemoPerformanceIT sent box first page p95: $p95 ms")
        assertThat(p95).isLessThan(SENT_BUDGET_MS * 2)
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
        const val MEMOS = 10_000
        const val MINE = 300
        const val SENT = 500
        const val PAGE = 25
        const val WARM_UP = 5
        const val RUNS = 20
        const val NANOS_PER_MILLI = 1_000_000L
        const val INBOX_BUDGET_MS = 150L
        const val SENT_BUDGET_MS = 150L
    }
}
