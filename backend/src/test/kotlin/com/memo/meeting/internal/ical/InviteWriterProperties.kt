package com.memo.meeting.internal.ical

import biweekly.Biweekly
import com.memo.meeting.MeetingMode
import com.memo.meeting.internal.ical.InviteFixtures.brc
import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.model.Component
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.component.VTimeZone
import net.fortuna.ical4j.model.property.TzOffsetTo
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Assume
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import net.jqwik.api.Tuple
import org.assertj.core.api.Assertions.assertThat
import java.io.StringReader
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import net.fortuna.ical4j.model.Property as IcalProperty

/**
 * Property tests of the writer (test plan T1 `InviteWriterProperties`, `VTimezoneProperties`): hostile text never adds
 * a property line or component, survives the round trip as its cleaned form and folds within 75 octets; and the
 * VTIMEZONE built from `ZoneRules` gives every zone the offsets `java.time` gives it, so DTSTART means the same instant.
 */
class InviteWriterProperties {
    private val writer = InviteFixtures.writer()

    @Property(tries = 300)
    fun hostileTextNeverAddsAPropertyAndComesBackCleaned(
        @ForAll("text") title: String,
        @ForAll("text") place: String,
        @ForAll("text") details: String,
        @ForAll("text") name: String,
        @ForAll("modes") mode: MeetingMode,
    ) {
        val event = brc.copy(summary = title, location = place, mode = mode, joinUrl = "https://meet.example/j/1", joinDetails = details)
        val invite = InviteFixtures.request(event).copy(attendee = InviteParty(name, "director@demo.example"))
        val bytes = writer.write(invite)

        InviteChecks.valid(bytes, InviteKind.REQUEST)
        val text = String(bytes, Charsets.UTF_8)
        val calendar = CalendarBuilder().build(StringReader(text))
        assertThat(calendar.getComponents<net.fortuna.ical4j.model.component.CalendarComponent>().map { it.name })
            .containsExactly(Component.VTIMEZONE, Component.VEVENT)
        val vevent = calendar.getComponent<VEvent>(Component.VEVENT).get()
        PROPERTIES.forEach { assertThat(vevent.getProperties<IcalProperty>(it)).describedAs(it).hasSize(1) }
        assertThat(vevent.getProperties<IcalProperty>(IcalProperty.LOCATION).size).isLessThanOrEqualTo(1)
        val unfoldedLines = text.replace("\r\n ", "").removeSuffix("\r\n").split("\r\n")
        assertThat(unfoldedLines.map { it.substringBefore(':').substringBefore(';') }.toSet()).isSubsetOf(NAMES)
        // Text values read back by biweekly: ical4j 4.4.0's parser misreads a backslash before a line break (its writer
        // escapes it correctly), a finding of the E05 T1 spike.
        val read =
            Biweekly
                .parse(text)
                .first()
                .events
                .single()
        assertThat(read.summary.value).isEqualTo(InviteInput.line(title).ifEmpty { "-" })
        // biweekly hands line breaks back as the platform's (CRLF on Windows).
        val description = read.description.value.replace(System.lineSeparator(), "\n")
        InviteInput.block(details).takeIf { it.isNotEmpty() }?.let { assertThat(description).contains(it) }
        InviteInput.line(place).takeIf { it.isNotEmpty() }?.let { assertThat(read.location.value).isEqualTo(it) }
        assertThat(
            read.attendees
                .single()
                .commonName
                .orEmpty(),
        ).isEqualTo(InviteInput.name(name))
    }

    @Property(tries = 500)
    fun vtimezoneGivesTheOffsetsJavaTimeGives(
        @ForAll("zones") zoneId: String,
        @ForAll("instants") at: Instant,
    ) {
        val zone = ZoneId.of(zoneId)
        val end = at.plusSeconds(MEETING)
        val vtimezone: VTimeZone = VTimezones.of(zone, at, end)
        val observances = vtimezone.observances
        // Unless yearly rules describe the zone, the last observance is the one in force when the meeting starts (or the
        // change during it): a reader that takes the last offset, as biweekly does, still reads the meeting right.
        val yearly = observances.any { it.getProperty<IcalProperty>(IcalProperty.RRULE).isPresent }
        if (!yearly) {
            val changes =
                zone.rules
                    .nextTransition(at)
                    ?.instant
                    ?.isAfter(end) == false
            assertThat(observances.last().getRequiredProperty<TzOffsetTo>(IcalProperty.TZOFFSETTO).offset)
                .describedAs("$zone: the last observance for a meeting at $at")
                .isEqualTo(zone.rules.getOffset(if (changes) end else at))
        }
        // Yearly and fixed observances describe the whole window around the meeting; one-off ones describe the meeting.
        val quiet =
            zone.rules
                .nextTransition(at.minus(WINDOW))
                ?.instant
                ?.isAfter(at.plus(WINDOW)) != false
        val covered =
            if (yearly || quiet) (-11L..11L).map { at.plusSeconds(it * MONTH) } + at else listOf(at, at.plusSeconds(MEETING / 2), end)
        // The meeting's instant, and instants across the window the observances cover, through ical4j's own choice of the
        // observance in force. Two ical4j 4.4.0 weaknesses found in the E05 T1 spike shape this oracle: its
        // java.util.TimeZone adapter misreports zones whose standard offset changes (Antarctica/Casey) although it picks
        // the right observance, and that choice can come up to a day early before a change in zones far west of UTC
        // (America/Adak). So instants within two days of a change are left out here; the exact instants of every change
        // are checked when the VTIMEZONE is built (VTimezones compares the RRULE's dates with the JVM's transitions).
        covered.filterNot { nearChange(zone, it) }.forEach { instant ->
            val observance = vtimezone.getApplicableObservance(instant)
            assertThat(observance).describedAs("$zone at $instant").isNotNull()
            assertThat(observance!!.getRequiredProperty<TzOffsetTo>(IcalProperty.TZOFFSETTO).offset)
                .describedAs("$zone at $instant")
                .isEqualTo(zone.rules.getOffset(instant))
        }
    }

