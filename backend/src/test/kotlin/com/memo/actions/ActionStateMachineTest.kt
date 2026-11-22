package com.memo.actions

import com.memo.actions.internal.ActionStatus
import com.memo.actions.internal.CancelInput
import com.memo.actions.internal.CancelKind
import com.memo.actions.internal.OnBehalf
import com.memo.actions.internal.RagColour
import com.memo.actions.internal.UpdateInput
import com.memo.workflow.WorkflowHandOns
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * The action's life (test plan E07 T4 `ActionStateMachineTest`, §3; design E07 §1.4): every allowed transition, and every
 * forbidden one answered 409 (or 403 for a recused officer), against the services on a running meeting.
 */
class ActionStateMachineTest : ActionFixture() {
    @Autowired lateinit var handOns: WorkflowHandOns

    override fun label() = "actstates"

    private fun status(id: UUID) = row(id).status

    private fun recommend(id: UUID) = asUser(director2) { workflows.approve(checkNotNull(taskOf(id, director2)).id, "Done") }

    @Test
    fun `every allowed transition`() {
        val d = deciding()
        // DRAFT → DRAFT (edit), DRAFT → CANCELLED (discard), DRAFT → OPEN (issue).
        val draft = raise(d.meeting.id, d.credit, fields(owner = person(director2)))
        asUser(secretary) { raising.edit(draft, row(draft).version, fields(title = "Edited", owner = person(director2))) }
        assertThat(status(draft)).isEqualTo(ActionStatus.DRAFT)
        val discarded = raise(d.meeting.id, d.credit)
        asUser(secretary) { raising.discard(discarded, row(discarded).version, null) }
        assertThat(status(discarded)).isEqualTo(ActionStatus.CANCELLED)
        assertThat(issue(draft).status).isEqualTo(ActionStatus.OPEN)

        // OPEN → IN_PROGRESS by an update, and by "I have started".
        asUser(director2) { progress.update(draft, row(draft).version, UpdateInput("Started.")) }
        assertThat(status(draft)).isEqualTo(ActionStatus.IN_PROGRESS)
        val started = issued(deciding = d).id
        asUser(director2) { progress.started(started, row(started).version, OnBehalf()) }
        assertThat(status(started)).isEqualTo(ActionStatus.IN_PROGRESS)

        // OPEN → DONE_PENDING, IN_PROGRESS → DONE_PENDING (the owner's step: RECOMMEND).
        val fromOpen = issued(deciding = d).id
        recommend(fromOpen)
        assertThat(status(fromOpen)).isEqualTo(ActionStatus.DONE_PENDING)
        recommend(draft)
        assertThat(status(draft)).isEqualTo(ActionStatus.DONE_PENDING)

        // DONE_PENDING → IN_PROGRESS (sent back), DONE_PENDING → CLOSED (verified).
        val verify = checkNotNull(taskOf(draft, secretary))
        asUser(secretary) { handOns.sendBack(verify.id, verify.sendBackTargets.first().stepId, "The report is not attached") }
        assertThat(status(draft)).isEqualTo(ActionStatus.IN_PROGRESS)
        assertThat(row(draft).holderUserId).isEqualTo(director2.id.value)
        asUser(secretary) { workflows.approve(checkNotNull(taskOf(fromOpen, secretary)).id, null) }
        assertThat(status(fromOpen)).isEqualTo(ActionStatus.CLOSED)

        // OPEN, IN_PROGRESS and DONE_PENDING → CANCELLED.
        val open = issued(deciding = d).id
        val pending = issued(deciding = d).id
        recommend(pending)
        listOf(open, started, pending).forEach { id ->
            asUser(secretary) { ending.cancel(id, row(id).version, CancelInput(CancelKind.NO_LONGER_NEEDED, "Not needed")) }
            assertThat(status(id)).isEqualTo(ActionStatus.CANCELLED)
        }

        // CLOSED → IN_PROGRESS and CANCELLED → IN_PROGRESS (reopened within 90 days).
        listOf(fromOpen, open).forEach { id ->
            asUser(secretary) { ending.reopen(id, row(id).version, due, "Reopened") }
            assertThat(status(id)).isEqualTo(ActionStatus.IN_PROGRESS)
        }
    }

