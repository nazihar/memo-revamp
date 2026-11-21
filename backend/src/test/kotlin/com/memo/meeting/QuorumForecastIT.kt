package com.memo.meeting

import com.memo.common.authz.ForbiddenException
import com.memo.forum.UnmetKind
import com.memo.identity.UserAccount
import com.memo.meeting.internal.DeclarationRegister
import com.memo.meeting.internal.Declarations
import com.memo.meeting.internal.DeclareInput
import com.memo.meeting.internal.MeetingRow
import com.memo.meeting.internal.quorum.QuorumReads
import com.memo.meeting.internal.rsvp.RsvpAnswer
import com.memo.meeting.internal.rsvp.RsvpInput
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * The quorum forecast from the replies (test plan E05 T5 `QuorumForecastIT`, ADR-0064, design E05 §1.7): the meeting and
 * every published item from the answers (a stale answer counts as no answer), the alternates asked to attend, and the
 * recusals; officers only, the counts only for an item the officer is recused from; with everyone answering yes it agrees
 * with E04's planning warning.
 */
class QuorumForecastIT : AttendanceTestSupport() {
    @Autowired lateinit var quorumReads: QuorumReads

    @Autowired lateinit var declarations: Declarations

    @Autowired lateinit var declarationRegister: DeclarationRegister

    override fun label() = "forecast"

    @Test
    fun `the forecast counts yes answers and asked alternates, leaves out recused members, and stale answers`() {
        val (meeting, items) = published()
        val credit = items.getValue("Credit proposal")
        val appetite = items.getValue("Risk appetite")
        declare(listOf(director2, others[0]), meeting, credit)
        declare(listOf(chair), meeting, appetite)
        answer(chair, meeting, RsvpInput(RsvpAnswer.IN_PERSON))
        answer(director, meeting, RsvpInput(RsvpAnswer.APOLOGIES, alternateAttends = true))
        answer(director2, meeting, RsvpInput(RsvpAnswer.IN_PERSON))
        answer(others[0], meeting, RsvpInput(RsvpAnswer.IN_PERSON))
        answer(others[1], meeting, RsvpInput(RsvpAnswer.REMOTE))
        answer(others[2], meeting, RsvpInput(RsvpAnswer.APOLOGIES))

        val forecast = asUser(secretary) { quorumReads.forecast(meeting.id) }
        // The chair, the director's alternate, director2, others[0] and others[1] online: 5 of 4 needed.
        assertThat(forecast.meeting.quorate to forecast.meeting.present).isEqualTo(true to 5)
        // Every published item, the template's standing ones included, in agenda order.
        assertThat(forecast.items.map { it.itemId }).contains(credit, appetite)
        val ofCredit = forecast.items.first { it.itemId == credit }
        assertThat(Triple(ofCredit.quorum.quorate, ofCredit.quorum.present, ofCredit.declared)).isEqualTo(Triple(false, 3, 2))
        assertThat(ofCredit.quorum.unmet.map { it.kind }).containsExactly(UnmetKind.COUNT)
        val ofAppetite = forecast.items.first { it.itemId == appetite }
        assertThat(ofAppetite.quorum.unmet.map { it.kind }).describedAs("the chair is recused").contains(UnmetKind.ROLE)
        assertThat(ofAppetite.declared to ofAppetite.viewerRecused).isEqualTo(1 to false)

        // The chair is recused from "Risk appetite": the counts only.
        val ofChair = asUser(chair) { quorumReads.forecast(meeting.id) }.items.first { it.itemId == appetite }
        assertThat(ofChair.declared to ofChair.viewerRecused).isEqualTo(null to true)
        // Officers only.
        listOf(director, reader).forEach { account ->
            assertThat(runCatching { asUser(account) { quorumReads.forecast(meeting.id) } }.exceptionOrNull())
                .isInstanceOf(ForbiddenException::class.java)
        }

        // The meeting moves: every answer is for the old time and counts as none; the alternate is still asked.
        val current = meeting(meeting.id)
        asUser(secretary) { lifecycle.reschedule(current.id, current.version, times(today.plusDays(20)), "Moved") }
        val stale = asUser(secretary) { quorumReads.forecast(meeting.id) }
        assertThat(stale.meeting.quorate to stale.meeting.present).isEqualTo(false to 1)
        answer(others[3], meeting(meeting.id), RsvpInput(RsvpAnswer.IN_PERSON))
        assertThat(asUser(secretary) { quorumReads.forecast(meeting.id) }.meeting.present).isEqualTo(2)
    }

    @Test
    fun `with every member saying yes, the forecast agrees with E04's planning warning`() {
        val (meeting, items) = published()
        val credit = items.getValue("Credit proposal")
        declare(listOf(director2) + others.take(3), meeting, credit)
        voting.forEach { answer(it, meeting, RsvpInput(RsvpAnswer.IN_PERSON)) }

        val forecast = asUser(secretary) { quorumReads.forecast(meeting.id) }.items.first { it.itemId == credit }
        val register = asUser(secretary) { declarationRegister.meeting(meeting.id) }
        val warning = checkNotNull(register.items.single { it.itemId == credit }.quorumWarning)
        assertThat(listOf(forecast.declared, forecast.quorum.basis, forecast.quorum.needed, forecast.quorum.present))
            .containsExactly(warning.declared, warning.basis, warning.needed, warning.available)
        assertThat(forecast.quorum.unmet).isEqualTo(warning.unmet)
        assertThat(forecast.quorum.quorate).isFalse()
    }

    private fun declare(
        people: List<UserAccount>,
        meeting: MeetingRow,
        item: UUID,
    ) {
        people.forEach {
            asUser(
                it,
            ) { declarations.declare(meeting.id, item, DeclareInput("A family member is a director of the borrower")) }
        }
    }
}
