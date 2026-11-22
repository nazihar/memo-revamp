package com.memo.actions

import com.memo.actions.internal.ActionStatus
import com.memo.actions.internal.CancelInput
import com.memo.actions.internal.CancelKind
import com.memo.actions.internal.How
import com.memo.forum.ActionsPolicy
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.Audit
import com.memo.testsupport.query
import com.memo.workflow.InstanceStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Cancelling and reopening (test plan E07 T4 `CancelReopenIT`; design E07 §1.10): each kind; "risk accepted" by the chair,
 * or by a secretary for the chair with how they heard (T4's acceptance criterion: without it, 422), the chair told at
 * once; superseded by an action of the same committee; the instance cancelled and a pending request withdrawn; reopening
 * within 90 days on a new instance (the action's next run), and `TOO_LATE` after.
 */
class CancelReopenIT : ActionFixture() {
    override fun label() = "actcancel"

    private fun cancel(
        id: UUID,
        input: CancelInput,
    ) = ending.cancel(id, row(id).version, input)

    @Test
    fun `a secretary or the chair cancels with a kind and a reason, the instance stops and the owner is told`() {
        val d = deciding()
        val action = issued(deciding = d, contributors = listOf(others[0].id.value))
        val id = action.id
        asUser(director2) { dates.request(id, row(id).version, due.plusDays(5), "More time") }
        assertThat(outcome(director2) { cancel(id, CancelInput(CancelKind.NO_LONGER_NEEDED, "Mine")) }).isEqualTo("403")
        assertThat(outcome(others[1]) { cancel(id, CancelInput(CancelKind.NO_LONGER_NEEDED, "No")) }).isEqualTo("403")
        assertThat(outcome(secretary) { cancel(id, CancelInput(CancelKind.DISCARDED, "Draft")) }).isEqualTo("422 REQUIRED")
        assertThat(outcome(secretary) { cancel(id, CancelInput(CancelKind.NO_LONGER_NEEDED, null)) }).isEqualTo("422 REQUIRED")

        val cancelled = asUser(secretary) { cancel(id, CancelInput(CancelKind.NO_LONGER_NEEDED, "The loan was withdrawn")) }
        assertThat(cancelled.status to cancelled.cancelKind).isEqualTo(ActionStatus.CANCELLED to CancelKind.NO_LONGER_NEEDED)
        assertThat(cancelled.closedAt to cancelled.stepName).matches { (at, step) -> at != null && step == null }
        assertThat(asUser(secretary) { workflowQueries.viewOf(ActionsPolicy.SUBJECT, id) }?.status).isEqualTo(InstanceStatus.CANCELLED)
        assertThat(taskOf(id, director2)).isNull()
        // The pending request was withdrawn; the owner and the contributor are told.
        assertThat(seen(id, director2)?.pendingRequest).isNull()
        Audit.assertAudited(tenant.id, "actions.due_request.withdrawn", id.toString())
        listOf(director2, others[0]).forEach { assertThat(items(it, "ACTION_CANCELLED")).describedAs(it.email).hasSize(1) }
        val audited = Audit.assertAudited(tenant.id, "actions.action.cancelled", id.toString(), secretary.id.value.toString())
        assertThat(audited.canonical).contains("NO_LONGER_NEEDED", "reasonSha256").doesNotContain("withdrawn")
        assertThat(timeline(id).first()).isEqualTo("CANCELLED")
        assertThat(outcome(secretary) { cancel(id, CancelInput(CancelKind.DUPLICATE, "Again")) }).isEqualTo("409 WRONG_STATE")

        // Superseded by an issued action of the same committee, never itself.
        val older = issued(deciding = d, item = d.appetite).id
        assertThat(outcome(chair) { cancel(older, CancelInput(CancelKind.SUPERSEDED, "Replaced")) }).isEqualTo("422 SUPERSEDED_BY")
        assertThat(outcome(chair) { cancel(older, CancelInput(CancelKind.SUPERSEDED, "Replaced", supersededBy = older)) })
            .isEqualTo("422 SUPERSEDED_BY")
        val newer = issued(deciding = d, item = d.appetite).id
        assertThat(
            outcome(chair) { cancel(older, CancelInput(CancelKind.DUPLICATE, "Twice", supersededBy = newer)) },
        ).isEqualTo("422 SOURCE")
        val superseded =
            asUser(chair) { cancel(older, CancelInput(CancelKind.SUPERSEDED, "Replaced by the wider action", supersededBy = newer)) }
        assertThat(superseded.cancelKind to superseded.supersededBy).isEqualTo(CancelKind.SUPERSEDED to newer)
    }

    @Test
    fun `risk accepted is the chair's, or a secretary's on the chair's behalf saying how, and the chair is told at once`() {
        val d = deciding()
        val id = issued(deciding = d).id
        // T4: cancelling with RISK_ACCEPTED as a secretary without `how` answers 422.
        assertThat(outcome(secretary) { cancel(id, CancelInput(CancelKind.RISK_ACCEPTED, "The chair accepts the risk")) })
            .isEqualTo("422 REQUIRED")
        asUser(secretary) { cancel(id, CancelInput(CancelKind.RISK_ACCEPTED, "The chair accepts the risk", how = How.MEETING)) }
        val entry = transactions.readInTenant(tenant.id) { actionLines.entries(id).first() }
        assertThat(entry.more.onBehalfOf to entry.more.how).isEqualTo(chair.id.value to How.MEETING)
        assertThat(items(chair, "ACTION_RISK_ACCEPTED")).hasSize(1)
        val mails =
            AppRoleJdbc.inTransaction(tenant.id) { c ->
                c.query("select to_address from notification_mail_outbox where template = 'ACTION_RISK_ACCEPTED'") { it.getString(1) }
            }
        assertThat(mails).containsExactly(chair.email)
        val audited = Audit.assertAudited(tenant.id, "actions.action.cancelled", id.toString(), secretary.id.value.toString())
        assertThat(audited.canonical).contains("RISK_ACCEPTED", chair.id.value.toString(), "MEETING")

        // The chair needs no one's word.
        val theirs = issued(deciding = d, item = d.appetite).id
        assertThat(checkNotNull(seen(theirs, chair)).can.acceptRisk).isTrue()
        asUser(chair) { cancel(theirs, CancelInput(CancelKind.RISK_ACCEPTED, "Accepted as it stands")) }
        assertThat(row(theirs).cancelKind).isEqualTo(CancelKind.RISK_ACCEPTED)
        assertThat(items(chair, "ACTION_RISK_ACCEPTED")).hasSize(1)
    }

    @Test
    fun `a closed or cancelled action reopens within 90 days on a new instance, not after, and never a discarded draft`() {
        val d = deciding()
        val action = issued(deciding = d)
        val id = action.id
        val first = checkNotNull(action.workflowInstanceId)
        // Closed through the engine: the owner recommends, the secretary verifies.
        asUser(director2) { workflows.approve(checkNotNull(taskOf(id, director2)).id, "Done") }
        asUser(secretary) { workflows.approve(checkNotNull(taskOf(id, secretary)).id, null) }
        assertThat(row(id).status).isEqualTo(ActionStatus.CLOSED)

        assertThat(outcome(chair) { ending.reopen(id, row(id).version, due, "Not done after all") }).isEqualTo("403")
        assertThat(outcome(secretary) { ending.reopen(id, row(id).version, null, "Not done after all") }).isEqualTo("422 REQUIRED")
        val newDue = due.plusDays(10)
        val reopened = asUser(secretary) { ending.reopen(id, row(id).version, newDue, "The valuation was not accepted") }
        assertThat(reopened.status to reopened.reopenCount).isEqualTo(ActionStatus.IN_PROGRESS to 1)
        assertThat(reopened.dueDate to reopened.originalDueDate).isEqualTo(newDue to due)
        assertThat(reopened.closedAt to reopened.workflowInstanceId).matches { (at, instance) -> at == null && instance != first }
        assertThat(reopened.holderUserId to reopened.stepName).isEqualTo(director2.id.value to "Owner")
        assertThat(taskOf(id, director2)).isNotNull()
        assertThat(
            asUser(secretary) { workflowQueries.viewOf(ActionsPolicy.SUBJECT, id) }?.instanceId,
        ).isEqualTo(reopened.workflowInstanceId)
        val entry = transactions.readInTenant(tenant.id) { actionLines.entries(id).first() }
        assertThat(entry.kind to entry.details["previousInstanceId"]).isEqualTo("REOPENED" to first.toString())
        Audit.assertAudited(tenant.id, "actions.action.reopened", id.toString(), secretary.id.value.toString())
        assertThat(items(director2, "ACTION_REOPENED")).hasSize(1)
        // The reopened action runs to its end again.
        asUser(director2) { workflows.approve(checkNotNull(taskOf(id, director2)).id, "Done again") }
        assertThat(row(id).status).isEqualTo(ActionStatus.DONE_PENDING)

        // Cancelled 91 days ago: too late; raise a new action instead.
        val old = issued(deciding = d, item = d.appetite).id
        asUser(secretary) { cancel(old, CancelInput(CancelKind.NO_LONGER_NEEDED, "Dropped")) }
        alter(old, "closed_at = closed_at - interval '91 days'")
        assertThat(outcome(secretary) { ending.reopen(old, row(old).version, due, "Back") }).isEqualTo("409 TOO_LATE")

        // A discarded draft is never reopened.
        val draft = raise(d.meeting.id, d.appetite)
        asUser(secretary) { raising.discard(draft, row(draft).version, null) }
        assertThat(outcome(secretary) { ending.reopen(draft, row(draft).version, due, "Back") }).isEqualTo("409 WRONG_STATE")
    }
}
