package com.memo.meeting.internal

import com.memo.common.web.RefusedException
import com.memo.meeting.MeetingStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Every allowed and forbidden transition of a declaration (test plan E04, T1; design E04 §1.2), and the windows by the
 * meeting's status (design E04 §1.1).
 */
class DeclarationStateTest {
    @Test
    fun `a new declaration to leave is recused at once, and one asking the chair waits for a ruling`() {
        assertThat(DeclarationRules.initial(DeclarationIntent.LEAVE)).isEqualTo(DeclarationState.RECUSED)
        assertThat(DeclarationRules.initial(DeclarationIntent.ASK_CHAIR)).isEqualTo(DeclarationState.AWAITING_RULING)
        assertThat(DeclarationState.entries.filter { it.effective })
            .containsExactly(DeclarationState.RECUSED, DeclarationState.AWAITING_RULING)
    }

    @Test
    fun `the declarant asks the chair from recused, and chooses to leave from a pending ruling or from taking part`() {
        val allowed =
            mapOf(
                (DeclarationState.RECUSED to DeclarationIntent.ASK_CHAIR) to DeclarationState.AWAITING_RULING,
                (DeclarationState.AWAITING_RULING to DeclarationIntent.LEAVE) to DeclarationState.RECUSED,
                (DeclarationState.TAKING_PART to DeclarationIntent.LEAVE) to DeclarationState.RECUSED,
            )
        DeclarationState.entries.forEach { state ->
            DeclarationIntent.entries.forEach { intent ->
                val expected = allowed[state to intent]
                if (expected != null) {
                    assertThat(DeclarationRules.afterIntent(state, intent)).describedAs("$state $intent").isEqualTo(expected)
                } else {
                    wrongState { DeclarationRules.afterIntent(state, intent) }
                }
            }
        }
    }

    @Test
    fun `the chair's ruling moves a pending ruling either way, changes a recusal or a taking part, and nothing withdrawn`() {
        val allowed =
            mapOf(
                (DeclarationState.AWAITING_RULING to RulingOutcome.LEAVE) to DeclarationState.RECUSED,
                (DeclarationState.AWAITING_RULING to RulingOutcome.TAKE_PART) to DeclarationState.TAKING_PART,
                (DeclarationState.RECUSED to RulingOutcome.TAKE_PART) to DeclarationState.TAKING_PART,
                (DeclarationState.TAKING_PART to RulingOutcome.LEAVE) to DeclarationState.RECUSED,
            )
        DeclarationState.entries.forEach { state ->
            RulingOutcome.entries.forEach { outcome ->
                val expected = allowed[state to outcome]
                if (expected != null) {
                    assertThat(DeclarationRules.afterRuling(state, outcome)).describedAs("$state $outcome").isEqualTo(expected)
                } else {
                    wrongState { DeclarationRules.afterRuling(state, outcome) }
                }
            }
        }
    }

    @Test
    fun `anything but a withdrawn declaration can be withdrawn`() {
        DeclarationState.entries.filter { it != DeclarationState.WITHDRAWN }.forEach {
            assertThat(DeclarationRules.afterWithdrawal(it)).isEqualTo(DeclarationState.WITHDRAWN)
        }
        wrongState { DeclarationRules.afterWithdrawal(DeclarationState.WITHDRAWN) }
    }

    @Test
    fun `until the meeting is held everyone acts, once held only a secretary corrects, and afterwards nothing`() {
        listOf(MeetingStatus.DRAFT, MeetingStatus.SCHEDULED, MeetingStatus.IN_PROGRESS).forEach { status ->
            DeclarationAct.entries.forEach { act ->
                assertThat(DeclarationRules.window(status, act, secretary = false)).describedAs("$status $act").isFalse()
                assertThat(DeclarationRules.window(status, act, secretary = true)).describedAs("$status $act").isFalse()
            }
        }
        DeclarationAct.entries.forEach { act ->
            if (act == DeclarationAct.RECORD || act == DeclarationAct.WITHDRAW) {
                assertThat(DeclarationRules.window(MeetingStatus.HELD, act, secretary = true)).describedAs("$act").isTrue()
            } else {
                frozen { DeclarationRules.window(MeetingStatus.HELD, act, secretary = true) }
            }
            frozen { DeclarationRules.window(MeetingStatus.HELD, act, secretary = false) }
            listOf(MeetingStatus.MINUTES_APPROVED, MeetingStatus.CLOSED, MeetingStatus.CANCELLED).forEach { status ->
                frozen { DeclarationRules.window(status, act, secretary = true) }
            }
        }
    }

    private fun wrongState(block: () -> Any?) = refused("WRONG_STATE", block)

    private fun frozen(block: () -> Any?) = refused("FROZEN", block)

    private fun refused(
        reason: String,
        block: () -> Any?,
    ) = assertThatThrownBy { block() }.isInstanceOfSatisfying(RefusedException::class.java) { assertThat(it.reason).isEqualTo(reason) }
}
