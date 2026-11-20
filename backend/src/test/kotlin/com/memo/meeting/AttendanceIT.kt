package com.memo.meeting

import com.memo.common.authz.ForbiddenException
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.web.PreconditionRequiredException
import com.memo.common.web.RefusedException
import com.memo.forum.ForumRole
import com.memo.forum.UnmetKind
import com.memo.forum.internal.MemberInput
import com.memo.meeting.internal.MeetingNotFoundException
import com.memo.meeting.internal.MeetingRejectedException
import com.memo.meeting.internal.rsvp.RsvpAnswer
import com.memo.meeting.internal.rsvp.RsvpInput
import com.memo.testsupport.Audit
import com.memo.testsupport.aPassword
import com.memo.testsupport.anActiveUser
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The attendance register (test plan E05 T4 `AttendanceIT`, ADR-0064, design E05 §1.6): opened at `start` with the
 * replies' apologies; "everyone who said yes is here"; arrivals, modes, departures and returns with times and items;
 * joined late and left early; the item in progress; voiding and corrections after the meeting; FROZEN; alternates and
 * `ALREADY_COUNTED`; who sees what; `MeetingAttendance` for E06; audit.
 */
class AttendanceIT : AttendanceTestSupport() {
    override fun label() = "attendance"

    @Test
    fun `the register opens with the replies' apologies, everyone who said yes arrives at once, and the quorum reads 5 of 7`() {
        val (meeting, _) =
            started { scheduled ->
                answer(director2, scheduled, RsvpInput(RsvpAnswer.APOLOGIES))
                answer(others[3], scheduled, RsvpInput(RsvpAnswer.APOLOGIES))
                (listOf(chair, director) + others.take(3)).forEach { answer(it, scheduled, RsvpInput(RsvpAnswer.IN_PERSON)) }
            }
        val opened = asUser(secretary) { attendanceReads.register(meeting.id) }
        val people = checkNotNull(opened.people).associateBy { it.userId }
        assertThat(people).describedAs("seven voting members and the secretary").hasSize(8)
        assertThat(people.getValue(director2.id.value).state).isEqualTo(AttendanceState.APOLOGIES)
        assertThat(people.getValue(chair.id.value).state).isEqualTo(AttendanceState.EXPECTED)
        assertThat(people.getValue(chair.id.value).role).isEqualTo(ForumRole.CHAIR)
        val rsvp = checkNotNull(opened.events).filter { it.kind == AttendanceEventKind.APOLOGIES }
        assertThat(rsvp.map { it.source }.toSet()).containsExactly(AttendanceSource.RSVP)
        assertThat(rsvp.map { it.userId }).containsExactlyInAnyOrder(director2.id.value, others[3].id.value)
        assertThat(opened.quorum?.quorate).isFalse()
        assertThat(opened.canRecord).isTrue()

        val yes = listOf(chair, director) + others.take(3)
        arrive(meeting, yes)
        val after = asUser(secretary) { attendanceReads.register(meeting.id) }
        val quorum = checkNotNull(after.quorum)
        assertThat(Triple(quorum.quorate, quorum.present, quorum.needed)).isEqualTo(Triple(true, 5, 4))
        assertThat(quorum.basis).isEqualTo(7)
        assertThat(checkNotNull(after.events).count { it.kind == AttendanceEventKind.ARRIVED }).isEqualTo(5)
        assertThat(after.counts.inPerson to after.counts.apologies).isEqualTo(5 to 2)
        assertThat(
            checkNotNull(after.people).filter { it.counted }.map { it.userId },
        ).containsExactlyInAnyOrderElementsOf(yes.map { it.id.value })
        Audit.assertAudited(tenant.id, "meeting.attendance.recorded", meeting.id.toString(), secretary.id.value.toString())
        val reached = Audit.assertAudited(tenant.id, "meeting.quorum.reached", meeting.id.toString())
        assertThat(reached.canonical).contains("\"present\":5").contains("\"needed\":4")

        // A second arrival is refused; If-Match is required and must be current.
        assertThat(refused { record(meeting, chair, AttendanceEventKind.ARRIVED, AttendanceMode.IN_PERSON) }).isEqualTo("WRONG_STATE")
        assertThatThrownBy { record(meeting, chair, AttendanceEventKind.LEFT, version = 0) }
            .isInstanceOf(ConcurrentModificationException::class.java)
        assertThatThrownBy { record(meeting, chair, AttendanceEventKind.LEFT, version = null) }
            .isInstanceOf(PreconditionRequiredException::class.java)
        assertThat(refused { arrive(meeting, listOf(director, others[3])) }).describedAs("all or nothing").isEqualTo("WRONG_STATE")
        assertThat(person(meeting, others[3]).state).isEqualTo(AttendanceState.APOLOGIES)
    }

