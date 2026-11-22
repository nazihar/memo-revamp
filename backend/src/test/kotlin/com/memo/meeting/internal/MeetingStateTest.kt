package com.memo.meeting.internal

import com.memo.common.web.RefusedException
import com.memo.meeting.MeetingStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Every allowed and forbidden transition of the meeting state machine (design E03 §1.1, test plan T1). */
class MeetingStateTest {
    private val allowed: Map<Pair<MeetingAction, MeetingStatus>, MeetingStatus> =
        mapOf(
            (MeetingAction.EDIT to MeetingStatus.DRAFT) to MeetingStatus.DRAFT,
            (MeetingAction.EDIT to MeetingStatus.SCHEDULED) to MeetingStatus.SCHEDULED,
            (MeetingAction.SCHEDULE to MeetingStatus.DRAFT) to MeetingStatus.SCHEDULED,
            (MeetingAction.RESCHEDULE to MeetingStatus.DRAFT) to MeetingStatus.DRAFT,
            (MeetingAction.RESCHEDULE to MeetingStatus.SCHEDULED) to MeetingStatus.SCHEDULED,
            (MeetingAction.START to MeetingStatus.SCHEDULED) to MeetingStatus.IN_PROGRESS,
            (MeetingAction.END to MeetingStatus.IN_PROGRESS) to MeetingStatus.HELD,
            (MeetingAction.CANCEL to MeetingStatus.DRAFT) to MeetingStatus.CANCELLED,
            (MeetingAction.CANCEL to MeetingStatus.SCHEDULED) to MeetingStatus.CANCELLED,
            (MeetingAction.GUESTS to MeetingStatus.DRAFT) to MeetingStatus.DRAFT,
            (MeetingAction.GUESTS to MeetingStatus.SCHEDULED) to MeetingStatus.SCHEDULED,
            (MeetingAction.GUESTS to MeetingStatus.IN_PROGRESS) to MeetingStatus.IN_PROGRESS,
            // E06 T6: the chair confirms the minutes.
            (MeetingAction.APPROVE_MINUTES to MeetingStatus.HELD) to MeetingStatus.MINUTES_APPROVED,
        )

    @Test
    fun `every allowed transition leads where the state machine says`() {
        allowed.forEach { (from, to) ->
            assertThat(from.first.allows(from.second)).describedAs("$from").isTrue()
            assertThat(from.first.next(from.second)).describedAs("$from").isEqualTo(to)
        }
    }

    @Test
    fun `every other transition is refused with WRONG_STATE`() {
        val forbidden =
            MeetingAction.entries.flatMap { action -> MeetingStatus.entries.map { action to it } }.filterNot { it in allowed.keys }
        assertThat(forbidden).contains(
            MeetingAction.START to MeetingStatus.DRAFT,
            MeetingAction.RESCHEDULE to MeetingStatus.HELD,
            MeetingAction.CANCEL to MeetingStatus.IN_PROGRESS,
            MeetingAction.END to MeetingStatus.SCHEDULED,
            MeetingAction.SCHEDULE to MeetingStatus.CANCELLED,
        )
        forbidden.forEach { (action, status) ->
            assertThat(action.allows(status)).describedAs("$action from $status").isFalse()
            assertThatThrownBy { action.next(status) }
                .describedAs("$action from $status")
                .isInstanceOfSatisfying(RefusedException::class.java) { assertThat(it.reason).isEqualTo("WRONG_STATE") }
        }
    }

    @Test
    fun `nothing moves a meeting out of HELD but the minutes' approval, nor out of CANCELLED or E06's states`() {
        val terminal = setOf(MeetingStatus.HELD, MeetingStatus.CANCELLED, MeetingStatus.MINUTES_APPROVED, MeetingStatus.CLOSED)
        MeetingAction.entries.filter { it != MeetingAction.APPROVE_MINUTES }.forEach { action ->
            terminal.forEach { assertThat(action.allows(it)).isFalse() }
        }
        assertThat(terminal.filter { MeetingAction.APPROVE_MINUTES.allows(it) }).containsExactly(MeetingStatus.HELD)
    }
}
