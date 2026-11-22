package com.memo.memo

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantTransactions
import com.memo.identity.UserAccounts
import com.memo.jooq.tables.references.MEMO_
import com.memo.memo.internal.MemoCopyStore
import com.memo.memo.internal.MemoNotes
import com.memo.memo.internal.MemoReadable
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.Latency
import com.memo.testsupport.TestTenant
import com.memo.testsupport.aPassword
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.anActiveUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * The note sheet and the access rule at the design's volume (design E03 §9): 100,000 memos, 20,000 shares and copies of
 * other people, 300 of the reader's; a memo with 200 notes; a memo with 500 read receipts. Budgets have 2x headroom for
 * shared CI runners; the measured p95 values are printed for the log.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotesAccessPerformanceIT : IntegrationTest() {
    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var tenantDsl: TenantDsl

    @Autowired lateinit var accounts: UserAccounts

    @Autowired lateinit var notes: MemoNotes

    @Autowired lateinit var readable: MemoReadable

    @Autowired lateinit var copies: MemoCopyStore

    private lateinit var tenant: TestTenant
    private lateinit var reader: UUID
    private lateinit var sheetMemo: UUID
    private lateinit var receiptMemo: UUID

    @BeforeAll
    fun volume() {
        tenant = registry.aTestTenant(transactions, settings, "notesperf")
        reader = accounts.anActiveUser(transactions, tenant, aPassword()).id.value
        transactions.inTenant(tenant.id) {
            val dsl = tenantDsl.current()
            val t = tenant.id.value
            val type = dsl.fetchValue("select id from memo_type where key = 'general'") as UUID
            val level = dsl.fetchValue("select id from authz_classification_level where rank = 0") as UUID
            val at = "now() - (i || ' seconds')::interval"
            // Memo 1 is the reader's, with the note sheet; memo 2 has the receipts. The rest belong to 50 other people.
            val initiator = "case when i <= 2 then '$reader'::uuid else md5('nau' || (i % 50))::uuid end"
            dsl.execute(
                """
                insert into memo (id, tenant_id, reference, memo_type_id, subject, classification_id, classification_rank,
                                  initiator_id, status, current_round, created_at, updated_at)
                select md5('nam' || i)::uuid, '$t', 'M-2025-' || lpad(i::text, 6, '0'), '$type', 'Access memo ' || i, '$level', 0,
                       $initiator, 'DRAFT', 0, $at, $at
                from generate_series(1, $MEMOS) i
                """,
            )
            dsl.execute(
                """
                insert into memo_participant (tenant_id, memo_id, user_id, kind, created_at)
                select '$t', md5('nam' || i)::uuid, $initiator, 'INITIATOR', $at from generate_series(1, $MEMOS) i
                """,
            )
            // Shares and copies: 20,000 of other people, and 300 of the reader's spread over the volume.
            dsl.execute(
                """
                insert into memo_participant (tenant_id, memo_id, user_id, kind, granted_by, created_at)
                select '$t', md5('nam' || (3 + (i * 7919) % ($MEMOS - 2)))::uuid, md5('nao' || (i % 400))::uuid,
                       case when i % 2 = 0 then 'SHARED' else 'COPY' end, md5('nau1')::uuid, now()
                from generate_series(1, $OTHERS) i
                on conflict do nothing
                """,
            )
            dsl.execute(
                """
                insert into memo_participant (tenant_id, memo_id, user_id, kind, granted_by, created_at)
                select '$t', md5('nam' || (3 + i * 331))::uuid, '$reader',
                       case when i % 2 = 0 then 'SHARED' else 'COPY' end, md5('nau1')::uuid, now()
                from generate_series(1, $MINE) i
                on conflict do nothing
                """,
            )
            notesAndReceipts(dsl, t)
            dsl.execute("analyze memo, memo_participant, memo_note, memo_receipt")
            sheetMemo = dsl.fetchValue("select md5('nam1')::uuid") as UUID
            receiptMemo = dsl.fetchValue("select md5('nam2')::uuid") as UUID
        }
    }

    /** 200 notes on memo 1 and 500 receipts on memo 2. */
    private fun notesAndReceipts(
        dsl: org.jooq.DSLContext,
        t: UUID,
    ) {
        dsl.execute(
            """
                insert into memo_note (id, tenant_id, memo_id, round, kind, author_id, body, body_schema, body_text, created_at)
                select md5('nan' || i)::uuid, '$t', md5('nam1')::uuid, 1, 'COMMENT', '$reader',
                       jsonb_build_object('type', 'doc', 'content', jsonb_build_array(jsonb_build_object('type', 'paragraph',
                           'content', jsonb_build_array(jsonb_build_object('type', 'text', 'text', 'Note number ' || i))))),
                       1, 'Note number ' || i, now() - ((${NOTES + 1} - i) || ' seconds')::interval
                from generate_series(1, $NOTES) i
                """,
        )
        dsl.execute(
            """
                insert into memo_receipt (tenant_id, memo_id, user_id, opened_at, document_version)
                select '$t', md5('nam2')::uuid, md5('nar' || i)::uuid, now(), 1 from generate_series(1, $RECEIPTS) i
                """,
        )
    }

    @Test
    fun `the note sheet of 200 notes answers within budget`() {
        assertThat(asReader { notes.sheet(sheetMemo).notes }).hasSizeGreaterThanOrEqualTo(NOTES)
        val p95 = p95 { asReader { notes.sheet(sheetMemo) } }
        println("NotesAccessPerformanceIT note sheet (200 notes) p95: $p95 ms")
        assertThat(p95).isLessThan(SHEET_BUDGET_MS * 2)
    }

    @Test
    fun `posting a note answers within budget`() {
        val json = JsonMapper.builder().build()
        val body =
            json.readTree(
                """{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"Timed note."}]}]}""",
            )
        val p95 = p95 { asReader(write = true) { notes.post(sheetMemo, body, null, emptyList()) } }
        println("NotesAccessPerformanceIT post a note p95: $p95 ms")
        assertThat(p95).isLessThan(POST_BUDGET_MS * 2)
    }

    @Test
    fun `the newest readable memos answer within budget at 100,000 memos and 20,000 shares and copies`() {
        fun page() =
            asReader {
                tenantDsl
                    .current()
                    .select(MEMO_.ID)
                    .from(MEMO_)
                    .where(readable.condition(reader))
                    .orderBy(MEMO_.CREATED_AT.desc())
                    .limit(PAGE)
                    .fetch(MEMO_.ID)
            }
        assertThat(page()).hasSize(PAGE)
        val p95 = p95 { page() }
        println("NotesAccessPerformanceIT readable memo list p95: $p95 ms")
        assertThat(p95).isLessThan(LIST_BUDGET_MS * 2)
    }

    @Test
    fun `the receipts of a memo with 500 recipients answer within budget`() {
        assertThat(asReader { copies.receipts(receiptMemo) }).hasSize(RECEIPTS)
        val p95 = p95 { asReader { copies.receipts(receiptMemo) } }
        println("NotesAccessPerformanceIT receipts (500) p95: $p95 ms")
        assertThat(p95).isLessThan(RECEIPTS_BUDGET_MS * 2)
    }

    private fun p95(call: () -> Unit): Long = Latency.p95(WARM_UP, RUNS, call = call)

    private fun <T> asReader(
        write: Boolean = false,
        block: () -> T,
    ): T =
        ActorContext.call(ActorScope(Actor.User(reader), null)) {
            TenantContext.call(tenant.id) {
                if (write) transactions.inTenant(tenant.id, block) else transactions.readInTenant(tenant.id, block)
            }
        }

    private companion object {
        const val MEMOS = 100_000
        const val OTHERS = 20_000
        const val MINE = 300
        const val NOTES = 200
        const val RECEIPTS = 500
        const val PAGE = 25
        const val WARM_UP = 5
        const val RUNS = 20
        const val SHEET_BUDGET_MS = 100L
        const val POST_BUDGET_MS = 150L
        const val LIST_BUDGET_MS = 100L
        const val RECEIPTS_BUDGET_MS = 50L
    }
}