    @Test
    fun `arrivals, modes, departures and returns carry their times and items, joined late and left early`() {
        val (meeting, items) = started()
        val credit = items.getValue("Credit proposal")
        val appetite = items.getValue("Risk appetite")
        assertThat(rejected { asUser(secretary) { attendance.progress(meeting.id, UUID.randomUUID()) } }).contains("ITEM_NOT_ON_AGENDA")
        asUser(secretary) { attendance.progress(meeting.id, credit) }
        Audit.assertAudited(tenant.id, "meeting.item.progressed", meeting.id.toString(), secretary.id.value.toString())

        // Before the first item: not late, during no item.
        record(meeting, others[3], AttendanceEventKind.ARRIVED, AttendanceMode.IN_PERSON) { it.copy(at = meeting.startsAt) }
        record(meeting, chair, AttendanceEventKind.ARRIVED, AttendanceMode.IN_PERSON)
        asUser(secretary) { attendance.progress(meeting.id, appetite) }
        record(meeting, director, AttendanceEventKind.ARRIVED, AttendanceMode.REMOTE)
        record(meeting, director, AttendanceEventKind.MODE_CHANGED, AttendanceMode.IN_PERSON)
        record(meeting, director2, AttendanceEventKind.ARRIVED, AttendanceMode.IN_PERSON)
        record(meeting, director2, AttendanceEventKind.LEFT)
        record(meeting, others[0], AttendanceEventKind.ABSENT)
        record(meeting, others[1], AttendanceEventKind.ARRIVED, AttendanceMode.IN_PERSON)
        record(meeting, others[1], AttendanceEventKind.STEPPED_OUT)
        assertThat(refused { record(meeting, others[1], AttendanceEventKind.STEPPED_OUT) }).isEqualTo("WRONG_STATE")

        val chairRow = person(meeting, chair)
        assertThat(chairRow.joinedLate).describedAs("after the first item started").isTrue()
        assertThat(person(meeting, others[3]).let { it.joinedLate to it.arrivedDuringItem }).isEqualTo(false to null)
        assertThat(chairRow.arrivedDuringItem).isEqualTo(credit)
        val directorRow = person(meeting, director)
        assertThat(directorRow.mode to directorRow.arrivedDuringItem).isEqualTo(AttendanceMode.IN_PERSON to appetite)
        assertThat(directorRow.joinedLate).isTrue()
        val left = person(meeting, director2)
        assertThat(left.state to left.leftEarly).isEqualTo(AttendanceState.LEFT to true)
        assertThat(left.leftDuringItem).isEqualTo(appetite)
        assertThat(person(meeting, others[0]).state).isEqualTo(AttendanceState.ABSENT)
        assertThat(person(meeting, others[1]).steppedOutOf).isEqualTo(appetite)
        // Coming back after leaving.
        record(meeting, director2, AttendanceEventKind.ARRIVED, AttendanceMode.REMOTE)
        assertThat(person(meeting, director2).let { it.state to it.leftEarly }).isEqualTo(AttendanceState.PRESENT to false)

        // E06 reads it all through the published API.
        val record = transactions.readInTenant(tenant.id) { checkNotNull(register.of(meeting.id)) }
        assertThat(record.itemsInProgress.map { it.itemId }).containsExactly(credit, appetite)
        val ofDirector2 = record.people.single { it.userId == director2.id.value }
        assertThat(ofDirector2.mode to ofDirector2.leftDuringItem).isEqualTo(AttendanceMode.REMOTE to appetite)
        assertThat(record.movements.filter { it.userId == director2.id.value }.map { it.kind })
            .containsExactly(AttendanceEventKind.ARRIVED, AttendanceEventKind.LEFT, AttendanceEventKind.ARRIVED)
        assertThat(record.people.single { it.userId == others[1].id.value }.steppedOutOf).containsExactly(appetite)
        assertThat(record.lastCorrectedAt).isNull()
    }

    @Test
    fun `times outside the meeting or ahead, a missing mode, items off the agenda and people outside are refused`() {
        val (meeting, _) = started()
        assertThat(
            rejected {
                record(
                    meeting,
                    others[2],
                    AttendanceEventKind.ARRIVED,
                    AttendanceMode.IN_PERSON,
                ) { it.copy(at = Instant.now().plusSeconds(3600)) }
            },
        ).contains("TIME_OUT_OF_RANGE")
        assertThat(
            rejected {
                record(meeting, others[2], AttendanceEventKind.ARRIVED, AttendanceMode.IN_PERSON) {
                    it.copy(at = meeting.startsAt.minus(Duration.ofHours(2)))
                }
            },
        ).contains("TIME_OUT_OF_RANGE")
        assertThat(rejected { record(meeting, others[2], AttendanceEventKind.ARRIVED) }).contains("MODE_REQUIRED")
        assertThat(
            rejected {
                record(meeting, others[2], AttendanceEventKind.ARRIVED, AttendanceMode.REMOTE) { it.copy(itemId = UUID.randomUUID()) }
            },
        ).contains("ITEM_NOT_ON_AGENDA")
        assertThat(rejected { record(meeting, user, AttendanceEventKind.ABSENT, version = null) }).contains("NOT_INVITABLE")
        assertThat(rejected { record(meeting, others[2], AttendanceEventKind.VOIDED) }).contains("VOID_SEPARATELY")
    }

