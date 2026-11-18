package com.memo.meeting.internal

import com.memo.common.web.RefusedException
import com.memo.forum.AgendaLimits
import com.memo.forum.AgendaSectionKind
import com.memo.forum.SubmissionPurpose
import com.memo.meeting.MeetingStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** The agenda's pure rules (test plan E03, T2): `ONE_LEVEL`, breaks, caps, positions, totals and overrun, numbering. */
class AgendaRulesTest {
    private val meeting = UUID.randomUUID()
    private val opening = section(0, "Opening")
    private val business = section(1, "For decision")
    private val start: Instant = Instant.parse("2026-12-12T04:00:00Z")

    @Test
    fun `items are numbered through the agenda, breaks and items waiting for the chair skipped, sub-items n dot m`() {
        val welcome = item(opening, 0, minutes = 5)
        val minutes = item(opening, 1, minutes = 5)
        val pause = item(business, 0, kind = ItemKind.BREAK, minutes = 15)
        val credit = item(business, 1, minutes = 30)
        val waiting = item(business, 2, minutes = 20, late = Lateness.AWAITING_CHAIR)
        val limits = item(business, 3, minutes = 10)
        val subA = item(business, 0, parent = credit)
        val subB = item(business, 1, parent = credit)
        val subWaiting = item(business, 2, parent = credit, late = Lateness.AWAITING_CHAIR)
        val all = listOf(limits, subB, credit, welcome, waiting, pause, subA, minutes, subWaiting)

        val numbers = AgendaRules.numbers(listOf(business, opening), all)

        assertThat(numbers).containsExactlyInAnyOrderEntriesOf(
            mapOf(welcome.id to "1", minutes.id to "2", credit.id to "3", subA.id to "3.1", subB.id to "3.2", limits.id to "4"),
        )
    }

