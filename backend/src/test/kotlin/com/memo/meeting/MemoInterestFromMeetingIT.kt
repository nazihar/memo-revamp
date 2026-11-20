package com.memo.meeting

import com.memo.forum.ForumRole
import com.memo.meeting.internal.DeclarationRegister
import com.memo.meeting.internal.RecusedMemoReaders
import com.memo.memo.MemoInterestLinks
import com.memo.testsupport.Audit
import com.memo.workflow.ForumOutcome
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * The memo side of declarations (test plan E04, T3; ADR-0062, design E04 §1.4): a declaration on a submission item
 * records an interest on the memo with a fixed reason, never the nature, so the workflow never offers the declarant a
 * later step of that memo (SOD-4); withdrawing the declaration leaves the interest; recusal leaves the memo's own access
 * alone, and the people recused who still read the memo are found for the meeting register.
 */
class MemoInterestFromMeetingIT : CarryOverFixture() {
    @Autowired lateinit var links: MemoInterestLinks

    @Autowired lateinit var memoReaders: RecusedMemoReaders

    @Autowired lateinit var register: DeclarationRegister

    @Test
    fun `a declaration on a submission item records a memo interest, and the workflow passes the declarant by`() {
        val (memo, submission) = escalated("Term loan, Delta Textiles")
        val meeting = meeting()
        val item = place(meeting, submission)
        publish(meeting)
        val label = checkNotNull(asUser(secretary.account) { reads.get(meeting.id) }.label)

        val declared = declare(colleague, meeting, item)
        val interest = memoInterests(memo).getValue(colleague.id)
        assertThat(interest.first).isEqualTo(colleague.id)
        assertThat(interest.second).startsWith("Declared an interest at $label, item ").doesNotContain(NATURE)
        val audited = Audit.assertAudited(tenant.id, "memo.interest.declared", memo.toString(), colleague.id.toString())
        assertThat(audited.canonical).contains("\"via\":\"meeting\"", declared.id.toString()).doesNotContain(NATURE)
        assertThat(asUser(admin) { links.of(memo) }).containsExactly(colleague.id)

        // Recorded by the secretary for the director: the secretary is the one who entered it.
        record(secretary, meeting, item, director)
        assertThat(memoInterests(memo).getValue(director.id).first).isEqualTo(secretary.id)

        // Withdrawn, the declaration leaves the memo interest; declaring again changes nothing on the memo.
        val withdrawn = withdraw(colleague, meeting, declared, "Declared in error")
        assertThat(withdrawn.state).isEqualTo(DeclarationState.WITHDRAWN)
        assertThat(memoInterests(memo)).containsKey(colleague.id)
        val count = Audit.entries(tenant.id).count { it.action == "memo.interest.declared" && it.objectId == memo.toString() }
        declare(colleague, meeting, item, "Still my wife's bank", DeclarationIntent.ASK_CHAIR)
        assertThat(Audit.entries(tenant.id).count { it.action == "memo.interest.declared" && it.objectId == memo.toString() })
            .isEqualTo(count)

        // The committee decides: the final step, meant for the colleague, goes to the deputy (SOD-4).
        asUser(secretary.account) { submissions.decide(submission, ForumOutcome.APPROVED, "Approved") }
        val view = get(memo.toString(), initiator.session).json()
        val holders = (0 until view["holders"].size()).map { view["holders"][it]["id"].asString() }
        assertThat(holders).containsExactly(deputy.id.toString())
    }

    @Test
    fun `recusal leaves the memo's own access, and the register finds those who still read it`() {
        seat(reviewer, ForumRole.MEMBER)
        val (memo, submission) = escalated("Facility renewal")
        val meeting = meeting()
        val item = place(meeting, submission)
        publish(meeting)

        declare(reviewer, meeting, item)
        declare(colleague, meeting, item)
        // The reviewer approved the memo and reads it still; the colleague never did.
        assertThat(get(memo.toString(), reviewer.session)).hasStatusOk()
        assertThat(asUser(admin) { links.readersAmong(memo, setOf(reviewer.id, colleague.id, outsider.id)) })
            .containsExactly(reviewer.id)
        assertThat(asUser(admin) { memoReaders.of(meeting.id) }).isEqualTo(mapOf(item to setOf(reviewer.id)))
        // The meeting register flags the reviewer on the item, and not the colleague (design E04 §1.4, flow T1).
        val flagged = asUser(secretary.account) { register.meeting(meeting.id) }.items.single { it.itemId == item }
        assertThat(flagged.alsoReadsMemo.map { it.id }).containsExactly(reviewer.id)
        assertThat(flagged.declarations.map { it.user.id }).containsExactlyInAnyOrder(reviewer.id, colleague.id)

        // Once the reviewer's declaration is withdrawn, they are no longer recused, so no longer flagged.
        val own = declarationsOn(meeting, item).single { it.userId == reviewer.id }
        withdraw(reviewer, meeting, own, "Not a conflict after all")
        assertThat(asUser(admin) { memoReaders.of(meeting.id) }).isEmpty()
    }
}
