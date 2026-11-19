package com.memo.meeting.internal.ical

import net.fortuna.ical4j.data.CalendarBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readBytes

/**
 * RFC 5545 and iTIP validation (test plan T1 `InviteValidationTest`): every golden file passes ical4j's strict
 * validation, memo's RFC 5546 rules for its method and the format checks; and those checks catch what they are for.
 */
class InviteValidationTest {
    private val goldenFiles: List<Path> = Files.list(Path.of("src/test/resources/ical")).use { files -> files.sorted().toList() }

    private fun kindOf(name: String) =
        when {
            name.startsWith("cancel") -> InviteKind.CANCEL
            name.startsWith("uninvite") -> InviteKind.UNINVITE
            name.startsWith("publish") -> InviteKind.PUBLISH
            else -> InviteKind.REQUEST
        }

    @Test
    fun `every golden file is valid for its method`() {
        assertThat(goldenFiles.map { it.name }).hasSize(GOLDEN_FILES)
        goldenFiles.forEach { InviteChecks.valid(it.readBytes(), kindOf(it.name)) }
    }

    @Test
    fun `the method rules catch what ical4j accepts`() {
        val cancel = golden("cancel-meeting.ics")
        val uninvite = golden("uninvite.ics")
        val publish = golden("publish-download.ics")
        val request = golden("request-asia-dhaka.ics")
        val attendee = "ATTENDEE;RSVP=FALSE:mailto:director@demo.example"

        assertThat(problems(cancel.without("ATTENDEE"), InviteKind.CANCEL)).contains("CANCEL names its ATTENDEE")
        assertThat(problems(cancel.replace("STATUS:CANCELLED", "STATUS:CONFIRMED"), InviteKind.CANCEL))
            .contains("CANCEL of the meeting has STATUS:CANCELLED")
        assertThat(problems(uninvite.replace("END:VEVENT", "STATUS:CANCELLED\r\nEND:VEVENT"), InviteKind.UNINVITE))
            .contains("CANCEL of one attendee has no STATUS")
        assertThat(
            problems(publish.replace("END:VEVENT", "$attendee\r\nEND:VEVENT"), InviteKind.PUBLISH),
        ).contains("PUBLISH has no ATTENDEE")
        assertThat(problems(request.without("SEQUENCE"), InviteKind.REQUEST)).contains("SEQUENCE is required once")
        assertThat(problems(request, InviteKind.CANCEL)).contains("METHOD must be CANCEL")
    }

    @Test
    fun `the format checks catch long lines, bare line ends and a TZID without its VTIMEZONE`() {
        val request = golden("request-asia-dhaka.ics")

        assertThat(IcsFolding.problems(request)).isEmpty()
        assertThat(IcsFolding.problems(request.replace("SUMMARY:Board Risk Committee", "SUMMARY:" + "x".repeat(80))))
            .anyMatch { it.endsWith("longer than 75 octets") }
        assertThat(IcsFolding.problems(request.replace("\r\n", "\n"))).isNotEmpty()
        assertThat(IcsFolding.problems(request.replace("TZID:Asia/Dhaka", "TZID:Asia/Kolkata"))).contains("no VTIMEZONE for a TZID")
        // Text that looks like a TZID parameter inside a value does not count (it is escaped and is not a parameter).
        assertThat(IcsFolding.problems(request.replace("SUMMARY:Board Risk Committee", "SUMMARY:a\\;TZID=Mars:b"))).isEmpty()
    }

    @Test
    fun `folding never splits a character and keeps every line within 75 octets`() {
        val line = "SUMMARY:" + "لجنة ".repeat(40) + "😀".repeat(30)
        val folded = IcsFolding.fold(line)

        assertThat(folded.removeSuffix("\r\n").split("\r\n")).allMatch { it.toByteArray(Charsets.UTF_8).size <= IcsFolding.MAX_OCTETS }
        assertThat(folded.replace("\r\n ", "").removeSuffix("\r\n")).isEqualTo(line)
        assertThat(IcsFolding.validUtf8(folded.toByteArray(Charsets.UTF_8))).isTrue()
    }

    private fun golden(name: String) = String(Path.of("src/test/resources/ical", name).readBytes(), Charsets.UTF_8)

    private fun String.without(property: String) =
        replace("\r\n ", "\u0000")
            .split("\r\n")
            .filterNot { it.startsWith(property) }
            .joinToString("\r\n")
            .replace("\u0000", "\r\n ")

    private fun problems(
        text: String,
        kind: InviteKind,
    ) = InviteRules.problems(CalendarBuilder().build(StringReader(text)), kind)

    private companion object {
        const val GOLDEN_FILES = 9
    }
}
