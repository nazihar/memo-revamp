package com.memo.minutes.internal

import com.memo.common.web.RefusedException
import com.memo.forum.MinutesApproval
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * The minutes' lifecycle (test plan E06 T6 `MinutesStateMachineTest`, design E06 §1.5): every step from every stage under
 * both approval modes, allowed ones to their stage and every other one refused, `FROZEN` once approved.
 */
class MinutesStateMachineTest {
    private val next = MinutesApproval.AT_NEXT_MEETING
    private val circulation = MinutesApproval.BY_CIRCULATION

    /** Where each allowed step leads, by approval mode; anything not listed is refused. */
    private val allowed: Map<Triple<MinutesStage, MinutesTransition, MinutesApproval>, MinutesStage> =
        buildMap {
            MinutesApproval.entries.forEach { mode ->
                put(Triple(MinutesStage.DRAFT, MinutesTransition.SEND_FOR_REVIEW, mode), MinutesStage.CHAIR_REVIEW)
                put(Triple(MinutesStage.CHAIR_REVIEW, MinutesTransition.AGREE, mode), MinutesStage.CIRCULATED)
                put(Triple(MinutesStage.CHAIR_REVIEW, MinutesTransition.RETURN, mode), MinutesStage.DRAFT)
                put(Triple(MinutesStage.DRAFT, MinutesTransition.CIRCULATE, mode), MinutesStage.CIRCULATED)
                put(Triple(MinutesStage.CIRCULATED, MinutesTransition.WITHDRAW, mode), MinutesStage.DRAFT)
                put(Triple(MinutesStage.CIRCULATED, MinutesTransition.CHANGE_DEADLINE, mode), MinutesStage.CIRCULATED)
                put(Triple(MinutesStage.CIRCULATED, MinutesTransition.COMMENT, mode), MinutesStage.CIRCULATED)
                put(Triple(MinutesStage.CIRCULATED, MinutesTransition.RESPOND, mode), MinutesStage.CIRCULATED)
                put(Triple(MinutesStage.AWAITING_CHAIR, MinutesTransition.RESPOND, mode), MinutesStage.AWAITING_CHAIR)
                put(Triple(MinutesStage.AWAITING_CHAIR, MinutesTransition.CONFIRM, mode), MinutesStage.APPROVED)
                listOf(MinutesStage.CHAIR_REVIEW, MinutesStage.CIRCULATED, MinutesStage.AWAITING_CHAIR).forEach {
                    put(Triple(it, MinutesTransition.DECISION_CORRECTED, mode), MinutesStage.DRAFT)
                }
            }
            put(Triple(MinutesStage.CIRCULATED, MinutesTransition.END_CIRCULATION, circulation), MinutesStage.AWAITING_CHAIR)
            put(Triple(MinutesStage.CIRCULATED, MinutesTransition.CLOSE_CIRCULATION, circulation), MinutesStage.AWAITING_CHAIR)
            put(Triple(MinutesStage.CIRCULATED, MinutesTransition.APPROVED_AT_MEETING, next), MinutesStage.AWAITING_CHAIR)
            put(Triple(MinutesStage.AWAITING_CHAIR, MinutesTransition.APPROVAL_UNDONE, next), MinutesStage.DRAFT)
        }

    @TestFactory
    fun `every step from every stage under both modes`(): List<DynamicTest> =
        MinutesStage.entries.flatMap { stage ->
            MinutesTransition.entries.flatMap { step ->
                MinutesApproval.entries.map { mode ->
                    DynamicTest.dynamicTest("$stage --$step--> ($mode)") {
                        val expected = allowed[Triple(stage, step, mode)]
                        if (expected != null) {
                            assertThat(MinutesStateMachine.next(stage, step, mode)).isEqualTo(expected)
                            assertThat(MinutesStateMachine.allows(stage, step, mode)).isTrue()
                        } else {
                            assertThatThrownBy { MinutesStateMachine.next(stage, step, mode) }
                                .isInstanceOfSatisfying(RefusedException::class.java) {
                                    assertThat(it.reason).isEqualTo(if (stage == MinutesStage.APPROVED) "FROZEN" else "WRONG_STATE")
                                }
                            assertThat(MinutesStateMachine.allows(stage, step, mode)).isFalse()
                        }
                    }
                }
            }
        }

    @Test
    fun `the reviewer's agreement with suggested changes left goes back to the secretary, the agreement standing`() {
        assertThat(MinutesStateMachine.next(MinutesStage.CHAIR_REVIEW, MinutesTransition.AGREE, next, ready = false))
            .isEqualTo(MinutesStage.DRAFT)
        assertThat(MinutesStateMachine.next(MinutesStage.CHAIR_REVIEW, MinutesTransition.AGREE, circulation, ready = true))
            .isEqualTo(MinutesStage.CIRCULATED)
    }

    @Test
    fun `nothing moves approved minutes, not even a decision corrected`() {
        MinutesTransition.entries.forEach { step ->
            assertThatThrownBy { MinutesStateMachine.next(MinutesStage.APPROVED, step, next) }
                .isInstanceOfSatisfying(RefusedException::class.java) { assertThat(it.reason).isEqualTo("FROZEN") }
        }
    }
}
