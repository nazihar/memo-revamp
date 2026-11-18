package com.memo.meeting.internal.ical

import net.fortuna.ical4j.model.Calendar
import net.fortuna.ical4j.model.Component
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.component.CalendarComponent
import net.fortuna.ical4j.model.component.VEvent

/**
 * The iTIP rules of RFC 5546 for the three methods memo sends, as far as ical4j 4.4.0 does not enforce them itself (the
 * E05 T1 spike: its rules require ATTENDEE and ORGANIZER for REQUEST, but accept a CANCEL without ATTENDEE or with
 * `STATUS:CONFIRMED`, and a PUBLISH with ATTENDEE). Problems name properties, never values.
 */
internal object InviteRules {
    private val COMMON = listOf(Property.UID, Property.DTSTAMP, Property.SEQUENCE, Property.DTSTART, Property.DTEND, Property.ORGANIZER)

    fun problems(
        calendar: Calendar,
        kind: InviteKind,
    ): List<String> {
        val method = calendar.getProperties<Property>(Property.METHOD).map { it.value }
        val events = calendar.getComponents<CalendarComponent>(Component.VEVENT)
        val event = events.singleOrNull() as? VEvent ?: return listOf("exactly one VEVENT is required")
        val methodProblem = "METHOD must be ${kind.method}".takeUnless { method == listOf(kind.method) }
        val once = COMMON.filter { event.getProperties<Property>(it).size != 1 }.map { "$it is required once" }
        val attendees = event.getProperties<Property>(Property.ATTENDEE).size
        val status = event.getProperties<Property>(Property.STATUS).map { it.value }
        return listOfNotNull(methodProblem) + once + methodRules(kind, attendees, status)
    }

    private fun methodRules(
        kind: InviteKind,
        attendees: Int,
        status: List<String>,
    ): List<String> =
        when (kind) {
            // §3.2.2: an invitation to one or more attendees; memo names the recipient alone.
            InviteKind.REQUEST -> {
                listOfNotNull(
                    "REQUEST names exactly one ATTENDEE".takeUnless { attendees == 1 },
                    "REQUEST has STATUS:CONFIRMED".takeUnless { status == listOf("CONFIRMED") },
                )
            }

            // §3.2.5: the whole event cancelled — some or all attendees, STATUS MUST be CANCELLED.
            InviteKind.CANCEL -> {
                listOfNotNull(
                    "CANCEL names its ATTENDEE".takeUnless { attendees >= 1 },
                    "CANCEL of the meeting has STATUS:CANCELLED".takeUnless { status == listOf("CANCELLED") },
                )
            }

            // §3.2.5: uninviting specific attendees — they are named, and STATUS MUST NOT be included.
            InviteKind.UNINVITE -> {
                listOfNotNull(
                    "CANCEL names its ATTENDEE".takeUnless { attendees >= 1 },
                    "CANCEL of one attendee has no STATUS".takeUnless { status.isEmpty() },
                )
            }

            // §3.2.1: attendees MUST NOT be present.
            InviteKind.PUBLISH -> {
                listOfNotNull(
                    "PUBLISH has no ATTENDEE".takeUnless { attendees == 0 },
                    "PUBLISH has STATUS:CONFIRMED".takeUnless { status == listOf("CONFIRMED") },
                )
            }
        }
}
