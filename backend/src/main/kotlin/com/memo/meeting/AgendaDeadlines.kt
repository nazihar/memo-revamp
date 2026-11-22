package com.memo.meeting

import com.memo.meeting.internal.AgendaLateness
import com.memo.meeting.internal.MeetingStore
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.UUID

/**
 * A meeting's agenda deadline for other modules (design E07 §1.12, §9 (b)): the last day papers are on time (E03's
 * [AgendaLateness.deadline], the paper deadline of the meeting's pinned type version, counted in working days on the
 * tenant's calendar), after which the committee's papers go out. Read only, inside a tenant transaction; no access
 * decision is made here.
 */
@Component
class AgendaDeadlines(
    private val store: MeetingStore,
    private val lateness: AgendaLateness,
) {
    /** [meetingId]'s agenda deadline, a date in the meeting's zone; null for no such meeting or no working day to count. */
    fun of(meetingId: UUID): LocalDate? = store.find(meetingId)?.let(lateness::deadline)
}
