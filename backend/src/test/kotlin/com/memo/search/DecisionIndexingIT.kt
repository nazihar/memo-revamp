package com.memo.search

import com.memo.common.context.ActorContext
import com.memo.common.tenant.TenantDsl
import com.memo.meeting.internal.ItemChange
import com.memo.meeting.internal.TimesInput
import com.memo.minutes.DecisionPlanting
import com.memo.minutes.MinutesApproved
import com.memo.minutes.ReleaseFixture
import com.memo.minutes.internal.DecisionStore
import com.memo.minutes.internal.NoteStore
import com.memo.search.internal.SearchReferences
import com.memo.search.internal.decision.DecisionIndex
import com.memo.search.internal.decision.DecisionIndexing
import com.memo.testsupport.Audit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * The register's indexing (ADR-0069; test plan E08 T1 `DecisionIndexingIT`, `DecisionBackfillIT`): a row per decision in
 * the recording transaction (rolled back with it); a correction's new text, a number given, voided and given again, the
 * release, the minutes' approval, a new agenda revision (title, restriction, hidden title) and a meeting change, each in the
 * change's transaction; the backfill of decisions recorded without a row, resumed and idempotent, and a rebuild; and the
 * application role never reads the table.
 */
class DecisionIndexingIT : ReleaseFixture() {
    @Autowired lateinit var decisionIndex: DecisionIndex

    @Autowired lateinit var indexing: DecisionIndexing

    @Autowired lateinit var events: ApplicationEventPublisher

    @Autowired lateinit var decisionStore: DecisionStore

    @Autowired lateinit var noteStore: NoteStore

    @Autowired lateinit var dsl: TenantDsl

    private class Rollback : RuntimeException()

    private fun row(decisionId: UUID) = RegisterTable.row(tenant.id.value, decisionId)

    @Suppress("UNCHECKED_CAST")
    private fun array(value: Any?): List<Any?>? = (value as java.sql.Array?)?.let { (it.array as Array<Any?>).toList() }

    @Test
    fun `a decision's row is written in its transaction and rolled back with it, and memo_app cannot read the table`() {
        val r = running("Term loan, Karnaphuli Steel", "Facility renewal, Acme")
        val credit = r.item("Term loan, Karnaphuli Steel")
        val decided = decide(r, credit.item, APPROVED_WITH_CONDITIONS)
        val row = checkNotNull(row(decided.decision.id))
        assertThat(row["forum_id"]).isEqualTo(brc)
        assertThat(row["meeting_id"]).isEqualTo(r.meeting.id)
        assertThat(row["item_id"]).isEqualTo(credit.item)
        assertThat(row["outcome_kind"]).isEqualTo("APPROVED_WITH_CONDITIONS")
        assertThat(row["state"]).isEqualTo("RECORDED")
        assertThat(row["entry_seq"]).isEqualTo(1)
        assertThat(row["has_conditions"]).isEqualTo(true)
        assertThat(row["corrected"]).isEqualTo(false)
        assertThat(row["memo_id"]).isEqualTo(credit.memo)
        assertThat(row["submission_id"]).isEqualTo(credit.submission)
        assertThat(row["chain_submitted_at"]).isNotNull()
        assertThat(row["body"] as String).contains("term loan of BDT 60 million", "Security perfected before disbursement.")
        assertThat(row["title"] as String).isNotBlank()

        val acme = r.item("Facility renewal, Acme")
        try {
            transactions.inTenant(tenant.id) {
                asUser(secretary.account) { recorder.record(r.meeting.id, acme.item, APPROVED, null) }
                assertThat(RegisterTable.rows(tenant.id.value, "item_id = ?::uuid", acme.item.toString())).isEmpty()
                throw Rollback()
            }
        } catch (_: Rollback) {
            // Rolled back with its decision.
        }
        assertThat(RegisterTable.rows(tenant.id.value, "item_id = ?::uuid", acme.item.toString())).isEmpty()
        assertThat(transactions.readInTenant(tenant.id) { decisionReads.current(acme.item) }).isNull()

        transactions.readInTenant(tenant.id) {
            assertThatThrownBy { dsl.current().fetch("select * from search_decision limit 1") }
                .rootCause()
                .hasMessageContaining("permission denied")
        }
    }

