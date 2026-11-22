package com.memo.meeting

import com.memo.forum.StandingKind
import com.memo.meeting.internal.MeetingLifecycle
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * The meeting's side of its minutes (design E06 §2, ADR-0066), for the minutes module. Inside a tenant transaction; the
 * minutes module has checked who confirms.
 */
@Component
class MeetingMinutes(
    private val lifecycle: MeetingLifecycle,
) {
    /**
     * [meetingId]'s minutes were confirmed: HELD → MINUTES_APPROVED with a guarded update (the meeting row locked: call it
     * after the meeting's decision rows, before the minutes' row), audited `meeting.minutes_approved` and told as
     * [MeetingChange.MINUTES_APPROVED]. Attendance and declarations are frozen from then on, and guests and item attendees
     * stop reading the meeting (ADR-0061). 409 `WRONG_STATE` for a meeting not held.
     */
    fun approved(meetingId: UUID): MeetingRef = lifecycle.minutesApproved(meetingId).ref()
}

/** A link a standing item of the agenda carries for its readers (design E06 §2): "Minutes of BRC 3/2026 — read your copy". */
data class ItemLink(
    /** What the link opens, for the client: `MINUTES` (the minutes of a meeting), `ACTION_REPORT` (E07). */
    val kind: String,
    /** The meeting the link is about (the earlier meeting whose minutes these are). */
    val meetingId: UUID,
    /** The words to show, without any item's content ("Minutes of BRC 3/2026"). */
    val label: String,
    /** Where the linked thing stands, for the client's wording (the minutes' stage, for example `CIRCULATED`). */
    val state: String?,
    /** For minutes approved by circulation: when ("approved by circulation on …, for noting"). */
    val at: Instant? = null,
    /** The linked meeting's number ("BRC 3/2026"), for the client's own words in the reader's language; null before it has one. */
    val meetingLabel: String? = null,
    /** When the linked meeting started, for the client's words when it has no number. */
    val meetingStartsAt: Instant? = null,
    /** The linked meeting's time zone, to say its date on its own calendar. */
    val meetingTimeZone: String? = null,
)

/**
 * Links shown on a standing item of the members' agenda (design E06 §2, E07 §11): the meeting module knows neither the
 * minutes nor the actions, so each module that has something to show on a kind of standing item provides one of these
 * (several may: minutes for PREVIOUS_MINUTES, the action taken report for MATTERS_ARISING). Called inside the reading
 * transaction for the signed-in actor, only for an item the actor sees in full; the implementation decides what the actor
 * may be shown.
 */
interface ItemLinks {
    /** The kind of standing item this provider links from. */
    val standingKind: StandingKind

    /** The links item [itemId] of [meetingId]'s published agenda carries for the actor; empty when none. */
    fun links(
        meetingId: UUID,
        itemId: UUID,
    ): List<ItemLink>
}

/**
 * A part of the hourly meeting sweep that another module adds (design E06 §9: the minutes' deadlines and reminders),
 * called once per run inside the sweep's tenant transaction, after the meeting module's own parts. Answers how many
 * things it sent or moved. Each part must be idempotent: the sweep may run twice in an hour.
 */
interface MeetingSweepStep {
    fun sweep(now: Instant): Int
}

/** A meeting's minutes as "My meetings" shows them (design E06 §3): their stage and what the viewer has to do. */
data class MeetingMinutesSummary(
    /** The minutes' stage: DRAFT, CHAIR_REVIEW, CIRCULATED, AWAITING_CHAIR or APPROVED. */
    val state: String,
    /** READ, COMMENT, REVIEW or CONFIRM; null when there is nothing for the viewer to do or read. */
    val myAction: String?,
)

/**
 * The minutes' line of each meeting in "My meetings" (design E06 §3), provided by the minutes module; none without it.
 * Called inside the reading transaction for the signed-in actor, with the meetings the list shows.
 */
interface MeetingMinutesSummaries {
    fun of(meetingIds: Collection<UUID>): Map<UUID, MeetingMinutesSummary>
}