    @Test
    fun `every forbidden transition`() {
        val secretary2 = secondSecretary()
        val d = deciding { m, items -> declareOn(m, items.getValue(CREDIT), secretary2) }
        val draft = raise(d.meeting.id, d.credit, fields(owner = person(director2)))
        val open = issued(deciding = d).id
        val closed = issued(deciding = d).id
        recommend(closed)
        asUser(secretary) { workflows.approve(checkNotNull(taskOf(closed, secretary)).id, null) }
        val cancelled = issued(deciding = d).id
        asUser(secretary) { ending.cancel(cancelled, row(cancelled).version, CancelInput(CancelKind.DUPLICATE, "Twice")) }
        val discarded = raise(d.meeting.id, d.credit)
        asUser(secretary) { raising.discard(discarded, row(discarded).version, null) }

        // Issue anything but a draft.
        assertThat(outcome(secretary) { issuing.issue(open, row(open).version) }).isEqualTo("409 WRONG_STATE")
        // Update, re-date or mark done a DRAFT, CLOSED or CANCELLED action.
        listOf(draft, closed, cancelled).forEach { id ->
            val by = if (id == draft) secretary else director2
            assertThat(outcome(by) { progress.update(id, row(id).version, UpdateInput("x")) }).describedAs("update $id").startsWith("409")
            assertThat(outcome(secretary) { dates.changeDirectly(id, row(id).version, due.plusDays(1), "x") })
                .describedAs("re-date $id")
                .isEqualTo("409 WRONG_STATE")
            assertThat(taskOf(id, director2)).describedAs("no owner's task to mark done on $id").isNull()
        }
        // Verify anything but DONE_PENDING: no verification task exists while it is open.
        assertThat(taskOf(open, secretary)).isNull()
        // Mark done by a contributor, verify by the owner: neither holds the task.
        assertThat(taskOf(open, others[0])).isNull()
        // Reopen a discarded draft, or an open action; cancel a draft (it is discarded instead).
        assertThat(outcome(secretary) { ending.reopen(discarded, row(discarded).version, due, "x") }).isEqualTo("409 WRONG_STATE")
        assertThat(outcome(secretary) { ending.reopen(open, row(open).version, due, "x") }).isEqualTo("409 WRONG_STATE")
        assertThat(outcome(secretary) { ending.cancel(draft, row(draft).version, CancelInput(CancelKind.DUPLICATE, "x")) })
            .isEqualTo("409 WRONG_STATE")
        // "I have started" twice, and an override on a closed action.
        asUser(director2) { progress.started(open, row(open).version, OnBehalf()) }
        assertThat(outcome(director2) { progress.started(open, row(open).version, OnBehalf()) }).isEqualTo("409 WRONG_STATE")
        assertThat(
            outcome(secretary) { progress.override(closed, row(closed).version, RagColour.RED, "x", null) },
        ).isEqualTo("409 WRONG_STATE")

        // Any officer change by an officer recused from the item: 403 RECUSED where they see it (the action is theirs to
        // see as an involved contributor), 404 where they do not.
        assertThat(outcome(secretary2) { ending.cancel(open, row(open).version, CancelInput(CancelKind.DUPLICATE, "x")) }).isEqualTo("404")
        val involved = issued(deciding = d, contributors = emptyList(), owner = person(secretary2)).id
        listOf<(UUID) -> Any>(
            { id -> ending.cancel(id, row(id).version, CancelInput(CancelKind.DUPLICATE, "x")) },
            { id -> progress.override(id, row(id).version, RagColour.RED, "x", null) },
            { id -> dates.changeDirectly(id, row(id).version, due.plusDays(2), "x") },
            { id -> ownership.changeOwner(id, row(id).version, person(director2), "x") },
            { id -> raising.edit(id, row(id).version, fields(owner = person(secretary2), dueDate = row(id).dueDate), "x") },
        ).forEach { change -> assertThat(outcome(secretary2) { change(involved) }).isEqualTo("403 RECUSED") }
        // The recused owner still posts their own updates.
        asUser(secretary2) { progress.update(involved, row(involved).version, UpdateInput("Mine to do.")) }
        assertThat(status(involved)).isEqualTo(ActionStatus.IN_PROGRESS)
    }
}
