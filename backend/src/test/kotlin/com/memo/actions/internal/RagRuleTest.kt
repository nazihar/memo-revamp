package com.memo.actions.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * Red, amber and green in Kotlin (test plan E07 T4 `RagRuleTest`; design E07 §1.5): every row of the table, first match
 * winning, and the words' colours. `RagRuleProperties` checks the SQL reading against this one.
 */
class RagRuleTest {
    private val today = LocalDate.of(2026, 12, 10)

    private fun facts(
        status: ActionStatus = ActionStatus.IN_PROGRESS,
        due: LocalDate? = today.plusDays(30),
        amberFrom: LocalDate? = today.plusDays(16),
        staleFrom: LocalDate? = today.plusDays(20),
        confidence: Confidence? = null,
        override: RagColour? = null,
        until: LocalDate? = null,
    ) = RagFacts(status, due, amberFrom, staleFrom, confidence, override, until)

    private fun word(facts: RagFacts) = RagRule.word(facts, today)

    @Test
    fun `a draft, a closed and a cancelled action, and one done to be verified, show their status whatever else holds`() {
        val late = facts(due = today.minusDays(3), confidence = Confidence.OFF_TRACK, override = RagColour.GREEN)
        assertThat(word(late.copy(status = ActionStatus.DRAFT))).isEqualTo(RagWord.DRAFT)
        assertThat(word(late.copy(status = ActionStatus.CLOSED))).isEqualTo(RagWord.CLOSED)
        assertThat(word(late.copy(status = ActionStatus.CANCELLED))).isEqualTo(RagWord.CANCELLED)
        // Done past its date is "Done, to be verified", not overdue.
        assertThat(word(late.copy(status = ActionStatus.DONE_PENDING))).isEqualTo(RagWord.TO_VERIFY)
        assertThat(RagWord.TO_VERIFY.colour).isEqualTo(RagColour.BLUE)
        assertThat(RagWord.CLOSED.colour to RagWord.DRAFT.colour).isEqualTo(RagColour.GREY to RagColour.NONE)
    }

    @Test
    fun `an override in force wins over the dates and the confidence, until its day or until the next update`() {
        val overdue = facts(due = today.minusDays(1))
        assertThat(word(overdue.copy(override = RagColour.GREEN))).isEqualTo(RagWord.OVERRIDE_GREEN)
        assertThat(word(facts(override = RagColour.RED, until = today))).isEqualTo(RagWord.OVERRIDE_RED)
        assertThat(word(facts(override = RagColour.AMBER, until = today.plusDays(1)))).isEqualTo(RagWord.OVERRIDE_AMBER)
        // Past its day, the rule reads the dates again.
        assertThat(word(overdue.copy(override = RagColour.GREEN, overrideUntil = today.minusDays(1)))).isEqualTo(RagWord.OVERDUE)
        assertThat(RagWord.OVERRIDE_AMBER.colour).isEqualTo(RagColour.AMBER)
    }

    @Test
    fun `red is past the due date or off track, the date first`() {
        assertThat(word(facts(due = today.minusDays(1)))).isEqualTo(RagWord.OVERDUE)
        assertThat(word(facts(due = today.minusDays(1), confidence = Confidence.OFF_TRACK))).isEqualTo(RagWord.OVERDUE)
        // Due today is not overdue: it is due at the end of the day.
        assertThat(word(facts(due = today, amberFrom = today.minusDays(10)))).isEqualTo(RagWord.DUE_SOON)
        assertThat(word(facts(confidence = Confidence.OFF_TRACK))).isEqualTo(RagWord.OFF_TRACK)
        assertThat(RagWord.OFF_TRACK.colour).isEqualTo(RagColour.RED)
    }

    @Test
    fun `amber is due soon, at risk or without a recent update, in that order, from the day itself`() {
        assertThat(word(facts(amberFrom = today))).isEqualTo(RagWord.DUE_SOON)
        assertThat(word(facts(amberFrom = today, confidence = Confidence.AT_RISK, staleFrom = today))).isEqualTo(RagWord.DUE_SOON)
        assertThat(word(facts(amberFrom = today.plusDays(1)))).isEqualTo(RagWord.ON_TRACK)
        assertThat(word(facts(confidence = Confidence.AT_RISK, staleFrom = today))).isEqualTo(RagWord.AT_RISK)
        assertThat(word(facts(staleFrom = today))).isEqualTo(RagWord.NO_RECENT_UPDATE)
        assertThat(word(facts(staleFrom = today.plusDays(1)))).isEqualTo(RagWord.ON_TRACK)
        // No stale rule (null): never "No recent update".
        assertThat(word(facts(staleFrom = null))).isEqualTo(RagWord.ON_TRACK)
        assertThat(listOf(RagWord.DUE_SOON, RagWord.AT_RISK, RagWord.NO_RECENT_UPDATE).map { it.colour }.toSet())
            .containsExactly(RagColour.AMBER)
    }

    @Test
    fun `green otherwise, an open action and one on track alike`() {
        assertThat(word(facts(status = ActionStatus.OPEN))).isEqualTo(RagWord.ON_TRACK)
        assertThat(word(facts(confidence = Confidence.ON_TRACK))).isEqualTo(RagWord.ON_TRACK)
        assertThat(word(facts(due = null, amberFrom = null, staleFrom = null))).isEqualTo(RagWord.ON_TRACK)
        assertThat(RagWord.ON_TRACK.colour).isEqualTo(RagColour.GREEN)
        // Every word but the fallback has its case, once.
        assertThat(RagRule.CASES.map { it.first } + RagWord.ON_TRACK).containsExactlyInAnyOrder(*RagWord.entries.toTypedArray())
    }
}
