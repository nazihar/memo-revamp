package com.memo.search.internal.decision

import com.memo.forum.OutcomeKind
import com.memo.minutes.DecisionEntryRef
import com.memo.minutes.DecisionRef
import com.memo.minutes.DecisionState
import com.memo.minutes.ResolutionRef
import com.memo.minutes.ResolutionState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.reflect.full.memberProperties

/**
 * The register's row from a decision's facts (test plan E08 T1, unit): the state as E06 derives it, the flags, the number
 * keys of the resolution and the memo, the body capped at 16,000 characters, the higher of the item's and the meeting's
 * level, and a column list with nothing the register must never hold (design E08 §1.1).
 */
class DecisionRowTest {
    private val decidedAt = Instant.parse("2026-03-17T05:30:00Z")

    private fun entry(
        seq: Int = 1,
        outcome: OutcomeKind = OutcomeKind.APPROVED_WITH_CONDITIONS,
        text: String? = "Approved the term loan to Karnaphuli Steel",
        conditions: List<String> = listOf("Security perfected before disbursement"),
        dissent: Boolean = true,
    ) = DecisionEntryRef(
        decisionId = DECISION,
        seq = seq,
        outcome = outcome,
        outcomeLabel = "Approved with conditions",
        resolution = true,
        text = text,
        conditions = conditions,
        validUntil = LocalDate.parse("2026-09-30"),
        decidedAt = decidedAt,
        correction = seq > 1,
        recordedBy = UUID.randomUUID(),
        recordedAt = decidedAt,
        voteMode = "VOTE",
        dissent = dissent,
    )

    private fun facts(
        entry: DecisionEntryRef = entry(),
        state: DecisionState = DecisionState.NUMBERED,
        number: ResolutionRef? = resolution(),
        itemRank: Int = 0,
        meetingRank: Int = 1,
        memoNumber: String? = "CP/2026/00042",
    ) = DecisionFacts(
        decision =
            DecisionRef(
                id = DECISION,
                meetingId = MEETING,
                itemId = ITEM,
                forumId = FORUM,
                submissionId = UUID.randomUUID(),
                memoId = UUID.randomUUID(),
                state = state,
                current = entry,
                ratificationRequired = false,
                releasedAt = null,
                finalAt = null,
                resolutionNumber = number?.number,
                resolutionId = number?.id,
            ),
        resolution = number,
        forumTypeId = UUID.randomUUID(),
        meeting = MeetingFacts(MEETING, "BRC 3/2026", LocalDate.parse("2026-03-17"), meetingRank),
        item = ItemFacts(ITEM, null, "5", "Term loan, Karnaphuli Steel", itemRank, null, titleHidden = false, membersOnly = false),
        memo = MemoFacts(UUID.randomUUID(), memoNumber),
        chainSubmittedAt = decidedAt.minusSeconds(86_400L * 30),
        priorDeferrals = 1,
        ratifiedBy = null,
    )

    private fun resolution(
        state: ResolutionState = ResolutionState.IN_FORCE,
        number: String = "BRC/2026/014",
        numberedAt: Instant = decidedAt,
    ) = ResolutionRef(UUID.randomUUID(), DECISION, FORUM, MEETING, "2026", 14, number, numberedAt, state, null, null)

    @Test
    fun `a numbered decision's row carries its number keys, the memo's, its flags and the higher level`() {
        val row = DecisionRows.of(facts())
        assertThat(row.state).isEqualTo("NUMBERED")
        assertThat(row.resolutionNumber).isEqualTo("BRC/2026/014")
        assertThat(row.resolutionState).isEqualTo("IN_FORCE")
        assertThat(row.refNorm).isEqualTo("BRC-2026-014")
        assertThat(row.refTail).isEqualTo("14")
        assertThat(row.memoRefNorm).isEqualTo("CP-2026-00042")
        assertThat(row.hasDissent).isTrue()
        assertThat(row.corrected).isFalse()
        assertThat(row.classificationRank).isEqualTo(1)
        assertThat(row.body).isEqualTo(
            "Approved with conditions\nApproved the term loan to Karnaphuli Steel\nSecurity perfected before disbursement",
        )
        assertThat(DecisionRows.of(facts(itemRank = 2, meetingRank = 0)).classificationRank).isEqualTo(2)
    }

    @Test
    fun `a correction is corrected, and a decision without a number or memo number has no keys`() {
        val corrected = entry(seq = 3, dissent = false)
        val row = DecisionRows.of(facts(entry = corrected, state = DecisionState.RECORDED, number = null, memoNumber = null))
        assertThat(row.corrected).isTrue()
        assertThat(row.entrySeq).isEqualTo(3)
        assertThat(row.state).isEqualTo("RECORDED")
        assertThat(row.resolutionId).isNull()
        assertThat(row.refNorm).isNull()
        assertThat(row.refTail).isNull()
        assertThat(row.memoRefNorm).isNull()
        assertThat(row.hasDissent).isFalse()
    }

    @Test
    fun `the number shown is the one in force, else the last voided`() {
        val void = resolution(ResolutionState.VOID, "BRC/2026/009", decidedAt)
        val again = resolution(ResolutionState.IN_FORCE, "BRC/2026/015", decidedAt.plusSeconds(60))
        assertThat(DecisionRows.shown(listOf(void, again))).isEqualTo(again)
        val later = resolution(ResolutionState.VOID, "BRC/2026/016", decidedAt.plusSeconds(120))
        assertThat(DecisionRows.shown(listOf(void, later))).isEqualTo(later)
        assertThat(DecisionRows.shown(emptyList())).isNull()
        assertThat(DecisionRows.of(facts(number = void)).resolutionState).isEqualTo("VOID")
    }

    @Test
    fun `the body holds the label, text and conditions, at most 16,000 characters`() {
        val long = DecisionRows.of(facts(entry = entry(text = "x".repeat(4_000), conditions = List(20) { "c".repeat(1_000) })))
        assertThat(long.body).hasSize(DecisionRows.BODY_MAX)
        val bare = DecisionRows.of(facts(entry = entry(outcome = OutcomeKind.NOTED, text = null, conditions = emptyList())))
        assertThat(bare.body).isEqualTo("Approved with conditions")
        assertThat(bare.outcomeKind).isEqualTo("NOTED")
    }

    @Test
    fun `the row never holds notes, discussion, papers, natures of interests, names or override reasons`() {
        val columns = DecisionRow::class.memberProperties.map { it.name.lowercase() }
        val never = listOf("note", "discussion", "paper", "nature", "dissenter", "name", "abstention", "override", "reason", "vote")
        assertThat(columns.filter { c -> never.any { c.contains(it) } && c != "votemode" }).isEmpty()
        assertThat(columns).contains("hasdissent", "votemode")
    }

    private companion object {
        val DECISION: UUID = UUID.randomUUID()
        val MEETING: UUID = UUID.randomUUID()
        val ITEM: UUID = UUID.randomUUID()
        val FORUM: UUID = UUID.randomUUID()
    }
}
