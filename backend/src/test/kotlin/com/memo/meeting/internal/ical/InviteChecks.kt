package com.memo.meeting.internal.ical

import biweekly.Biweekly
import biweekly.io.ParseWarning
import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.model.Calendar
import net.fortuna.ical4j.model.Component
import net.fortuna.ical4j.model.Parameter
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.parameter.TzId
import net.fortuna.ical4j.model.property.DateProperty
import net.fortuna.ical4j.validate.ValidationEntry
import org.assertj.core.api.Assertions.assertThat
import java.io.StringReader
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.Temporal

/** The properties both parsers must agree on (test plan T1, `InviteCrossParserTest`). */
data class ParsedInvite(
    val method: String,
    val uid: String,
    val sequence: Int,
    val start: Instant,
    val end: Instant,
    val tzid: String?,
    val status: String?,
    val attendees: List<String>,
    val summary: String,
)

/**
 * The checks every invitation file in the tests goes through (test plan T1, `InviteValidationTest`): format (CRLF, 75
 * octets, UTF-8, a VTIMEZONE per TZID), ical4j's strict validation with its iTIP rules after parsing the bytes back,
 * memo's RFC 5546 rules for the method, and biweekly 0.6.8 as an independent second parser agreeing on the key
 * properties. Every test that writes a file passes it here, so none escapes validation.
 */
object InviteChecks {
    /** Validates [bytes] as a file of [kind] and returns what the parsers read (they agree, or this fails). */
    fun valid(
        bytes: ByteArray,
        kind: InviteKind,
    ): ParsedInvite {
        assertThat(IcsFolding.validUtf8(bytes)).describedAs("UTF-8").isTrue()
        val text = String(bytes, Charsets.UTF_8)
        assertThat(IcsFolding.problems(text)).describedAs("format").isEmpty()
        val calendar = CalendarBuilder().build(StringReader(text))
        val errors = calendar.validate().entries.filter { it.severity == ValidationEntry.Severity.ERROR }
        assertThat(errors).describedAs("ical4j strict and iTIP validation").isEmpty()
        assertThat(InviteRules.problems(calendar, kind)).describedAs("RFC 5546 rules for ${kind.name}").isEmpty()
        val ical4j = ical4j(calendar)
        val biweekly = biweekly(text)
        // biweekly 0.6.8 turns a VTIMEZONE into a java.util.TimeZone with one hour of daylight saving, so it misplaces
        // times in daylight saving that is not one hour (Australia/Lord_Howe: 30 minutes; Antarctica/Troll: 2 hours).
        // There only ical4j, which agrees with java.time, is checked for the instants.
        val zone = ical4j.tzid?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        val wholeHour =
            zone == null || listOf(ical4j.start, ical4j.end).all { zone.rules.getDaylightSavings(it).seconds in setOf(0L, HOUR) }
        // biweekly 0.6.8 also ignores the onsets of observances without RRULE and reads every time at the last observance's
        // offset. The writer therefore ends a one-off zone's VTIMEZONE with the observance in force (VTimezones), which only
        // a meeting across a one-off change (a change during the meeting, in a file without RRULE) cannot satisfy: there
        // biweekly's start is checked against nothing but ical4j, which agrees with java.time (E05 T1, CI run 44333621213).
        val oneOffChangeDuring =
            zone != null && "RRULE:" !in text && zone.rules
                .nextTransition(ical4j.start)
                ?.instant
                ?.isAfter(ical4j.end) == false
        val compared =
            when {
                !wholeHour -> biweekly.copy(start = ical4j.start, end = ical4j.end)
                oneOffChangeDuring -> biweekly.copy(start = ical4j.start)
                else -> biweekly
            }
        assertThat(compared).describedAs("biweekly reads what ical4j reads").isEqualTo(ical4j)
        return ical4j
    }

    private const val HOUR = 3600L

    private fun ical4j(calendar: Calendar): ParsedInvite {
        val event = calendar.getComponent<VEvent>(Component.VEVENT).get()

        fun value(name: String) = event.getProperty<Property>(name).map { it.value }.orElse(null)

        val start = event.getProperty<DateProperty<Temporal>>(Property.DTSTART).get()
        val end = event.getProperty<DateProperty<Temporal>>(Property.DTEND).get()
        return ParsedInvite(
            method = calendar.getProperty<Property>(Property.METHOD).get().value,
            uid = value(Property.UID),
            sequence = value(Property.SEQUENCE).toInt(),
            start = Instant.from(start.date),
            end = Instant.from(end.date),
            tzid = start.getParameter<TzId>(Parameter.TZID).map { it.value }.orElse(null),
            status = value(Property.STATUS),
            attendees = event.getProperties<Property>(Property.ATTENDEE).map { it.value.removePrefix("mailto:") },
            summary = value(Property.SUMMARY),
        )
    }

    private fun biweekly(text: String): ParsedInvite {
        val warnings = mutableListOf<List<ParseWarning>>()
        val ical = Biweekly.parse(text).warnings(warnings).first()
        assertThat(warnings.flatten()).describedAs("biweekly warnings").isEmpty()
        val event = ical.events.single()
        return ParsedInvite(
            method = ical.method.value,
            uid = event.uid.value,
            sequence = event.sequence.value,
            start = event.dateStart.value.toInstant(),
            end = event.dateEnd.value.toInstant(),
            tzid =
                ical.timezoneInfo
                    .getTimezone(event.dateStart)
                    ?.component
                    ?.timezoneId
                    ?.value,
            status = event.status?.value,
            attendees = event.attendees.map { it.email },
            summary = event.summary.value,
        )
    }
}
