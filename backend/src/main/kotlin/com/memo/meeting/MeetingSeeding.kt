package com.memo.meeting

import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.meeting.internal.AgendaLateness
import com.memo.meeting.internal.AgendaOrigin
import com.memo.meeting.internal.AgendaStart
import com.memo.meeting.internal.ConsentRequests
import com.memo.meeting.internal.MeetingGuests
import com.memo.meeting.internal.MeetingInput
import com.memo.meeting.internal.MeetingLifecycle
import com.memo.meeting.internal.MeetingPlace
import com.memo.meeting.internal.MeetingStore
import com.memo.meeting.internal.MemberAgendas
import com.memo.meeting.internal.TimesInput
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * Meetings for seeders (the demo tenant, Phase 4 E03 T9), through the same services as the API, as the user bound to the
 * current unit of work: creating, scheduling and moving them, guests, and what a member does on the meeting page. Never
 * called from requests. Each call is its own tenant transaction. The seeder decides what already exists ("seeders only
 * fill gaps"): [find] answers whether a meeting is there. The agenda is [AgendaSeeding]'s.
 */
@Suppress("TooManyFunctions") // One facade for every meeting step a seeder takes, as ActionSeeding.
@Service
class MeetingSeeding(
    private val lifecycle: MeetingLifecycle,
    private val meetings: MeetingStore,
    private val guests: MeetingGuests,
    private val starts: AgendaStart,
    private val consent: ConsentRequests,
    private val members: MemberAgendas,
    private val lateness: AgendaLateness,
    private val transactions: TenantTransactions,
) {
    /** The forum's meeting titled [title] in any state, if there is one. */
    fun find(
        forumId: UUID,
        title: String,
    ): UUID? = tx { meetings.ofForum(forumId).firstOrNull { it.title == title }?.id }

    /** The days the forum's meetings start on, each in its own zone, in any state (Phase 4 E08 T8: the history's months). */
    fun dates(forumId: UUID): List<LocalDate> = tx { meetings.ofForum(forumId).map { it.date } }

    /** The meeting's status (Phase 4 E04 T9: declarations are seeded only while it is scheduled). */
    fun status(meetingId: UUID): MeetingStatus = tx { meeting(meetingId).status }

    /**
     * A draft meeting of [forumId] at [location] (in person, or [MeetingMode.HYBRID] with [joinUrl]: Phase 4 E05 T10), its
     * agenda started from the committee's template.
     */
    @Suppress("LongParameterList")
    fun create(
        forumId: UUID,
        title: String,
        startsAt: LocalDateTime,
        endsAt: LocalDateTime,
        zone: String,
        location: String,
        mode: MeetingMode = MeetingMode.IN_PERSON,
        joinUrl: String? = null,
    ): UUID =
        tx {
            val meeting =
                lifecycle.create(
                    forumId,
                    MeetingInput(MeetingKind.ORDINARY, MeetingPlace(title, mode, location, joinUrl, null), null),
                    TimesInput(startsAt, endsAt, zone),
                )
            starts.start(meeting.id, AgendaOrigin.TEMPLATE, null)
            meeting.id
        }

    fun schedule(meetingId: UUID) {
        tx { lifecycle.schedule(meetingId, meeting(meetingId).version) }
    }

    /** New times for a scheduled meeting, with the [reason] the members see. */
    fun reschedule(
        meetingId: UUID,
        startsAt: LocalDateTime,
        endsAt: LocalDateTime,
        zone: String,
        reason: String,
    ) {
        tx { lifecycle.reschedule(meetingId, meeting(meetingId).version, TimesInput(startsAt, endsAt, zone), reason) }
    }

    /** Whether the meeting's paper deadline has passed: what is placed or uploaded now is late. */
    fun deadlinePassed(meetingId: UUID): Boolean =
        tx {
            val meeting = meeting(meetingId)
            lateness.passed(meeting, lateness.deadline(meeting))
        }

    fun guest(
        meetingId: UUID,
        userId: UUID,
        note: String,
    ) {
        tx { guests.add(meetingId, userId, note) }
    }

    /** The bound member asks to discuss consent item [itemId]. */
    fun askToDiscuss(
        meetingId: UUID,
        itemId: UUID,
    ) {
        tx { consent.ask(meetingId, itemId) }
    }

    /** The bound person has seen revision [number] ("What changed" starts after it). */
    fun seen(
        meetingId: UUID,
        number: Int,
    ) {
        tx { members.seen(meetingId, number) }
    }

    private fun meeting(id: UUID) = checkNotNull(meetings.find(id)) { "meeting $id" }

    private fun <T> tx(block: () -> T): T = transactions.inTenant(TenantContext.current(), block)
}