    @Property(tries = 200)
    fun dtstartInTheZoneIsTheMeetingsInstantForBothParsers(
        @ForAll("zones") zoneId: String,
        @ForAll("instants") at: Instant,
    ) {
        val zone = ZoneId.of(zoneId)
        Assume.that(!nearChange(zone, at))
        val event = brc.copy(startsAt = at, endsAt = at.plusSeconds(HOUR), zone = zone)

        val parsed = InviteChecks.valid(writer.write(InviteFixtures.request(event)), InviteKind.REQUEST)
        assertThat(parsed.start).isEqualTo(at)
        assertThat(parsed.end).isEqualTo(at.plusSeconds(HOUR))
    }

    private fun nearChange(
        zone: ZoneId,
        at: Instant,
    ): Boolean {
        val rules = zone.rules
        val around = listOfNotNull(rules.previousTransition(at), rules.nextTransition(at)).map { it.instant }
        return around.any { Duration.between(it, at).abs() < NEAR }
    }

    @Provide
    fun text(): Arbitrary<String> {
        val hostile =
            Arbitraries.of(
                ";",
                ",",
                "\\",
                ":",
                "\"",
                "^",
                "ATTENDEE:mailto:x@evil.example",
                "END:VEVENT",
                "لجنة",
                "😀",
                *CONTROLS.map { Char(it).toString() }.toTypedArray(),
            )
        val any = Arbitraries.strings().withCharRange(Char(0), Char(0xFFFF)).ofMaxLength(MAX_TEXT)
        return Arbitraries.frequencyOf(
            Tuple.of(3, any),
            Tuple.of(2, hostile.list().ofMaxSize(HOSTILE_PARTS).map { it.joinToString("") }),
            Tuple.of(1, any.map { it.take(MAX_TEXT / 2) + "\r\nATTENDEE:mailto:x@evil.example\r\n" }),
        )
    }

    @Provide
    fun modes(): Arbitrary<MeetingMode> = Arbitraries.of(*MeetingMode.entries.toTypedArray())

    /** The JVM's IANA zones; ical4j registers zones of its own (`ical4j~…`) as it parses files, which are not meetings'. */
    @Provide
    fun zones(): Arbitrary<String> = Arbitraries.of(ZoneId.getAvailableZoneIds().filterNot { it.startsWith("ical4j") }.sorted())

    @Provide
    fun instants(): Arbitrary<Instant> =
        Arbitraries
            .longs()
            .between(FROM.epochSecond, TO.epochSecond)
            .map { Instant.ofEpochSecond(it - it % MINUTE) }

    private companion object {
        const val MAX_TEXT = 2000

        /** CR, LF, tab, NUL, DEL, NEL, the line and paragraph separators, a lone surrogate. */
        val CONTROLS = listOf(0x0D, 0x0A, 0x09, 0x00, 0x7F, 0x85, 0x2028, 0x2029, 0xD800)
        const val HOSTILE_PARTS = 40
        const val MONTH = 30L * 24 * 3600
        const val HOUR = 3600L
        val NEAR: Duration = Duration.ofDays(2)
        val WINDOW: Duration = Duration.ofDays(366)
        const val MEETING = 3L * 3600
        const val MINUTE = 60L
        val FROM: Instant = Instant.parse("2020-01-01T00:00:00Z")
        val TO: Instant = Instant.parse("2040-12-31T00:00:00Z")
        val PROPERTIES =
            listOf(
                IcalProperty.UID,
                IcalProperty.SEQUENCE,
                IcalProperty.DTSTAMP,
                IcalProperty.DTSTART,
                IcalProperty.DTEND,
                IcalProperty.SUMMARY,
                IcalProperty.ORGANIZER,
                IcalProperty.ATTENDEE,
                IcalProperty.DESCRIPTION,
                IcalProperty.URL,
                IcalProperty.STATUS,
            )
        val NAMES =
            setOf(
                "BEGIN",
                "END",
                "PRODID",
                "VERSION",
                "CALSCALE",
                "METHOD",
                "TZID",
                "DTSTART",
                "TZOFFSETFROM",
                "TZOFFSETTO",
                "TZNAME",
                "RRULE",
            ) + PROPERTIES + IcalProperty.LOCATION
    }
}
