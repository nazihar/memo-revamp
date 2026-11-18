package com.memo.meeting.internal.ical

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import com.memo.meeting.MeetingMode
import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.model.Component
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.component.VEvent
import java.io.StringReader

/**
 * Fuzz target for the invitation writer (test plan, Jazzer nightly `InviteWriterFuzz`; threat T3): titles, places,
 * joining details, names and addresses into a full write-validate-parse cycle. In the PR stage it replays its stored
 * inputs; the nightly stage fuzzes it for a few minutes (`JAZZER_FUZZ=1`). It fails when anything other than a refused
 * input leaves the writer, when a property is added or lost, or when a line is longer than 75 octets.
 */
class InviteWriterFuzz {
    private val writer = InviteFixtures.writer()

    @FuzzTest(maxDuration = "5m")
    fun write(data: FuzzedDataProvider) {
        val kind = InviteKind.entries[data.consumeInt(0, InviteKind.entries.size - 1)]
        val mode = MeetingMode.entries[data.consumeInt(0, MeetingMode.entries.size - 1)]
        val event =
            InviteFixtures.brc.copy(
                sequence = data.consumeInt(0, MAX_SEQUENCE),
                summary = data.consumeString(MAX_TEXT),
                location = data.consumeString(MAX_TEXT),
                joinDetails = data.consumeString(MAX_TEXT),
                joinUrl = "https://meet.example/" + data.consumeAsciiString(MAX_PATH),
                mode = mode,
            )
        val attendee = InviteParty(data.consumeString(MAX_TEXT), data.consumeAsciiString(MAX_PATH) + "@demo.example")
        val bytes =
            try {
                writer.write(InviteFixtures.of(kind, event).copy(attendee = attendee.takeIf { kind != InviteKind.PUBLISH }))
            } catch (_: InviteRefusedException) {
                return
            }
        val text = String(bytes, Charsets.UTF_8)
        check(IcsFolding.problems(text).isEmpty()) { "format" }
        val vevent = CalendarBuilder().build(StringReader(text)).getComponent<VEvent>(Component.VEVENT).get()
        check(vevent.getProperties<Property>(Property.SUMMARY).size == 1)
        check(vevent.getProperties<Property>(Property.ATTENDEE).size == if (kind == InviteKind.PUBLISH) 0 else 1)
        check(vevent.getProperties<Property>(Property.DESCRIPTION).size == 1)
    }

    private companion object {
        const val MAX_TEXT = 500
        const val MAX_PATH = 40
        const val MAX_SEQUENCE = 1000
    }
}
