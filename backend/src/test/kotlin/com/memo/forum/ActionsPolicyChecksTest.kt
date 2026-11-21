package com.memo.forum

import com.memo.forum.internal.ActionsChecks
import com.memo.forum.internal.ForumTypePreset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

/**
 * A committee type's actions policy (design E07 §1.13; test plan T2 `ActionsPolicyChecksTest`): each number of working
 * days in its range, 0 switching a rule off where the design allows it; every choice of each kind; the defaults when the
 * policy or a field is missing; and the presets.
 */
class ActionsPolicyChecksTest {
    private val json = JsonMapper.builder().addModule(kotlinModule()).build()

    private fun problems(policy: ActionsPolicy) = ActionsChecks.problems(policy).map { "${it.field}:${it.code}" }

    @Test
    fun `the defaults pass and are the design's`() {
        val policy = ActionsPolicy()
        assertThat(problems(policy)).isEmpty()
        assertThat(policy.amberWorkingDays).isEqualTo(10)
        assertThat(policy.staleWorkingDays).isEqualTo(20)
        assertThat(policy.dueChange).isEqualTo(DueChange.SECRETARY_AGREES)
        assertThat(policy.verifiedBy).isEqualTo(ActionVerifier.SECRETARY)
        assertThat(policy.evidenceRequired).isFalse()
        assertThat(policy.defaultDue).isEqualTo(DefaultDue.NEXT_MEETING)
        assertThat(policy.defaultDueWorkingDays).isEqualTo(20)
        assertThat(policy.updateRequestWorkingDays).isEqualTo(5)
        assertThat(policy.definitionKey).isEqualTo("action-item")
    }

    @Test
    fun `each number of working days is checked at its edges`() {
        // Amber: 1 to 60, never off.
        assertThat(problems(ActionsPolicy(amberWorkingDays = 0))).containsExactly("actions/amberWorkingDays:RANGE")
        assertThat(problems(ActionsPolicy(amberWorkingDays = 61))).containsExactly("actions/amberWorkingDays:RANGE")
        assertThat(problems(ActionsPolicy(amberWorkingDays = 1))).isEmpty()
        assertThat(problems(ActionsPolicy(amberWorkingDays = 60))).isEmpty()
        // No recent update: 5 to 120, or 0 for off.
        assertThat(problems(ActionsPolicy(staleWorkingDays = 0))).isEmpty()
        assertThat(problems(ActionsPolicy(staleWorkingDays = 4))).containsExactly("actions/staleWorkingDays:RANGE")
        assertThat(problems(ActionsPolicy(staleWorkingDays = 5))).isEmpty()
        assertThat(problems(ActionsPolicy(staleWorkingDays = 120))).isEmpty()
        assertThat(problems(ActionsPolicy(staleWorkingDays = 121))).containsExactly("actions/staleWorkingDays:RANGE")
        assertThat(problems(ActionsPolicy(staleWorkingDays = -1))).containsExactly("actions/staleWorkingDays:RANGE")
        // The default due date in working days: 1 to 120, also used by NEXT_MEETING without a next meeting.
        assertThat(problems(ActionsPolicy(defaultDueWorkingDays = 0))).containsExactly("actions/defaultDueWorkingDays:RANGE")
        assertThat(problems(ActionsPolicy(defaultDueWorkingDays = 121))).containsExactly("actions/defaultDueWorkingDays:RANGE")
        assertThat(problems(ActionsPolicy(defaultDue = DefaultDue.WORKING_DAYS, defaultDueWorkingDays = 120))).isEmpty()
        // Update requests: 1 to 20 before the papers are due, or 0 for off.
        assertThat(problems(ActionsPolicy(updateRequestWorkingDays = 0))).isEmpty()
        assertThat(problems(ActionsPolicy(updateRequestWorkingDays = 1))).isEmpty()
        assertThat(problems(ActionsPolicy(updateRequestWorkingDays = 20))).isEmpty()
        assertThat(problems(ActionsPolicy(updateRequestWorkingDays = 21))).containsExactly("actions/updateRequestWorkingDays:RANGE")
        // A definition key longer than any definition's.
        assertThat(problems(ActionsPolicy(definitionKey = "a".repeat(101)))).containsExactly("actions/definitionKey:LENGTH")
        // Several problems are all named.
        assertThat(problems(ActionsPolicy(amberWorkingDays = 0, staleWorkingDays = 2, updateRequestWorkingDays = 30)))
            .containsExactly("actions/amberWorkingDays:RANGE", "actions/staleWorkingDays:RANGE", "actions/updateRequestWorkingDays:RANGE")
    }

    @Test
    fun `every choice of each kind passes and round-trips as JSON`() {
        val choices =
            DueChange.entries.flatMap { due ->
                ActionVerifier.entries.flatMap { verifier ->
                    DefaultDue.entries.map { ActionsPolicy(dueChange = due, verifiedBy = verifier, defaultDue = it) }
                }
            }
        assertThat(choices).hasSize(DueChange.entries.size * ActionVerifier.entries.size * DefaultDue.entries.size)
        choices.forEach { policy ->
            assertThat(problems(policy)).isEmpty()
            assertThat(json.readValue(json.writeValueAsString(policy), ActionsPolicy::class.java)).isEqualTo(policy)
        }
    }

    @Test
    fun `a policy missing fields reads as the defaults for them`() {
        assertThat(json.readValue("{}", ActionsPolicy::class.java)).isEqualTo(ActionsPolicy())
        assertThat(json.readValue("""{"evidenceRequired":true,"staleWorkingDays":0}""", ActionsPolicy::class.java))
            .isEqualTo(ActionsPolicy(evidenceRequired = true, staleWorkingDays = 0))
    }

    @Test
    fun `boards and board committees use the defaults, an audit committee asks for evidence, management committees are quicker`() {
        assertThat(ForumTypePreset.BOARD.settings.actions).isEqualTo(ActionsPolicy())
        assertThat(ForumTypePreset.BOARD_COMMITTEE.settings.actions).isEqualTo(ActionsPolicy())
        assertThat(ForumTypePreset.BOARD_AUDIT_COMMITTEE.settings.actions).isEqualTo(ActionsPolicy(evidenceRequired = true))
        assertThat(ForumTypePreset.BOARD_AUDIT_COMMITTEE.settings.copy(name = "", description = null, actions = ActionsPolicy()))
            .describedAs("otherwise a board committee")
            .isEqualTo(ForumTypePreset.BOARD_COMMITTEE.settings.copy(name = "", description = null))
        assertThat(ForumTypePreset.MANAGEMENT_COMMITTEE.settings.actions)
            .isEqualTo(
                ActionsPolicy(
                    amberWorkingDays = 5,
                    dueChange = DueChange.OWNER_WITH_REASON,
                    defaultDue = DefaultDue.WORKING_DAYS,
                    defaultDueWorkingDays = 10,
                ),
            )
        ForumTypePreset.entries.forEach { assertThat(problems(it.settings.actions)).describedAs(it.name).isEmpty() }
    }
}
