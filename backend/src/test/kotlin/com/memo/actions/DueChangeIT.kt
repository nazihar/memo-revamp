package com.memo.actions

import com.memo.actions.internal.ActionReferences
import com.memo.actions.internal.DueDecision
import com.memo.forum.ActionsPolicy
import com.memo.forum.DueChange
import com.memo.testsupport.Audit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.ZoneOffset

/**
 * Due-date changes (test plan E07 T4 `DueChangeIT`; design E07 §1.7): each `dueChange` mode, one pending request, its
 * answer once, the request withdrawn by a direct change, the original date kept and the revisions counted, and the owner's
 * timers re-planned with the date (`Workflows.redue`).
 */
class DueChangeIT : ActionFixture() {
    override fun label() = "actdue"

    @Test
    fun `the owner asks, the action keeps its date until a secretary agrees, then it is revised once and the reminders move`() {
        val action = issued()
        val id = action.id
        val instance = checkNotNull(action.workflowInstanceId)
        val newDate = due.plusDays(21)
        val before = pendingTimers(instance)
        assertThat(before.map { it.first }).contains("DUE")
        assertThat(before.single { it.first == "DUE" }.second).isEqualTo(ActionReferences.dueAt(due, ZoneOffset.UTC))

        // T4: given SECRETARY_AGREES, the owner asks for a new date with a reason; the action keeps its date.
        asUser(director2) { dates.request(id, row(id).version, newDate, "The valuer needs three more weeks") }
        val asked = checkNotNull(seen(id, director2))
        assertThat(asked.dueDate to asked.pendingRequest?.newDate).isEqualTo(due to newDate)
        assertThat(asked.dueChange).isEqualTo(DueChange.SECRETARY_AGREES)
        assertThat(items(secretary, "ACTION_DUE_REQUESTED")).singleElement().satisfies({
            assertThat(it).contains(checkNotNull(action.reference), newDate.toString()).doesNotContain("valuer")
        })
        Audit.assertAudited(tenant.id, "actions.due_request.made", id.toString(), director2.id.value.toString())
        // One pending request at a time.
        assertThat(outcome(director2) { dates.request(id, row(id).version, newDate.plusDays(1), "Again") }).isEqualTo("409 PENDING_REQUEST")
        // Who decides: a secretary not recused; not the owner, not a member.
        val request = checkNotNull(asked.pendingRequest).id
        assertThat(outcome(director2) { dates.decide(id, request, row(id).version, DueDecision.AGREE, null) }).isEqualTo("403")
        assertThat(outcome(others[0]) { dates.decide(id, request, row(id).version, DueDecision.AGREE, null) }).isEqualTo("403")
        assertThat(outcome(secretary) { dates.decide(id, request, row(id).version, null, null) }).isEqualTo("422 REQUIRED")

        // Then it shows "Due {new} (originally {old}, revised once)", and the owner's reminders move with it.
        asUser(secretary) { dates.decide(id, request, row(id).version, DueDecision.AGREE, "Agreed with the chair") }
        val agreed = checkNotNull(seen(id, director2))
        assertThat(Triple(agreed.dueDate, agreed.originalDueDate, agreed.dueRevisions)).isEqualTo(Triple(newDate, due, 1))
        assertThat(agreed.pendingRequest).isNull()
        val after = pendingTimers(instance)
        assertThat(after.single { it.first == "DUE" }.second).isEqualTo(ActionReferences.dueAt(newDate, ZoneOffset.UTC))
        assertThat(
            after.filter { it.first == "REMIND_BEFORE" }.minOf { it.second },
        ).isAfter(before.filter { it.first == "REMIND_BEFORE" }.minOf { it.second })
        assertThat(after).isNotEqualTo(before)
        assertThat(row(id).amberFrom).isAfter(action.amberFrom)
        assertThat(timeline(id).take(3)).containsExactly("DUE_CHANGED", "DUE_AGREED", "DUE_REQUESTED")
        Audit.assertAudited(tenant.id, "actions.due_request.agreed", id.toString(), secretary.id.value.toString())
        val changed = Audit.assertAudited(tenant.id, "actions.due.changed", id.toString(), secretary.id.value.toString())
        assertThat(changed.canonical).contains(newDate.toString(), due.toString()).doesNotContain("valuer")
        assertThat(items(director2, "ACTION_DUE_DECIDED")).singleElement().satisfies({ assertThat(it).contains("AGREED") })
        assertThat(items(director2, "ACTION_DUE_CHANGED")).isEmpty()
        assertThat(
            outcome(secretary) { dates.decide(id, request, row(id).version, DueDecision.DECLINE, null) },
        ).isEqualTo("409 WRONG_STATE")
    }

