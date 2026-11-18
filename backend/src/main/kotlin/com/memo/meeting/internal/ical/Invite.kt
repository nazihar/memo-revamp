package com.memo.meeting.internal.ical

import com.memo.meeting.MeetingMode
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

/**
 * What an invitation file is for (ADR-0063, design E05 §1.2), with its iTIP method: an invitation or update (REQUEST),
 * the meeting called off (CANCEL with `STATUS:CANCELLED`), one person uninvited (CANCEL without STATUS), and the "Add to
 * my calendar" download (PUBLISH, no ATTENDEE).
 */
enum class InviteKind(
    val method: String,
) {
    REQUEST("REQUEST"),
    CANCEL("CANCEL"),
    UNINVITE("CANCEL"),
    PUBLISH("PUBLISH"),
}

/**
 * The meeting as an invitation describes it, and nothing more: no agenda, item, paper, classification or cancellation
 * reason ever reaches the writer (threat I1). The caller picks [summary] (the meeting's title, the committee's name, or
 * the type's neutral title, which also sets [confidential]).
 */
data class InviteEvent(
    /** The invitation's random UID (RFC 7986 §5.3), kept for the meeting's life. */
    val uid: UUID,
    val sequence: Int,
    val startsAt: Instant,
    val endsAt: Instant,
    /** The meeting's IANA zone: DTSTART and DTEND carry it as TZID, with a VTIMEZONE built from the JVM's rules. */
    val zone: ZoneId,
    val summary: String,
    /** True with a neutral title: `CLASS:PRIVATE` (kept only if the recorded run shows clients accept it, ADR-0063). */
    val confidential: Boolean,
    val mode: MeetingMode,
    val location: String?,
    val joinUrl: String?,
    val joinDetails: String?,
    /** The meeting page in the web app (absolute https URL). */
    val meetingUrl: String,
)

/** A calendar user: a display name and an email address. */
data class InviteParty(
    val name: String,
    val email: String,
)

/**
 * One file for one recipient (ADR-0063): [organizer] is the tenant's sending address with the committee's name;
 * [attendee] is the recipient alone (none for PUBLISH); a [guest] is an optional participant. Texts are in [locale].
 */
data class Invite(
    val kind: InviteKind,
    val event: InviteEvent,
    val organizer: InviteParty,
    val attendee: InviteParty?,
    val guest: Boolean = false,
    val locale: Locale = Locale.ENGLISH,
)

/** An input the writer will not turn into a file (a malformed address or link, times in the wrong order). */
class InviteRefusedException(
    message: String,
) : IllegalArgumentException(message)

/**
 * A built file that failed validation (ical4j's strict rules, the iTIP rules for its method, or the file format): the
 * caller fails its job and sends nothing (ADR-0063). [problems] name properties and rules, never content.
 */
class InviteInvalidException(
    val problems: List<String>,
) : IllegalStateException("invitation failed validation: ${problems.joinToString("; ")}")