    @Test
    fun `a wrong event is voided with a reason, and after the meeting only corrections with a reason`() {
        val (meeting, _) = started()
        val arrived = record(meeting, director, AttendanceEventKind.ARRIVED, AttendanceMode.IN_PERSON)
        assertThat(rejected { asUser(secretary) { attendance.void(meeting.id, arrived.event.id, arrived.row.version, " ") } })
            .contains("REASON_REQUIRED")
        assertThatThrownBy { asUser(secretary) { attendance.void(meeting.id, arrived.event.id, null, "Wrong person") } }
            .isInstanceOf(PreconditionRequiredException::class.java)
        val voided = asUser(secretary) { attendance.void(meeting.id, arrived.event.id, arrived.row.version, "Wrong person") }
        assertThat(voided.row.standing.state).isEqualTo(AttendanceState.EXPECTED)
        val audit = Audit.assertAudited(tenant.id, "meeting.attendance.voided", meeting.id.toString())
        assertThat(audit.canonical).contains(arrived.event.id.toString()).doesNotContain("Wrong person")
        assertThat(refused { asUser(secretary) { attendance.void(meeting.id, arrived.event.id, voided.row.version, "Again") } })
            .isEqualTo("WRONG_STATE")
        assertThat(refused { asUser(secretary) { attendance.void(meeting.id, voided.event.id, voided.row.version, "A void") } })
            .isEqualTo("WRONG_STATE")
        assertThatThrownBy { asUser(secretary) { attendance.void(meeting.id, UUID.randomUUID(), 0, "x") } }
            .isInstanceOf(MeetingNotFoundException::class.java)

        // Ended: arrivals and departures are corrections, with a reason; a change of mode is no longer recorded.
        val now = meeting(meeting.id)
        val held = asUser(secretary) { lifecycle.end(now.id, now.version) }
        assertThat(
            rejected { record(held, director, AttendanceEventKind.ARRIVED, AttendanceMode.IN_PERSON) { it.copy(at = held.startsAt) } },
        ).contains("REASON_REQUIRED")
        val corrected =
            record(held, director, AttendanceEventKind.ARRIVED, AttendanceMode.IN_PERSON) {
                it.copy(at = held.startsAt.plusSeconds(30), reason = "Arrived on time; missed in the room")
            }
        assertThat(corrected.event.correction).isTrue()
        assertThat(refused { record(held, director, AttendanceEventKind.MODE_CHANGED, AttendanceMode.REMOTE) { it.copy(reason = "x") } })
            .isEqualTo("WRONG_STATE")
        assertThat(asUser(secretary) { attendanceReads.register(held.id) }.correcting).isTrue()
        assertThat(transactions.readInTenant(tenant.id) { register.of(held.id) }?.lastCorrectedAt).isNotNull()
        assertThat(refused { asUser(secretary) { attendance.progress(held.id, UUID.randomUUID()) } }).isEqualTo("WRONG_STATE")
        assertThat(
            transactions.readInTenant(tenant.id) { progressStore.changes(held.id) },
        ).describedAs("no live quorum after the end").isEmpty()

        // A cancelled meeting's register is frozen; before the start only apologies are recorded.
        val cancelled = scheduled().let { asUser(secretary) { lifecycle.cancel(it.id, it.version, "Called off") } }
        assertThat(refused { record(cancelled, director, AttendanceEventKind.APOLOGIES, version = null) }).isEqualTo("FROZEN")
        val upcoming = scheduled()
        assertThat(refused { record(upcoming, director, AttendanceEventKind.ARRIVED, AttendanceMode.IN_PERSON, version = null) })
            .isEqualTo("WRONG_STATE")
        val sent = record(upcoming, director, AttendanceEventKind.APOLOGIES, version = null) { it.copy(note = "Abroad") }
        assertThat(sent.row.standing.state).isEqualTo(AttendanceState.APOLOGIES)
    }

