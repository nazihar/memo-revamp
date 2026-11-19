package com.memo.meeting

import com.memo.forum.ForumRole
import com.memo.meeting.internal.AgendaOrigin
import com.memo.meeting.internal.DeclarationCarryOver
import com.memo.meeting.internal.MeetingInput
import com.memo.meeting.internal.MeetingPlace
import com.memo.meeting.internal.MeetingRow
import com.memo.meeting.internal.TimesInput
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.AppRoleJdbc.long
import com.memo.testsupport.Audit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Declarations carried over (test plan E04, T3; ADR-0062, design E04 §1.4): the memo's interests reach the item when it
 * is placed and at each publication, once, with a fixed nature and never the memo's reason, for the meeting's audience
 * and officers only; a submission placed again brings the declarations of its earlier placement (RECUSED stays
 * RECUSED, a pending ruling arrives RECUSED, TAKING_PART arrives AWAITING_RULING, withdrawn ones stay behind); cloning an
 * agenda copies none.
 */
class DeclarationCarryOverIT : CarryOverFixture() {
    @Test
    fun `the memo's interests are carried at placement and at publication, once, with the fixed nature`() {
        val newcomer = person()
        val (memo, submission) = escalated("Term loan, Delta Textiles", director, outsider, newcomer)
        val meeting = meeting()
        val item = place(meeting, submission)

        // At placement: the director (a member) is recused; the outsider and the newcomer are not in the audience.
        val carried = declarationsOn(meeting, item).single()
        assertThat(carried.userId).isEqualTo(director.id)
        assertThat(carried.source to carried.sourceRef).isEqualTo(DeclarationSource.MEMO to memo)
        assertThat(carried.state to carried.intent).isEqualTo(DeclarationState.RECUSED to DeclarationIntent.LEAVE)
        assertThat(carried.how).isEqualTo(DeclarationHow.SYSTEM)
        assertThat(carried.recordedBy).isEqualTo(secretary.id)
        assertThat(carried.nature).startsWith("Interest declared on").doesNotContain("CANARY")
        val reference = get(memo.toString(), initiator.session).json()["reference"]
        assertThat(carried.nature).isEqualTo(DeclarationCarryOver.memoNature(reference?.takeUnless { it.isNull }?.asString()))
        assertThat(notified(director, "MEETING_DECLARATION_RECORDED")).isEqualTo(1)
        // Carried declarations are not told to the officers as declarations made.
        assertThat(notified(chair, "MEETING_DECLARATION_MADE")).isZero()
        val audited = Audit.assertAudited(tenant.id, "meeting.declaration.made", meeting.id.toString(), secretary.id.toString())
        assertThat(audited.canonical).contains("\"source\":\"MEMO\"").doesNotContain("CANARY")
        // A declaration carried from the memo is not written back to it.
        assertThat(memoInterests(memo).getValue(director.id).first).isEqualTo(initiator.id)

        // The newcomer joins the committee; the next publication carries theirs, and the director's only once.
        seat(newcomer, ForumRole.MEMBER)
        publish(meeting)
        assertThat(declarationsOn(meeting, item).map { it.userId }).containsExactlyInAnyOrder(director.id, newcomer.id)
        assertThat(declarationsOn(meeting, item).single { it.userId == newcomer.id }.revision).isEqualTo(1)

        // Withdrawn with a reason, a carried declaration is not carried again.
        withdraw(director, meeting, carried, "I sold the shares")
        addItem(meeting, "Any other business")
        publish(meeting, "Any other business added")
        val again = declarationsOn(meeting, item).filter { it.userId == director.id }
        assertThat(again.map { it.state }).containsExactly(DeclarationState.WITHDRAWN)
        // The memo keeps the interest (design E04 §1.4).
        assertThat(memoInterests(memo)).containsKey(director.id)
    }

