package com.memo.actions.internal

import com.memo.common.web.RefusedException
import com.memo.meeting.MeetingStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** The pure rules of an action (test plan E07 T3 `ActionRulesTest`; design E07 §1.1, §1.4), each with its problem code. */
class ActionRulesTest {
    private val someone = UUID.randomUUID()
    private val today = LocalDate.of(2026, 11, 21)

    private fun codes(fields: ActionFields) = ActionRules.fields(fields).map { it.field to it.code }

    @Test
    fun `the fields' lengths, the owner's shape and the people involved`() {
        assertThat(codes(ActionFields("Report"))).isEmpty()
        assertThat(codes(ActionFields(" "))).containsExactly("title" to "REQUIRED")
        assertThat(codes(ActionFields("x".repeat(201)))).containsExactly("title" to "LENGTH")
        assertThat(
            codes(ActionFields("x".repeat(200), description = "d".repeat(4001), context = "c".repeat(2001), sourceNote = "n".repeat(301))),
        ).containsExactly("description" to "LENGTH", "context" to "LENGTH", "sourceNote" to "LENGTH")
        assertThat(codes(ActionFields("Report", owner = OwnerInput(OwnerKind.PERSON)))).containsExactly("owner/userId" to "REQUIRED")
        assertThat(codes(ActionFields("Report", owner = OwnerInput(OwnerKind.POSITION, userId = someone))))
            .containsExactly("owner/positionId" to "REQUIRED")
        assertThat(
            codes(ActionFields("Report", contributors = List(6) { UUID.randomUUID() })),
        ).containsExactly("contributors" to "TOO_MANY")
        assertThat(codes(ActionFields("Report", contributors = listOf(someone, someone)))).containsExactly("contributors" to "DUPLICATE")
        assertThat(codes(ActionFields("Report", owner = OwnerInput(OwnerKind.PERSON, userId = someone), contributors = listOf(someone))))
            .containsExactly("contributors" to "SAME_PERSON")
    }

    @Test
    fun `the due date, what issuing needs, the level and the condition`() {
        assertThat(ActionRules.due(today.minusDays(1), today)?.code).isEqualTo("DUE_IN_PAST")
        assertThat(ActionRules.due(today, today)).isNull()
        assertThat(ActionRules.issuable(null, null, today).map { it.field }).containsExactly("owner", "dueDate")
        assertThat(ActionRules.issuable(ActionOwner.person(someone), today, today)).isEmpty()
        assertThat(ActionRules.classification(0, 1)?.code).isEqualTo("CLASSIFICATION")
        assertThat(ActionRules.classification(2, 1)).isNull()
        assertThat(ActionRules.classification(null, 1)?.code).isEqualTo("unknown")
        assertThat(ActionRules.condition(2, 2)?.code).isEqualTo("CONDITION")
        assertThat(ActionRules.condition(-1, 2)?.code).isEqualTo("CONDITION")
        assertThat(ActionRules.condition(1, 2)).isNull()
        assertThat(ActionRules.condition(null, 0)).isNull()
    }

    @Test
    fun `the reference, the due time, the label and the windows`() {
        assertThat(ActionReferences.reference("BRC", 14, 2026)).isEqualTo("BRC A14/2026")
        val dhaka = ZoneId.of("Asia/Dhaka")
        assertThat(ActionReferences.dueAt(today, dhaka)).isEqualTo(today.plusDays(1).atStartOfDay(dhaka).toInstant())
        assertThat(ActionReferences.label(ActionSource.DECISION, "Board Risk Committee", "BRC 3/2026", "5", 1))
            .isEqualTo("BRC 3/2026, item 5 — decision, condition 2")
        assertThat(ActionReferences.label(ActionSource.DECISION, "Board Risk Committee", "BRC 3/2026", "5", 1, "BRC/2026/014"))
            .isEqualTo("BRC 3/2026, item 5 — Resolution BRC/2026/014, condition 2")
        assertThat(
            ActionReferences.label(ActionSource.ITEM, "Board Risk Committee", "BRC 3/2026", null, null),
        ).isEqualTo("BRC 3/2026, an item")
        assertThat(ActionReferences.label(ActionSource.MEETING, "Board Risk Committee", "BRC 3/2026", null, null)).isEqualTo("BRC 3/2026")
        assertThat(ActionReferences.label(ActionSource.DIRECT, "Board Risk Committee", null, null, null)).isEqualTo("Board Risk Committee")
        listOf(MeetingStatus.IN_PROGRESS, MeetingStatus.HELD, MeetingStatus.MINUTES_APPROVED).forEach { ActionRules.requireRaiseWindow(it) }
        listOf(MeetingStatus.DRAFT, MeetingStatus.SCHEDULED, MeetingStatus.CLOSED, MeetingStatus.CANCELLED).forEach { status ->
            assertThatThrownBy { ActionRules.requireRaiseWindow(status) }.isInstanceOf(RefusedException::class.java)
        }
        ActionRules.requireDraft(ActionStatus.DRAFT)
        ActionStatus.entries.filter { it != ActionStatus.DRAFT }.forEach { status ->
            assertThatThrownBy { ActionRules.requireDraft(status) }.isInstanceOf(RefusedException::class.java)
        }
        assertThat(ActionStatus.entries.filter { it.open })
            .containsExactly(ActionStatus.OPEN, ActionStatus.IN_PROGRESS, ActionStatus.DONE_PENDING)
    }
}
