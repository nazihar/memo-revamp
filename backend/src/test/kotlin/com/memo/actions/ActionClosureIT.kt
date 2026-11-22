package com.memo.actions

import com.memo.actions.internal.ActionClosureController
import com.memo.actions.internal.ActionStatus
import com.memo.actions.internal.CloseAgreedRequest
import com.memo.actions.internal.MyActionsTab
import com.memo.actions.internal.VerifyDecision
import com.memo.forum.ActionVerifier
import com.memo.forum.ActionsPolicy
import com.memo.forum.ForumRole
import com.memo.testsupport.Audit
import com.memo.testsupport.MailOutbox
import com.memo.testsupport.aPassword
import com.memo.testsupport.anActiveUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * Closure verified by someone else (test plan E07 T5 `ActionClosureIT`; design E07 §1.9): done with a note and evidence
 * (`EVIDENCE_REQUIRED`), the pending date request withdrawn; the verifier pool leaves out the owner (also a secretary),
 * the holder, contributors and the recused; CHAIR mode with the vice-chair after a chair who owns it; close and send back
 * (a remark required; the owner's step again on the same date); "My actions" To verify and Contributing with their
 * counts; closing the actions the committee agreed at the meeting, with per-action results replayed by the key.
 */
class ActionClosureIT : ActionFixture() {
    override fun label() = "actclose"

    @Autowired lateinit var closureController: ActionClosureController

    private val outbox by lazy { MailOutbox(keys, json) }

    private fun done(
        id: UUID,
        note: String? = "The revised valuation was reported",
        files: List<UUID> = emptyList(),
    ) = closure.done(id, row(id).version, note, files)

    private fun verify(
        id: UUID,
        decision: VerifyDecision?,
        remark: String? = null,
    ) = closure.verify(id, row(id).version, decision, remark)

    private fun tab(
        account: com.memo.identity.UserAccount,
        tab: MyActionsTab,
    ) = asUser(account) { actionAccess.mine(tab, null, null, 50) }

    @Test
    fun `the owner who is a secretary marks it done, another secretary sends it back, then closes it, and the owner is told`() {
        val secretary2 = secondSecretary()
        val action = issued(owner = person(secretary), contributors = listOf(others[0].id.value))
        val id = action.id
        assertThat(outcome(others[0]) { done(id) }).isEqualTo("403")
        assertThat(outcome(secretary) { done(id, note = " ") }).isEqualTo("422 REQUIRED")
        assertThat(outcome(secretary) { done(id, note = "x".repeat(2001)) }).isEqualTo("422 LENGTH")
        assertThat(outcome(secretary) { done(id, files = listOf(UUID.randomUUID())) }).isEqualTo("422 unknown")

        // Marked done: the verify task goes to the secretaries' pool, not to the owner (also a secretary).
        asUser(secretary) { done(id) }
        assertThat(row(id).status).isEqualTo(ActionStatus.DONE_PENDING)
        assertThat(row(id).doneAt).isNotNull()
        assertThat(taskOf(id, secretary)).isNull()
        assertThat(taskOf(id, secretary2)).isNotNull()
        assertThat(items(secretary2, "ACTION_TO_VERIFY")).singleElement().satisfies({
            assertThat(it).contains(checkNotNull(action.reference)).doesNotContain(action.title)
        })
        assertThat(items(secretary, "ACTION_TO_VERIFY")).isEmpty()
        Audit.assertAudited(tenant.id, "actions.done", id.toString(), secretary.id.value.toString())
        assertThat(outcome(secretary) { done(id) }).isEqualTo("409 WRONG_STATE")
        assertThat(checkNotNull(seen(id, secretary2)).can.verify).isTrue()
        assertThat(checkNotNull(seen(id, secretary)).can.verify).isFalse()

        // "My actions": the verifier's To verify, the contributor's Contributing, the owner's Mine, each with its count.
        val toVerify = tab(secretary2, MyActionsTab.TO_VERIFY)
        assertThat(toVerify.items.map { it.id }).containsExactly(id)
        assertThat(toVerify.counts.toVerify).isEqualTo(1)
        assertThat(tab(others[0], MyActionsTab.CONTRIBUTING).items.map { it.id }).containsExactly(id)
        assertThat(tab(others[0], MyActionsTab.MINE).counts.contributing).isEqualTo(1)
        assertThat(tab(secretary, MyActionsTab.MINE).items.map { it.id }).containsExactly(id)
        assertThat(tab(secretary, MyActionsTab.TO_VERIFY).items).isEmpty()

        // The owner and the contributor never verify; sending back needs a remark.
        assertThat(outcome(secretary) { verify(id, VerifyDecision.CLOSE) }).isEqualTo("403 NOT_VERIFIER")
        assertThat(outcome(others[0]) { verify(id, VerifyDecision.CLOSE) }).isEqualTo("403 NOT_VERIFIER")
        assertThat(outcome(secretary2) { verify(id, VerifyDecision.SEND_BACK) }).isEqualTo("422 REQUIRED")
        assertThat(outcome(secretary2) { verify(id, null) }).isEqualTo("422 REQUIRED")

        // Sent back: the owner's step again, on the same date; the owner is told at once, without the remark.
        val remark = "The valuation report is not attached"
        asUser(secretary2) { verify(id, VerifyDecision.SEND_BACK, remark) }
        assertThat(row(id).status).isEqualTo(ActionStatus.IN_PROGRESS)
        assertThat(row(id).dueDate).isEqualTo(action.dueDate)
        assertThat(taskOf(id, secretary)).isNotNull()
        assertThat(items(secretary, "ACTION_SENT_BACK")).hasSize(1)
        val mail = outbox.all(tenant).single { it.template == "ACTION_SENT_BACK" }
        assertThat(mail.to).isEqualTo(secretary.email)
        assertThat(mail.params.values.joinToString()).doesNotContain(remark).doesNotContain(action.title)
        val sentBack = Audit.assertAudited(tenant.id, "actions.sent_back", id.toString(), secretary2.id.value.toString())
        assertThat(sentBack.canonical).doesNotContain(remark)

        // Done again and closed by the other secretary: the owner and the contributor are told.
        asUser(secretary) { done(id, note = "The report is attached now") }
        asUser(secretary2) { verify(id, VerifyDecision.CLOSE) }
        assertThat(row(id).status).isEqualTo(ActionStatus.CLOSED)
        assertThat(row(id).closedBy).isEqualTo(secretary2.id.value)
        assertThat(items(secretary, "ACTION_CLOSED")).hasSize(1)
        assertThat(items(others[0], "ACTION_CLOSED")).hasSize(1)
        assertThat(items(secretary2, "ACTION_CLOSED")).isEmpty()
        assertThat(timeline(id).take(4)).containsExactly("CLOSED", "DONE", "SENT_BACK", "DONE")
        Audit.assertAudited(tenant.id, "actions.closed", id.toString(), secretary2.id.value.toString())
        assertThat(outcome(secretary2) { verify(id, VerifyDecision.CLOSE) }).isEqualTo("409 WRONG_STATE")
        assertThat(tab(secretary2, MyActionsTab.TO_VERIFY).counts.toVerify).isZero()
    }

    @Test
    fun `evidence is required when the committee says so, and done withdraws a pending date request`() {
        actionsPolicy(ActionsPolicy(evidenceRequired = true))
        val id = issued().id
        asUser(director2) { dates.request(id, row(id).version, due.plusDays(7), "The valuer is late") }
        assertThat(outcome(director2) { done(id) }).isEqualTo("422 EVIDENCE_REQUIRED")
        assertThat(seen(id, director2)?.pendingRequest).isNotNull()

        actionsPolicy(ActionsPolicy(evidenceRequired = false))
        asUser(director2) { done(id) }
        assertThat(seen(id, director2)?.pendingRequest).isNull()
        Audit.assertAudited(tenant.id, "actions.due_request.withdrawn", id.toString())
        assertThat(timeline(id).take(2)).containsExactly("DONE", "DUE_WITHDRAWN")
    }

    @Test
    fun `the pool leaves out a recused secretary, and CHAIR mode goes to the vice-chair when the chair owns the action`() {
        val secretary2 = secondSecretary()
        val d = deciding { m, items -> declareOn(m, items.getValue(CREDIT), secretary2) }
        val id = issued(deciding = d).id
        asUser(director2) { done(id) }
        assertThat(taskOf(id, secretary)).isNotNull()
        assertThat(taskOf(id, secretary2)).isNull()

        val vice = accounts.anActiveUser(transactions, tenant, aPassword()).also { seat(it, ForumRole.VICE_CHAIR) }
        actionsPolicy(ActionsPolicy(verifiedBy = ActionVerifier.CHAIR))
        val byChair = issued(deciding = d, item = d.appetite).id
        asUser(director2) { done(byChair) }
        assertThat(taskOf(byChair, chair)).isNotNull()
        assertThat(taskOf(byChair, secretary)).isNull()
        asUser(chair) { verify(byChair, VerifyDecision.CLOSE, "Seen at the committee") }
        assertThat(row(byChair).status).isEqualTo(ActionStatus.CLOSED)

        val owned = issued(owner = person(chair), deciding = d, item = d.appetite).id
        asUser(chair) { done(owned) }
        assertThat(taskOf(owned, chair)).isNull()
        assertThat(taskOf(owned, vice)).isNotNull()
    }

    @Test
    fun `the actions the committee agreed are closed at the meeting, one by one, and a replay answers the same`() {
        val d = deciding()
        val doneId = issued(deciding = d).id
        val openId = issued(deciding = d, item = d.appetite).id
        asUser(director2) { done(doneId) }
        val key = UUID.randomUUID().toString()
        val request = CloseAgreedRequest(listOf(doneId, openId, doneId))
        val results = signedIn(secretary) { closureController.closeAgreed(d.meeting.id, key, request) }.body!!.results
        assertThat(results.map { it.actionId to it.closed }).containsExactly(doneId to true, openId to false)
        assertThat(results.single { it.actionId == openId }.reason).isEqualTo("WRONG_STATE")
        assertThat(results.single { it.actionId == doneId }.reference).isEqualTo(row(doneId).reference)
        assertThat(row(doneId).status).isEqualTo(ActionStatus.CLOSED)
        val entry = transactions.readInTenant(tenant.id) { actionLines.entries(doneId).first() }
        assertThat(entry.kind).isEqualTo("CLOSED")
        assertThat(entry.more.text).startsWith("Agreed at")
        val audited = Audit.assertAudited(tenant.id, "actions.closed", doneId.toString(), secretary.id.value.toString())
        assertThat(audited.canonical).contains(d.meeting.id.toString())

        val replay = signedIn(secretary) { closureController.closeAgreed(d.meeting.id, key, request) }.body!!.results
        assertThat(replay.map { it.actionId to it.closed }).isEqualTo(results.map { it.actionId to it.closed })
        assertThat(timeline(doneId).count { it == "CLOSED" }).isEqualTo(1)

        // The owner closes nothing, and a stranger to the meeting gets 404.
        val other = issued(deciding = d, item = d.appetite).id
        asUser(director2) { done(other) }
        val mine =
            signedIn(
                director2,
            ) { closureController.closeAgreed(d.meeting.id, UUID.randomUUID().toString(), CloseAgreedRequest(listOf(other))) }
        assertThat(
            mine.body!!
                .results
                .single()
                .reason,
        ).isEqualTo("NOT_VERIFIER")
        assertThat(outcome(user) { closure.agreedAt(d.meeting.id, null) }).isEqualTo("404")
        assertThat(
            rejectedCodes { closureController.closeAgreed(d.meeting.id, key, CloseAgreedRequest(emptyList())) },
        ).containsExactly("REQUIRED")
    }
}
