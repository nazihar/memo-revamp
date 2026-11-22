package com.memo.analytics

import com.memo.actions.ActionPlanting
import com.memo.actions.internal.ActionStore
import com.memo.actions.internal.ItemCopy
import com.memo.meeting.AttendanceEventKind
import com.memo.meeting.AttendanceMode
import com.memo.meeting.MeetingItems
import com.memo.meeting.MeetingKind
import com.memo.meeting.MeetingMode
import com.memo.meeting.internal.AgendaOrigin
import com.memo.meeting.internal.LateDecision
import com.memo.meeting.internal.MeetingInput
import com.memo.meeting.internal.MeetingPlace
import com.memo.meeting.internal.MeetingRow
import com.memo.meeting.internal.TimesInput
import com.memo.meeting.internal.attendance.AttendanceInput
import com.memo.minutes.ReleaseFixture
import com.memo.testsupport.Person
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * E06's Board Risk Committee (chair, vice-chair and two directors in seats, a secretary, a `forum.read` holder, someone
 * outside) with held meetings made as the committee makes them: memos escalated and placed on a meeting that started a
 * minute ago (late, so the chair took them), apologies recorded while it was scheduled, arrivals while it runs; and
 * overdue actions planted through the store with a due date in the past.
 */
abstract class AnalyticsFixture : ReleaseFixture() {
    @Autowired lateinit var actionStore: ActionStore

    @Autowired lateinit var meetingItems: MeetingItems

    /**
     * A meeting with the memos of [subjects] placed on it (published, the chair agreeing to take them late), [apologies]
     * recorded while it was scheduled, [before] run while scheduled (before publication: restrictions) and [published]
     * after it (declarations), then started with [present] arriving.
     */
    fun meetingWith(
        subjects: List<String>,
        present: List<Person>,
        apologies: List<Person> = emptyList(),
        before: (MeetingRow, List<Placed>) -> Unit = { _, _ -> },
        published: (MeetingRow, List<Placed>) -> Unit = { _, _ -> },
    ): Running {
        val placed = subjects.map { s -> escalated(s).let { (memo, submission) -> Triple(s, memo, submission) } }
        val zone = ZoneId.of("Asia/Dhaka")
        val start = LocalDateTime.now(zone).truncatedTo(ChronoUnit.MINUTES).minusMinutes(1)
        val meeting =
            asUser(secretary.account) {
                val created =
                    lifecycle.create(
                        brc,
                        MeetingInput(MeetingKind.ORDINARY, MeetingPlace(null, MeetingMode.IN_PERSON, "Board room", null, null), null),
                        TimesInput(start, start.plusHours(3), zone.id),
                    )
                starts.start(created.id, AgendaOrigin.TEMPLATE, null)
                created
            }
        val items = placed.map { (s, memo, submission) -> Placed(s, memo, submission, place(meeting, submission)) }
        asUser(secretary.account) { lifecycle.schedule(meeting.id, reads.get(meeting.id).version) }
        items.forEach { asUser(chair.account) { chairing.decideItem(meeting.id, it.item, version(meeting), LateDecision(true)) } }
        before(meeting, items)
        publish(meeting)
        published(meeting, items)
        apologies.forEach { person ->
            asUser(secretary.account) { attendance.record(meeting.id, null, AttendanceInput(person.id, AttendanceEventKind.APOLOGIES)) }
        }
        startMeeting(meeting)
        if (present.isNotEmpty()) {
            asUser(secretary.account) { attendance.arrivals(meeting.id, present.map { it.id }, AttendanceMode.IN_PERSON, null) }
        }
        return Running(meeting, items)
    }

    /** An issued action of [meeting] (on [item] when given) owned by [owner], due [daysAgo] days ago. */
    fun overdueAction(
        meeting: MeetingRow,
        item: UUID?,
        owner: Person,
        daysAgo: Long,
        title: String = "Report the revised valuation",
    ): UUID {
        val copy =
            item?.let { id ->
                val ref = checkNotNull(asUser(admin) { meetingItems.agenda(meeting.id) }.item(id))
                ItemCopy(ref.parentId, ref.classificationId, ref.restrictedTo, ref.titleHidden, ref.membersOnly)
            }
        val id =
            ActionPlanting(transactions, actionStore, tenantDsl).action(
                tenant.id,
                brc,
                meeting.id,
                item,
                copy,
                levels()[0].id,
                secretary.id,
                title,
                owner.id,
            )
        transactions.inTenant(tenant.id) {
            tenantDsl
                .current()
                .execute(
                    "update actions_item set due_date = ?, status = 'OPEN', version = version + 1 where id = ?",
                    LocalDate.now(ZoneId.of("Asia/Dhaka")).minusDays(daysAgo),
                    id,
                )
        }
        return id
    }

    fun analytics(
        person: Person,
        query: String = "",
    ) = read("/api/v1/analytics/committees$query", person.session)

    fun detail(
        person: Person,
        query: String = "",
    ) = read("/api/v1/analytics/committees/$brc$query", person.session)

    fun export(
        person: Person,
        query: String = "",
    ) = read("/api/v1/analytics/committees/$brc/attendance/export$query", person.session)
}