    @Test
    fun `times add up from the meeting's start, sub-items and waiting items take none, and an overrun is flagged`() {
        val welcome = item(opening, 0, minutes = 10)
        val pause = item(business, 0, kind = ItemKind.BREAK, minutes = 15)
        val credit = item(business, 1, minutes = 60)
        val sub = item(business, 0, parent = credit)
        val waiting = item(business, 2, minutes = 45, late = Lateness.AWAITING_CHAIR)
        val untimed = item(business, 3)
        val all = listOf(welcome, pause, credit, sub, waiting, untimed)

        val fits = AgendaRules.times(start, start.plusSeconds(MINUTE * 90), listOf(opening, business), all)
        assertThat(fits.starts).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                welcome.id to start,
                pause.id to start.plusSeconds(MINUTE * 10),
                credit.id to start.plusSeconds(MINUTE * 25),
                untimed.id to start.plusSeconds(MINUTE * 85),
            ),
        )
        assertThat(fits.sectionMinutes).containsExactlyInAnyOrderEntriesOf(mapOf(opening.id to 10, business.id to 75))
        assertThat(fits.totalMinutes).isEqualTo(85)
        assertThat(fits.endsAt).isEqualTo(start.plusSeconds(MINUTE * 85))
        assertThat(fits.overrun).isFalse()

        val tight = AgendaRules.times(start, start.plusSeconds(MINUTE * 80), listOf(opening, business), all)
        assertThat(tight.overrun).isTrue()
    }

    @Test
    fun `a move puts the row at its position, clamped, and keeps the rest in order`() {
        val ids = List(4) { UUID.randomUUID() }
        val (a, b, c) = ids
        val d = ids[3]
        assertThat(AgendaRules.reorder(ids, d, 0)).containsExactly(d, a, b, c)
        assertThat(AgendaRules.reorder(ids, a, 2)).containsExactly(b, c, a, d)
        assertThat(AgendaRules.reorder(ids, b, 99)).containsExactly(a, c, d, b)
        assertThat(AgendaRules.reorder(ids, c, -3)).containsExactly(c, a, b, d)
        assertThat(AgendaRules.reorder(ids, b, null)).containsExactly(a, c, d, b)
        // A new row (not yet among the ids) is inserted.
        val e = UUID.randomUUID()
        assertThat(AgendaRules.reorder(ids + e, e, 1)).containsExactly(a, e, b, c, d)
    }

    @Test
    fun `items have one level of sub-items, and breaks are never sub-items nor have them`() {
        val parent = item(business, 0)
        val child = item(business, 0, parent = parent)
        val other = item(business, 1)
        val pause = item(business, 2, kind = ItemKind.BREAK)

        assertThatCode { AgendaRules.requireOneLevel(other, parent, hasChildren = false) }.doesNotThrowAnyException()
        assertThatCode { AgendaRules.requireOneLevel(child, null, hasChildren = false) }.doesNotThrowAnyException()
        oneLevel { AgendaRules.requireOneLevel(other, child, hasChildren = false) }
        oneLevel { AgendaRules.requireOneLevel(parent, other, hasChildren = true) }
        oneLevel { AgendaRules.requireOneLevel(pause, parent, hasChildren = false) }
        oneLevel { AgendaRules.requireOneLevel(other, pause, hasChildren = false) }
        oneLevel { AgendaRules.requireOneLevel(parent, parent, hasChildren = false) }
    }

    @Test
    fun `the caps refuse a section or item more than an agenda holds`() {
        assertThat(AgendaRules.capProblem(AgendaLimits.MAX_SECTIONS, AgendaLimits.MAX_ITEMS)).isNull()
        assertThat(AgendaRules.capProblem(AgendaLimits.MAX_SECTIONS + 1, 0)?.code).isEqualTo("TOO_MANY")
        assertThat(AgendaRules.capProblem(1, AgendaLimits.MAX_ITEMS + 1)?.code).isEqualTo("TOO_MANY")
    }

    @Test
    fun `the draft changes freely before the meeting, takes only tabled additions while in progress, and freezes after`() {
        for (status in listOf(MeetingStatus.DRAFT, MeetingStatus.SCHEDULED)) {
            assertThat(AgendaChanges.of(status)).isEqualTo(AgendaChanges.ANY)
            assertThatCode { AgendaRules.requireChange(status) }.doesNotThrowAnyException()
        }
        assertThat(AgendaChanges.of(MeetingStatus.IN_PROGRESS)).isEqualTo(AgendaChanges.TABLED_ONLY)
        assertThatCode { AgendaRules.requireChange(MeetingStatus.IN_PROGRESS, tabled = true) }.doesNotThrowAnyException()
        frozen { AgendaRules.requireChange(MeetingStatus.IN_PROGRESS) }
        for (status in listOf(MeetingStatus.HELD, MeetingStatus.CANCELLED, MeetingStatus.MINUTES_APPROVED, MeetingStatus.CLOSED)) {
            assertThat(AgendaChanges.of(status)).isEqualTo(AgendaChanges.NONE)
            frozen { AgendaRules.requireChange(status, tabled = true) }
        }
    }

    @Test
    fun `titles are trimmed and between 1 and the maximum`() {
        assertThat(AgendaRules.title("title", "  Credit  ", 10)).isEqualTo("Credit" to null)
        assertThat(AgendaRules.title("title", " ", 10).second?.code).isEqualTo("LENGTH")
        assertThat(AgendaRules.title("title", "x".repeat(11), 10).second?.code).isEqualTo("LENGTH")
        assertThat(AgendaRules.optional("description", " ", 10)).isEqualTo(null to null)
        assertThat(AgendaRules.optional("description", "x".repeat(11), 10).second?.code).isEqualTo("LENGTH")
    }

    private fun oneLevel(block: () -> Unit) =
        assertThatThrownBy(block).isInstanceOfSatisfying(RefusedException::class.java) { assertThat(it.reason).isEqualTo("ONE_LEVEL") }

    private fun frozen(block: () -> Unit) =
        assertThatThrownBy(block).isInstanceOfSatisfying(RefusedException::class.java) { assertThat(it.reason).isEqualTo("FROZEN") }

    private fun section(
        position: Int,
        title: String,
    ) = SectionRow(
        UUID.randomUUID(),
        meeting,
        position,
        title,
        AgendaSectionKind.STANDARD,
        membersOnly = false,
        receivesSubmissions = false,
    )

    private fun item(
        section: SectionRow,
        position: Int,
        kind: ItemKind = ItemKind.ITEM,
        minutes: Int? = null,
        parent: ItemRow? = null,
        late: Lateness = Lateness.NONE,
    ) = ItemRow(
        id = UUID.randomUUID(),
        meetingId = meeting,
        sectionId = section.id,
        parentId = parent?.id,
        position = position,
        kind = kind,
        standingKind = null,
        submissionId = null,
        memoId = null,
        title = "Item",
        description = null,
        purpose = if (kind == ItemKind.BREAK) null else SubmissionPurpose.DECISION,
        resolutionSought = null,
        minutes = minutes,
        presenters = emptyList(),
        restrictedTo = null,
        titleHidden = false,
        classificationId = UUID.randomUUID(),
        late = late,
        addedAfterRevision = 0,
    )

    private companion object {
        const val MINUTE = 60L
    }
}
