package com.memo.minutes.internal

import com.memo.common.web.RefusedException
import com.memo.forum.OutcomeKind
import com.memo.meeting.MeetingStatus
import com.memo.minutes.DecisionState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Instant
import java.util.UUID

/**
 * Every allowed and every forbidden transition of a decision (test plan E06 T3 `DecisionStateMachineTest`, design E06 §1.1
 * state diagram, ADR-0065): record and correct while the meeting is in progress or held; WRONG_STATE before it starts and
 * for a cancelled one; FROZEN once the minutes are approved (by the meeting, the minutes or the decision); a change while
 * the minutes are out of the draft only with the confirmation (MINUTES_IN_REVIEW) and then sending them back; after the
 * release the outcome kind fixed (RELEASED) and the rest open. NUMBERED comes with T4.
 */
class DecisionStateMachineTest {
    private val now: Instant = Instant.parse("2026-12-12T06:00:00Z")

    private fun decision(
        released: Boolean = false,
        final: Boolean = false,
    ) = DecisionRow(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        null,
        null,
        1,
        now.takeIf { released },
        OutcomeKind.APPROVED.takeIf { released },
        now.takeIf { final },
        3,
    )

    private fun refused(block: () -> Unit): String? = (runCatching(block).exceptionOrNull() as? RefusedException)?.reason

    @ParameterizedTest
    @EnumSource(MeetingStatus::class)
    fun `record and correct only while the meeting is in progress or held, never after the minutes are approved`(status: MeetingStatus) {
        val expected =
            when (status) {
                MeetingStatus.IN_PROGRESS, MeetingStatus.HELD -> null
                MeetingStatus.MINUTES_APPROVED, MeetingStatus.CLOSED -> "FROZEN"
                else -> "WRONG_STATE"
            }
        // [*] -> RECORDED and RECORDED -> RECORDED alike.
        assertThat(refused { DecisionRules.window(status, null, null, false) }).isEqualTo(expected)
        assertThat(refused { DecisionRules.window(status, MinutesStage.DRAFT, decision(), false) }).isEqualTo(expected)
    }

    @Test
    fun `a final decision never changes, whatever the minutes say`() {
        assertThat(refused { DecisionRules.window(MeetingStatus.HELD, null, decision(final = true), true) }).isEqualTo("FROZEN")
        assertThat(refused { DecisionRules.window(MeetingStatus.HELD, MinutesStage.APPROVED, decision(), true) }).isEqualTo("FROZEN")
        assertThat(refused { DecisionRules.window(MeetingStatus.HELD, MinutesStage.APPROVED, null, true) }).isEqualTo("FROZEN")
    }

    @ParameterizedTest
    @EnumSource(value = MinutesStage::class, names = ["CHAIR_REVIEW", "CIRCULATED", "AWAITING_CHAIR"])
    fun `a change once the minutes left the draft needs the confirmation and sends them back`(stage: MinutesStage) {
        assertThat(refused { DecisionRules.window(MeetingStatus.HELD, stage, decision(), false) }).isEqualTo("MINUTES_IN_REVIEW")
        assertThat(refused { DecisionRules.window(MeetingStatus.HELD, stage, null, false) }).isEqualTo("MINUTES_IN_REVIEW")
        assertThat(DecisionRules.window(MeetingStatus.HELD, stage, decision(), true)).isTrue()
    }

    @Test
    fun `in the draft or before the minutes exist, a change does not touch them`() {
        assertThat(DecisionRules.window(MeetingStatus.HELD, MinutesStage.DRAFT, decision(), false)).isFalse()
        assertThat(DecisionRules.window(MeetingStatus.IN_PROGRESS, null, null, false)).isFalse()
    }

    @Test
    fun `after the release the outcome kind is fixed and the rest may change`() {
        val released = decision(released = true)
        val entry = entry(OutcomeKind.APPROVED)
        assertThat(refused { DecisionRules.released(released, entry, OutcomeKind.REJECTED) }).isEqualTo("RELEASED")
        assertThat(refused { DecisionRules.released(released, entry, OutcomeKind.APPROVED) }).isNull()
        assertThat(refused { DecisionRules.released(decision(), entry, OutcomeKind.REJECTED) }).isNull()
        assertThat(refused { DecisionRules.released(null, null, OutcomeKind.REJECTED) }).isNull()
    }

    @Test
    fun `the state is derived as recorded, released or final`() {
        assertThat(DecisionState.of(null, null)).isEqualTo(DecisionState.RECORDED)
        assertThat(DecisionState.of(now, null)).isEqualTo(DecisionState.RELEASED)
        assertThat(DecisionState.of(now, now)).isEqualTo(DecisionState.FINAL)
        assertThat(DecisionState.of(null, now)).isEqualTo(DecisionState.FINAL)
    }

    private fun entry(kind: OutcomeKind) =
        EntryRow(
            UUID.randomUUID(),
            1,
            kind,
            kind.name,
            true,
            "Resolved",
            emptyList(),
            null,
            null,
            null,
            null,
            VoteMode.CONSENSUS,
            null,
            null,
            null,
            emptyList(),
            emptyList(),
            emptyList(),
            UUID.randomUUID(),
            false,
            null,
            now,
            null,
            false,
            null,
            UUID.randomUUID(),
            now,
        )
}