    @Test
    fun `a submission placed again brings the declarations of its earlier placement`() {
        val (_, submission) = escalated("Term loan, Delta Textiles")
        val first = meeting()
        val earlierItem = place(first, submission)
        publish(first)
        val recused = declare(director, first, earlierItem)
        val ruled = declare(colleague, first, earlierItem, "My wife banks with them", DeclarationIntent.ASK_CHAIR)
        val takingPart = rule(chair, first, ruled, RulingOutcome.TAKE_PART)
        val awaiting = declare(guest, first, earlierItem, "I advised the borrower", DeclarationIntent.ASK_CHAIR)
        val clone = cloneOf(first)

        // Deferred in the queue: it leaves the first meeting's draft, is accepted again and goes on the next meeting.
        asUser(secretary.account) { triage.defer(brc, submission, submissions.get(brc, submission).version, "Next month", null) }
        asUser(secretary.account) { triage.accept(brc, submission, submissions.get(brc, submission).version, null) }
        val second = meeting(DAYS_AHEAD + 30)
        val rulingsBefore = notified(chair, "MEETING_RULING_NEEDED")
        val item = place(second, submission)

        val carried = declarationsOn(second, item).associateBy { it.userId }
        assertThat(carried.keys).containsExactlyInAnyOrder(director.id, colleague.id, guest.id)
        assertThat(carried.values.map { it.source }.toSet()).containsExactly(DeclarationSource.EARLIER_MEETING)
        assertThat(carried.getValue(director.id).let { it.state to it.sourceRef }).isEqualTo(DeclarationState.RECUSED to recused.id)
        assertThat(carried.getValue(director.id).nature).isEqualTo(NATURE)
        assertThat(carried.getValue(guest.id).let { it.state to it.intent }).isEqualTo(DeclarationState.RECUSED to DeclarationIntent.LEAVE)
        assertThat(carried.getValue(colleague.id).let { it.state to it.intent })
            .isEqualTo(DeclarationState.AWAITING_RULING to DeclarationIntent.ASK_CHAIR)
        assertThat(carried.getValue(colleague.id).sourceRef).isEqualTo(takingPart.id)
        assertThat(carried.getValue(colleague.id).ruling).isNull()
        // The new meeting's chair is asked to rule; the declarants are told.
        assertThat(notified(chair, "MEETING_RULING_NEEDED")).isEqualTo(rulingsBefore + 1)
        assertThat(notified(colleague, "MEETING_DECLARATION_RECORDED")).isEqualTo(1)
        assertThat(awaiting.state).isEqualTo(DeclarationState.AWAITING_RULING)

        // The first meeting's declarations are unchanged; the cloned agenda has none.
        assertThat(declarationsOn(first, earlierItem).map { it.state })
            .containsExactly(DeclarationState.RECUSED, DeclarationState.TAKING_PART, DeclarationState.AWAITING_RULING)
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            assertThat(c.long("select count(*) from meeting_declaration where meeting_id = ?", clone.id)).isZero()
        }
    }

    @Test
    fun `a withdrawn declaration stays behind, and a later placement carries from the latest one`() {
        val (_, submission) = escalated("Facility renewal")
        val first = meeting()
        val firstItem = place(first, submission)
        val made = record(secretary, first, firstItem, director)
        // Removed from the draft by the secretary, and placed on another meeting.
        asUser(secretary.account) { items.remove(first.id, firstItem, version(first)) }
        val second = meeting(DAYS_AHEAD + 10)
        val secondItem = place(second, submission)
        val carried = declarationsOn(second, secondItem).single()
        assertThat(carried.sourceRef).isEqualTo(made.id)
        withdraw(secretary, second, carried, "Sold the shares")

        asUser(secretary.account) { items.remove(second.id, secondItem, version(second)) }
        val third = meeting(DAYS_AHEAD + 20)
        val thirdItem = place(third, submission)
        // The latest placement had only a withdrawn declaration; the memo interest recorded from the first one carries.
        val last = declarationsOn(third, thirdItem).single()
        assertThat(last.source).isEqualTo(DeclarationSource.MEMO)
        assertThat(last.userId).isEqualTo(director.id)
    }

    /** A meeting whose agenda is copied from [source]'s. */
    private fun cloneOf(source: MeetingRow): MeetingRow {
        val day = LocalDate.now().plusDays(DAYS_AHEAD + 60)
        return asUser(secretary.account) {
            val created =
                lifecycle.create(
                    brc,
                    MeetingInput(MeetingKind.ORDINARY, MeetingPlace(null, MeetingMode.IN_PERSON, "Board room", null, null), null),
                    TimesInput(day.atTime(LocalTime.of(10, 0)), day.atTime(LocalTime.of(13, 0)), "Asia/Dhaka"),
                )
            starts.start(created.id, AgendaOrigin.MEETING, source.id)
            created
        }
    }
}