    @Test
    fun `a request declined keeps the date, a secretary's direct change withdraws a pending one, and the owner may not change it`() {
        val id = issued(contributors = listOf(others[0].id.value)).id
        asUser(director2) { dates.request(id, row(id).version, due.plusDays(7), "Waiting for data") }
        val request = checkNotNull(seen(id, director2)?.pendingRequest).id
        asUser(secretary) { dates.decide(id, request, row(id).version, DueDecision.DECLINE, "Please keep the date") }
        assertThat(row(id).dueDate to row(id).dueRevisions).isEqualTo(due to 0)
        Audit.assertAudited(tenant.id, "actions.due_request.declined", id.toString())
        assertThat(items(director2, "ACTION_DUE_DECIDED")).singleElement().satisfies({ assertThat(it).contains("DECLINED") })

        // The owner of a committee that agrees dates asks; they do not change it.
        assertThat(outcome(director2) { dates.changeDirectly(id, row(id).version, due.plusDays(3), "Late") }).isEqualTo("403 ASK_FOR_IT")
        asUser(director2) { dates.request(id, row(id).version, due.plusDays(9), "Waiting for data again") }
        asUser(secretary) { dates.changeDirectly(id, row(id).version, due.plusDays(14), "Moved at the meeting") }
        assertThat(seen(id, director2)?.pendingRequest).isNull()
        assertThat(row(id).dueDate to row(id).dueRevisions).isEqualTo(due.plusDays(14) to 1)
        Audit.assertAudited(tenant.id, "actions.due_request.withdrawn", id.toString(), secretary.id.value.toString())
        // The owner and the contributors are told; not the secretary who changed it.
        assertThat(items(director2, "ACTION_DUE_CHANGED")).hasSize(1)
        assertThat(items(others[0], "ACTION_DUE_CHANGED")).hasSize(1)
        assertThat(items(secretary, "ACTION_DUE_CHANGED")).isEmpty()

        // The checks of a new date.
        assertThat(outcome(secretary) { dates.changeDirectly(id, row(id).version, due.minusDays(60), "Past") }).isEqualTo("422 DUE_IN_PAST")
        assertThat(outcome(secretary) { dates.changeDirectly(id, row(id).version, due.plusDays(14), "Same") }).isEqualTo("422 SAME_DATE")
        assertThat(outcome(secretary) { dates.changeDirectly(id, row(id).version, due.plusDays(15), null) }).isEqualTo("422 REQUIRED")
        assertThat(outcome(others[0]) { dates.changeDirectly(id, row(id).version, due.plusDays(15), "Mine") }).isEqualTo("403")
    }

    @Test
    fun `under CHAIR_AGREES the chair decides, and under OWNER_WITH_REASON the owner moves the date and the secretaries are told`() {
        actionsPolicy(ActionsPolicy(dueChange = DueChange.CHAIR_AGREES))
        val d = deciding()
        val id = issued(deciding = d).id
        asUser(director2) { dates.request(id, row(id).version, due.plusDays(10), "Board pack moved") }
        val request = checkNotNull(seen(id, director2)?.pendingRequest).id
        assertThat(items(chair, "ACTION_DUE_REQUESTED")).hasSize(1)
        assertThat(outcome(secretary) { dates.decide(id, request, row(id).version, DueDecision.AGREE, null) }).isEqualTo("403")
        asUser(chair) { dates.decide(id, request, row(id).version, DueDecision.AGREE, null) }
        assertThat(row(id).dueDate).isEqualTo(due.plusDays(10))

        actionsPolicy(ActionsPolicy(dueChange = DueChange.OWNER_WITH_REASON))
        val own = issued(deciding = d, item = d.appetite).id
        assertThat(checkNotNull(seen(own, director2)).can.changeDue).isTrue()
        assertThat(outcome(director2) { dates.request(own, row(own).version, due.plusDays(5), "Late") }).isEqualTo("409 OWNER_CHANGES")
        asUser(director2) { dates.changeDirectly(own, row(own).version, due.plusDays(5), "The data came late") }
        assertThat(row(own).dueDate to row(own).dueRevisions).isEqualTo(due.plusDays(5) to 1)
        assertThat(items(secretary, "ACTION_DUE_CHANGED")).hasSize(1)
        Audit.assertAudited(tenant.id, "actions.due.changed", own.toString(), director2.id.value.toString())
    }
}