    @Test
    fun `a correction, a number given, voided and given again, and the release follow the decision`() {
        // The secretary's own item: a released submission's decision is corrected in the minutes instead (E06).
        var own: UUID? = null
        val r = running("Term loan, Karnaphuli Steel") { meeting -> own = addItem(meeting, "Treasury limits") }
        val item = checkNotNull(own)
        val id = decide(r, item, APPROVED_WITH_CONDITIONS).decision.id
        held(r)
        val number = checkNotNull(numberOf(item))
        val numbered = checkNotNull(row(id))
        assertThat(numbered["resolution_number"]).isEqualTo(number)
        assertThat(numbered["resolution_state"]).isEqualTo("IN_FORCE")
        assertThat(numbered["ref_norm"]).isEqualTo(SearchReferences.norm(number))
        assertThat(numbered["ref_tail"]).isEqualTo(SearchReferences.tail(number))
        assertThat(numbered["state"]).isEqualTo(transactions.readInTenant(tenant.id) { decisionsApi.find(id) }?.state?.name)

        decide(r, item, NOTED.copy(reason = "Recorded against the wrong outcome"))
        val voided = checkNotNull(row(id))
        assertThat(voided["entry_seq"]).isEqualTo(2)
        assertThat(voided["corrected"]).isEqualTo(true)
        assertThat(voided["outcome_kind"]).isEqualTo("NOTED")
        assertThat(voided["decision_text"]).isEqualTo("Noted.")
        assertThat(voided["has_conditions"]).isEqualTo(false)
        assertThat(voided["resolution_number"] to voided["resolution_state"]).isEqualTo(number to "VOID")

        decide(r, item, APPROVED.copy(reason = "The committee approved the facility"))
        val again = checkNotNull(row(id))
        val renumbered = checkNotNull(numberOf(item))
        assertThat(renumbered).isNotEqualTo(number)
        assertThat(again["resolution_number"] to again["resolution_state"]).isEqualTo(renumbered to "IN_FORCE")
        assertThat(again["entry_seq"]).isEqualTo(3)
        assertThat(again["state"]).isEqualTo(transactions.readInTenant(tenant.id) { decisionsApi.find(id) }?.state?.name)
        assertThat(transactions.readInTenant(tenant.id) { decisionIndex.unindexed(100) }).doesNotContain(id)
    }

    @Test
    fun `the minutes' approval makes the meeting's rows final in one write`() {
        val r = running("Term loan, Karnaphuli Steel", "Facility renewal, Acme")
        val ids = r.items.map { decide(r, it.item, APPROVED).decision.id }
        transactions.inTenant(tenant.id) { events.publishEvent(MinutesApproved(tenant.id.value, r.meeting.id, brc)) }
        ids.forEach { id ->
            val row = checkNotNull(row(id))
            assertThat(row["state"]).isEqualTo("FINAL")
            assertThat(row["final_at"]).isNotNull()
        }
    }

    @Test
    fun `a new agenda revision (title, restriction, hidden title) and a meeting change reach the decided items' rows`() {
        val meeting = meeting()
        val item = addItem(meeting, "Liquidity policy")
        publish(meeting)
        val planting = DecisionPlanting(transactions, decisionStore, noteStore)
        val id = planting.decision(tenant.id, meeting.id, brc, item, secretary.id, "Approved the policy")
        transactions.inTenant(tenant.id) { decisionIndex.put(id) }
        assertThat(row(id)?.get("title")).isEqualTo("Liquidity policy")
        assertThat(row(id)?.get("classification_rank")).isEqualTo(0)

        change(meeting, item, ItemChange("Liquidity policy (revised)", restrictedTo = listOf(director.id), titleHidden = true))
        publish(meeting, "Item revised")
        val revised = checkNotNull(row(id))
        assertThat(revised["title"]).isEqualTo("Liquidity policy (revised)")
        assertThat(revised["item_title_hidden"]).isEqualTo(true)
        assertThat(array(revised["item_restricted_to"])).containsExactly(director.id)
        assertThat(revised["item_number"]).isNotNull()

        val day = LocalDate.now().plusDays(DAYS_AHEAD + 7)
        asUser(secretary.account) {
            lifecycle.reschedule(
                meeting.id,
                reads.get(meeting.id).version,
                TimesInput(day.atTime(LocalTime.of(10, 0)), day.atTime(LocalTime.of(13, 0)), "Asia/Dhaka"),
                "The chair is travelling",
            )
        }
        assertThat(row(id)?.get("meeting_date")).isEqualTo(java.sql.Date.valueOf(day))
    }

    @Test
    fun `the backfill fills decisions recorded without a row, resumes, is idempotent, and a rebuild rewrites them`() {
        val meeting = meeting()
        val items = (1..3).map { addItem(meeting, "Item $it") }
        publish(meeting)
        val planting = DecisionPlanting(transactions, decisionStore, noteStore)
        val ids = items.map { planting.decision(tenant.id, meeting.id, brc, it, secretary.id, "Approved $it") }
        assertThat(transactions.readInTenant(tenant.id) { decisionIndex.unindexed(1000) }).containsAll(ids)

        // A run stopped after one decision; the next one carries on from what is still missing.
        assertThat(transactions.inTenant(tenant.id) { indexing.backfill(1) }).isEqualTo(1)
        transactions.inTenant(tenant.id) { generateSequence { indexing.backfill().takeIf { it > 0 } }.count() }
        assertThat(transactions.readInTenant(tenant.id) { decisionIndex.unindexed(1000) }).doesNotContainAnyElementsOf(ids)
        ids.forEach { assertThat(row(it)).describedAs("row of $it").isNotNull() }
        assertThat(transactions.inTenant(tenant.id) { indexing.backfill() }).isZero()

        val before = ids.associateWith { row(it)?.get("updated_at") }
        val rebuilt = transactions.inTenant(tenant.id) { indexing.rebuild(null, 10_000) }
        assertThat(rebuilt).containsAll(ids)
        ids.forEach { assertThat(row(it)?.get("updated_at")).isNotEqualTo(before[it]) }
        ActorContext.asSystem("search-decisions-backfill") {
            transactions.inTenant(tenant.id) { indexing.backfilled(ids.size, rebuild = true) }
        }
        Audit.assertAudited(tenant.id, "search.decisions.backfilled", tenant.id.value.toString())
    }
}
