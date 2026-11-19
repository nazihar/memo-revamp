package com.memo.meeting

import com.memo.forum.AgendaSectionKind
import com.memo.meeting.internal.AgendaSections
import com.memo.meeting.internal.ItemChange
import com.memo.meeting.internal.ItemInput
import com.memo.meeting.internal.ItemKind
import com.memo.meeting.internal.MyDeclarationsResponse
import com.memo.meeting.internal.SectionInput
import com.memo.testsupport.Audit
import com.memo.testsupport.Person
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * The review of an agenda for interests (test plan E04, T1; design E04 §1.5): the items to review per person and
 * revision (those whose title they see, standing items and breaks aside); the confirmation against the latest revision,
 * once, audited; `STALE_REVISION` for an earlier one; a later revision asks again only about the items it added.
 */
class DeclarationReviewIT : DeclarationFixture() {
    @Autowired lateinit var sections: AgendaSections

    @Test
    fun `a member reviews the items they see, confirms, and is asked again only about the items a later revision adds`() {
        val meeting = meeting()
        val loan = addItem(meeting, TITLE)
        val capital = addItem(meeting, "Capital plan")
        val hidden = addItem(meeting, "Acquisition of Beta Bank")
        change(
            meeting,
            hidden,
            ItemChange("Acquisition of Beta Bank", minutes = 20, restrictedTo = listOf(colleague.id), titleHidden = true),
        )
        val restricted = addItem(meeting, "Remuneration")
        change(meeting, restricted, ItemChange("Remuneration", minutes = 20, restrictedTo = listOf(colleague.id)))
        // Before any publication there is nothing to confirm.
        refused("STALE_REVISION") { asUser(secretary.account) { reviews.review(meeting.id, 1) } }
        publish(meeting)

        // The director sees the restricted item's title, not the hidden one; the standing items are not asked about.
        assertThat(toReview(director, meeting)).containsExactly(TITLE, "Capital plan", "Remuneration")
        assertThat(toReview(colleague, meeting)).containsExactly(TITLE, "Capital plan", "Acquisition of Beta Bank", "Remuneration")
        assertThat(toReview(secretary, meeting)).contains("Acquisition of Beta Bank")
        assertThat(mine(director, meeting).reviewedRevision).isNull()

        val confirmed = asUser(director.account) { reviews.review(meeting.id, 1) }
        assertThat(confirmed.reviewedRevision).isEqualTo(1)
        assertThat(confirmed.toReview).isEmpty()
        Audit.assertAudited(tenant.id, "meeting.declarations.reviewed", meeting.id.toString(), director.id.toString())
        // Confirming again is quiet: one row, one audit entry.
        asUser(director.account) { reviews.review(meeting.id, 1) }
        assertThat(
            Audit.entries(tenant.id).count { it.action == "meeting.declarations.reviewed" && it.actorId == director.id.toString() },
        ).isEqualTo(1)

        // Revision 2 adds two items: the director is asked about exactly those; revision 1 is stale now.
        addItem(meeting, "Liquidity contingency plan")
        addItem(meeting, "Recovery plan")
        publish(meeting, "Two papers added")
        assertThat(toReview(director, meeting)).containsExactly("Liquidity contingency plan", "Recovery plan")
        refused("STALE_REVISION") { asUser(director.account) { reviews.review(meeting.id, 1) } }
        refused("STALE_REVISION") { asUser(director.account) { reviews.review(meeting.id, 3) } }
        assertThat(asUser(director.account) { reviews.review(meeting.id, 2) }.toReview).isEmpty()
        // Someone who never confirmed is still asked about everything they see.
        assertThat(toReview(colleague, meeting)).hasSize(6)
        assertThat(loan).isNotEqualTo(capital)
    }

    @Test
    fun `a guest is not asked about items in a members-only section, and the reader and outsiders cannot confirm`() {
        val meeting = meeting()
        addItem(meeting, TITLE)
        val noting = asUser(secretary.account) { views.draft(meeting.id) }.sections.single { it.title == "For noting" }
        asUser(secretary.account) {
            sections.update(
                meeting.id,
                noting.id,
                version(meeting),
                SectionInput("For noting", AgendaSectionKind.STANDARD, membersOnly = true),
            )
            items.add(
                meeting.id,
                version(meeting),
                ItemInput(ItemKind.ITEM, sectionId = noting.id, title = "Board evaluation", minutes = 10),
            )
        }
        publish(meeting)

        assertThat(toReview(guest, meeting)).containsExactly(TITLE)
        assertThat(toReview(director, meeting)).containsExactly(TITLE, "Board evaluation")
        assertThat(asUser(guest.account) { reviews.review(meeting.id, 1) }.reviewedRevision).isEqualTo(1)
        assertThat(runCatching { asUser(reader.account) { reviews.review(meeting.id, 1) } }.exceptionOrNull())
            .isInstanceOf(com.memo.common.authz.ForbiddenException::class.java)
        assertThat(runCatching { asUser(outsider.account) { reviews.review(meeting.id, 1) } }.exceptionOrNull())
            .isInstanceOf(com.memo.meeting.internal.MeetingNotFoundException::class.java)

        // Once the meeting is held, the review is closed.
        startMeeting(meeting)
        endMeeting(meeting)
        refused("FROZEN") { asUser(director.account) { reviews.review(meeting.id, 1) } }
    }

    private fun mine(
        person: Person,
        meeting: com.memo.meeting.internal.MeetingRow,
    ): MyDeclarationsResponse = asUser(person.account) { reviews.mine(meeting.id) }

    private fun toReview(
        person: Person,
        meeting: com.memo.meeting.internal.MeetingRow,
    ): List<String> = mine(person, meeting).toReview.map { it.title }
}
