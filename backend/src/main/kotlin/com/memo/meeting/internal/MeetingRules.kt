package com.memo.meeting.internal

import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import com.memo.meeting.MeetingMode
import com.memo.meeting.MeetingStatus
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * What a secretary does to a meeting, and the statuses it may start from (the state machine of design E03 §1.1). [to] is
 * the status it moves to; null keeps the status. Anything else answers 409 `WRONG_STATE`.
 */
enum class MeetingAction(
    val from: Set<MeetingStatus>,
    val to: MeetingStatus?,
) {
    /** Title, kind, place, mode, link, classification. */
    EDIT(setOf(MeetingStatus.DRAFT, MeetingStatus.SCHEDULED), null),
    SCHEDULE(setOf(MeetingStatus.DRAFT), MeetingStatus.SCHEDULED),

    /** New times; a draft's times change the same way, without a reason. */
    RESCHEDULE(setOf(MeetingStatus.DRAFT, MeetingStatus.SCHEDULED), null),
    START(setOf(MeetingStatus.SCHEDULED), MeetingStatus.IN_PROGRESS),
    END(setOf(MeetingStatus.IN_PROGRESS), MeetingStatus.HELD),

    /** A draft is discarded, a scheduled meeting called off; either way with a reason. */
    CANCEL(setOf(MeetingStatus.DRAFT, MeetingStatus.SCHEDULED), MeetingStatus.CANCELLED),

    /** Add or remove guests: until the meeting is held. */
    GUESTS(setOf(MeetingStatus.DRAFT, MeetingStatus.SCHEDULED, MeetingStatus.IN_PROGRESS), null),
    ;

    fun allows(status: MeetingStatus): Boolean = status in from

    /** The status after the action from [status]; refused with `WRONG_STATE` when the action is not allowed there. */
    fun next(status: MeetingStatus): MeetingStatus {
        refuseIf(!allows(status), "WRONG_STATE") { "A meeting that is ${status.name.lowercase()} cannot be ${verb()}." }
        return to ?: status
    }

    private fun verb(): String =
        when (this) {
            EDIT -> "edited"
            SCHEDULE -> "scheduled"
            RESCHEDULE -> "rescheduled"
            START -> "started"
            END -> "ended"
            CANCEL -> "cancelled"
            GUESTS -> "given or relieved of guests"
        }
}

/** A meeting's place and labels as a secretary sends them (design E03 §1.1). */
data class MeetingPlace(
    val title: String?,
    val mode: MeetingMode,
    val location: String?,
    val joinUrl: String?,
    val joinDetails: String?,
)

/** Local start and end in [zone] (null: the organisation's); resolved to instants by [MeetingTimes.resolve]. */
data class LocalTimes(
    val startsAt: LocalDateTime,
    val endsAt: LocalDateTime,
    val zone: String?,
)

data class ResolvedTimes(
    val zone: ZoneId,
    val startsAt: Instant,
    val endsAt: Instant,
)

/** The field checks of a meeting (design E03 §1.1), pure: texts, place by mode, the join link, times in a zone. */
object MeetingFields {
    const val MAX_TITLE = 200
    const val MAX_LOCATION = 500
    const val MAX_URL = 2000
    const val MAX_DETAILS = 1000
    const val MAX_REASON = 500
    const val MAX_NOTE = 200

    /** [place] with blank texts as null; problems by field. */
    fun place(place: MeetingPlace): Pair<MeetingPlace, List<FieldProblem>> {
        val clean =
            place.copy(
                title = place.title.clean(),
                location = place.location.clean(),
                joinUrl = place.joinUrl.clean(),
                joinDetails = place.joinDetails.clean(),
            )
        val needsLocation = clean.mode != MeetingMode.ONLINE
        val needsLink = clean.mode != MeetingMode.IN_PERSON
        val problems =
            listOfNotNull(
                tooLong("title", clean.title, MAX_TITLE),
                FieldProblem("location", "REQUIRED", "Say where the meeting is held.").takeIf { needsLocation && clean.location == null },
                tooLong("location", clean.location, MAX_LOCATION),
                FieldProblem("joinUrl", "REQUIRED", "Give the link to join online.").takeIf { needsLink && clean.joinUrl == null },
                clean.joinUrl?.let(::link),
                tooLong("joinDetails", clean.joinDetails, MAX_DETAILS),
            )
        return clean to problems
    }

