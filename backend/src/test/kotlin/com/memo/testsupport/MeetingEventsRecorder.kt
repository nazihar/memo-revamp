package com.memo.testsupport

import com.memo.meeting.AgendaPublished
import com.memo.meeting.MeetingChanged
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.util.concurrent.CopyOnWriteArrayList

/** Records the meeting module's published events (E03 T3) for tests of what E04–E07 will hear; in every test context. */
@Component
class MeetingEventsRecorder {
    @EventListener
    fun onChanged(event: MeetingChanged) {
        CHANGED += event
    }

    @EventListener
    fun onPublished(event: AgendaPublished) {
        PUBLISHED += event
    }

    companion object {
        val CHANGED = CopyOnWriteArrayList<MeetingChanged>()
        val PUBLISHED = CopyOnWriteArrayList<AgendaPublished>()
    }
}