    @Test
    fun `an alternate counts once for the number only, while the member is absent, never as chair`() {
        val (meeting, _) = started { answer(chair, it, RsvpInput(RsvpAnswer.APOLOGIES, alternateAttends = true)) }
        val stand = person(meeting, chairAlternate)
        assertThat(stand.capacity to stand.standingInFor?.userId).isEqualTo(AttendanceCapacity.ALTERNATE to chair.id.value)
        assertThat(person(meeting, chair).state).isEqualTo(AttendanceState.APOLOGIES)

        arrive(meeting, listOf(chairAlternate) + others.take(3))
        val quorum = asUser(secretary) { attendanceReads.quorum(meeting.id, null) }
        assertThat(quorum.present).describedAs("the alternate counts once towards the number").isEqualTo(4)
        assertThat(quorum.quorate).isFalse()
        assertThat(quorum.unmet.map { it.kind }).containsExactly(UnmetKind.ROLE)
        val chairSeat = checkNotNull(quorum.seats).single { it.holder.userId == chair.id.value }
        assertThat(chairSeat.byAlternate to chairSeat.filledBy?.userId).isEqualTo(true to chairAlternate.id.value)

        // The chair comes after all: the alternate stays in attendance and stops counting.
        record(meeting, chair, AttendanceEventKind.ARRIVED, AttendanceMode.IN_PERSON)
        val withChair = asUser(secretary) { attendanceReads.quorum(meeting.id, null) }
        assertThat(withChair.quorate to withChair.present).isEqualTo(true to 4)
        assertThat(person(meeting, chairAlternate).let { it.state to it.counted }).isEqualTo(AttendanceState.PRESENT to false)

        // Once the alternate holds a seat themselves, they are counted as a member, never as an alternate.
        record(meeting, chairAlternate, AttendanceEventKind.LEFT)
        asUser(admin) { members.add(brc, MemberInput(userId = chairAlternate.id.value, role = ForumRole.MEMBER)) }
        assertThat(refused { record(meeting, chairAlternate, AttendanceEventKind.ARRIVED, AttendanceMode.IN_PERSON) })
            .isEqualTo("ALREADY_COUNTED")
    }

    @Test
    fun `officers see the register, the audience its own row and counts, members the quorum's numbers`() {
        val (meeting, _) = started { asUser(secretary) { guests.add(it.id, user.id.value, "Adviser") } }
        arrive(meeting, listOf(chair, director, director2))

        val ofChair = asUser(chair) { attendanceReads.register(meeting.id) }
        assertThat(ofChair.people).isNotNull()
        assertThat(ofChair.canRecord).isFalse()
        assertThatThrownBy { record(meeting, director, AttendanceEventKind.LEFT, by = chair) }.isInstanceOf(ForbiddenException::class.java)
        assertThat(asUser(chair) { attendanceReads.changes(meeting.id) }).isEmpty()

        val ofMember = asUser(director) { attendanceReads.register(meeting.id) }
        assertThat(ofMember.people to ofMember.events).isEqualTo(null to null)
        assertThat(ofMember.mine?.state).isEqualTo(AttendanceState.PRESENT)
        assertThat(ofMember.counts.inPerson).isEqualTo(3)
        assertThat(ofMember.quorum?.present to ofMember.quorum?.seats).isEqualTo(3 to null)
        assertThatThrownBy { asUser(director) { attendanceReads.changes(meeting.id) } }.isInstanceOf(ForbiddenException::class.java)
        assertThatThrownBy {
            record(
                meeting,
                director2,
                AttendanceEventKind.LEFT,
                by = director,
            )
        }.isInstanceOf(ForbiddenException::class.java)

        val ofGuest = asUser(user) { attendanceReads.register(meeting.id) }
        assertThat(ofGuest.mine?.capacity).isEqualTo(AttendanceCapacity.GUEST)
        assertThat(ofGuest.quorum).isNull()
        assertThatThrownBy { asUser(user) { attendanceReads.quorum(meeting.id, null) } }.isInstanceOf(ForbiddenException::class.java)

        assertThatThrownBy { asUser(reader) { attendanceReads.register(meeting.id) } }.isInstanceOf(ForbiddenException::class.java)
        val outsider = accounts.anActiveUser(transactions, tenant, aPassword())
        assertThatThrownBy { asUser(outsider) { attendanceReads.register(meeting.id) } }.isInstanceOf(MeetingNotFoundException::class.java)
    }

    private fun refused(block: () -> Unit): String =
        runCatching(block).exceptionOrNull().let { (it as? RefusedException)?.reason ?: throw AssertionError("not refused", it) }

    private fun rejected(block: () -> Unit): List<String> =
        runCatching(block).exceptionOrNull().let {
            (it as? MeetingRejectedException)?.problems?.map { p -> p.code } ?: throw AssertionError("not rejected", it)
        }
}