    /**
     * A join link: `https` only, a host, no user name or password in it, at most [MAX_URL] characters. It is a plain field:
     * the server never calls it (threat model T4).
     */
    fun link(url: String): FieldProblem? {
        val uri = runCatching { URI(url) }.getOrNull()
        return when {
            url.length > MAX_URL -> FieldProblem("joinUrl", "LENGTH", "At most $MAX_URL characters.")
            uri == null || uri.host.isNullOrBlank() -> FieldProblem("joinUrl", "FORMAT", "Not a web address.")
            !uri.scheme.equals("https", ignoreCase = true) -> FieldProblem("joinUrl", "LINK_NOT_HTTPS", "Use an https:// link.")
            uri.rawUserInfo != null -> FieldProblem("joinUrl", "LINK_CREDENTIALS", "Leave the user name and password out of the link.")
            else -> null
        }
    }

    /** A required reason of 1 to [MAX_REASON] characters (cancel, reschedule a scheduled meeting). */
    fun reason(text: String?): Pair<String?, FieldProblem?> {
        val clean = text.clean()
        val problem =
            when {
                clean == null -> FieldProblem("reason", "REASON_REQUIRED", "Say why.")
                clean.length > MAX_REASON -> FieldProblem("reason", "LENGTH", "At most $MAX_REASON characters.")
                else -> null
            }
        return clean to problem
    }

    fun tooLong(
        field: String,
        text: String?,
        max: Int,
    ): FieldProblem? = FieldProblem(field, "LENGTH", "At most $max characters.").takeIf { (text?.length ?: 0) > max }

    fun String?.clean(): String? = this?.trim()?.ifEmpty { null }
}

/**
 * Meeting times (design E03 §1.1): local date-times in an IANA zone known to the JVM, stored as instants. A local time in
 * a daylight-saving gap does not exist and is refused (`UNKNOWN_LOCAL_TIME`); one in an overlap takes the earlier offset.
 * A meeting ends after it starts and lasts at most [MAX_LENGTH].
 */
object MeetingTimes {
    val MAX_LENGTH: Duration = Duration.ofDays(7)

    /** The IANA zone [name], or null when the JVM does not know it (offsets such as `+06:00` are not zone names). */
    fun zone(name: String): ZoneId? = name.trim().takeIf { it in ZoneId.getAvailableZoneIds() }?.let(ZoneId::of)

    /** [local] in [zone]: null in a gap; the earlier offset in an overlap. */
    fun instant(
        local: LocalDateTime,
        zone: ZoneId,
    ): Instant? = if (zone.rules.getValidOffsets(local).isEmpty()) null else ZonedDateTime.ofLocal(local, zone, null).toInstant()

    /** [times] as instants; a missing zone is [fallback] (the organisation's, which may be a fixed offset). */
    fun resolve(
        times: LocalTimes,
        fallback: ZoneId = ZoneOffset.UTC,
    ): Pair<ResolvedTimes?, List<FieldProblem>> {
        val named = times.zone
        val zone =
            (if (named == null) fallback else zone(named))
                ?: return null to listOf(FieldProblem("timeZone", "UNKNOWN_ZONE", "Not a known time zone."))
        val start = instant(times.startsAt, zone)
        val end = instant(times.endsAt, zone)
        val problems =
            listOfNotNull(
                FieldProblem("startsAt", "UNKNOWN_LOCAL_TIME", "This time does not exist on that day (the clocks change).")
                    .takeIf { start == null },
                FieldProblem("endsAt", "UNKNOWN_LOCAL_TIME", "This time does not exist on that day (the clocks change).")
                    .takeIf { end == null },
                FieldProblem("endsAt", "END_BEFORE_START", "End after the start.")
                    .takeIf { start != null && end != null && !end.isAfter(start) },
                FieldProblem("endsAt", "TOO_LONG", "A meeting lasts at most 7 days.")
                    .takeIf { start != null && end != null && Duration.between(start, end) > MAX_LENGTH },
            )
        val resolved = if (start != null && end != null && problems.isEmpty()) ResolvedTimes(zone, start, end) else null
        return resolved to problems
    }
}
