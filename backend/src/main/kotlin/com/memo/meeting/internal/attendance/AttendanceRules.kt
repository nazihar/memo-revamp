package com.memo.meeting.internal.attendance

import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import com.memo.meeting.AttendanceCapacity
import com.memo.meeting.AttendanceEventKind
import com.memo.meeting.AttendanceMode
import com.memo.meeting.AttendanceSource
import com.memo.meeting.AttendanceState
import com.memo.meeting.MeetingStatus
import com.memo.meeting.internal.AudienceRow
import com.memo.meeting.internal.AudienceSource
import com.memo.meeting.internal.MeetingRow
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** One event of a register (design E05 §1.6), append-only. */
data class AttendanceEventRow(
    val id: UUID,
    val meetingId: UUID,
    val userId: UUID,
    val kind: AttendanceEventKind,
    val mode: AttendanceMode?,
    /** When it happened. */
    val at: Instant,
    /** The item in progress then, or the item stepped out of or back into. */
    val itemId: UUID?,
    val voidsEventId: UUID?,
    val reason: String?,
    val note: String?,
    val source: AttendanceSource,
    /** Recorded after the meeting was held. */
    val correction: Boolean,
    val recordedBy: UUID?,
    val recordedAt: Instant,
)

/** Where a person stands after some events (design E05 §1.6): the register row's derived part. */
data class Standing(
    val state: AttendanceState = AttendanceState.EXPECTED,
    /** While PRESENT. */
    val mode: AttendanceMode? = null,
    /** The mode they last had, kept after they left. */
    val lastMode: AttendanceMode? = null,
    /** The item they are out of the room for now, while PRESENT. */
    val steppedOutOf: UUID? = null,
    /** Every item they stepped out of, in order. */
    val steppedOutItems: List<UUID> = emptyList(),
    val firstArrivedAt: Instant? = null,
    val lastLeftAt: Instant? = null,
    val arrivedDuringItem: UUID? = null,
    val leftDuringItem: UUID? = null,
)

/**
 * The pure rules of the attendance register (ADR-0064, design E05 §1.6): which events each meeting status allows (and
 * which are corrections), the person's state machine, re-deriving a person's state from their events with the voided ones
 * left out, the times an event may carry, and the "joined late" flag. The table's CHECKs repeat the shapes.
 */
object AttendanceRules {
    const val MAX_REASON = 500
    const val MAX_NOTE = 300

    /** At most this many people in one "everyone who said yes is here". */
    const val MAX_ARRIVALS = 200

    /** An event's time lies between the start minus an hour and the end plus six hours (design E05 §1.6). */
    val BEFORE_START: Duration = Duration.ofHours(1)
    val AFTER_END: Duration = Duration.ofHours(6)

    /** Without an item started, arriving this long after the start is late (design E05 §1.6). */
    val LATE_AFTER_START: Duration = Duration.ofMinutes(10)

    /** A time this far ahead of the server's clock is accepted (a client's clock running fast). */
    val CLOCK_SKEW: Duration = Duration.ofMinutes(1)

    /** The events that take a time and an item: arrivals and departures, as opposed to apologies and absence. */
    private val TIMED =
        setOf(
            AttendanceEventKind.ARRIVED,
            AttendanceEventKind.MODE_CHANGED,
            AttendanceEventKind.LEFT,
            AttendanceEventKind.STEPPED_OUT,
            AttendanceEventKind.STEPPED_BACK,
        )

    /** What may be corrected once the meeting is held (with a reason). */
    private val CORRECTIONS =
        setOf(
            AttendanceEventKind.ARRIVED,
            AttendanceEventKind.LEFT,
            AttendanceEventKind.ABSENT,
            AttendanceEventKind.APOLOGIES,
            AttendanceEventKind.VOIDED,
        )

    private val FROZEN = setOf(MeetingStatus.MINUTES_APPROVED, MeetingStatus.CLOSED, MeetingStatus.CANCELLED)

    /** Whether [kind] takes a time within the meeting's window and an item label. */
    fun timed(kind: AttendanceEventKind): Boolean = kind in TIMED

    /**
     * Whether [kind] may be recorded while the meeting is [status], and whether it is then a correction (HELD): apologies
     * from scheduling, everything while in progress, corrections after the meeting until the minutes are approved;
     * 409 `WRONG_STATE` before or outside that, `FROZEN` once the minutes are approved or the meeting was cancelled.
     */
    fun window(
        kind: AttendanceEventKind,
        status: MeetingStatus,
    ): Boolean {
        refuseIf(status in FROZEN, "FROZEN") { "The register of this meeting is closed." }
        refuseIf(status == MeetingStatus.DRAFT, "WRONG_STATE") { "The meeting is not scheduled yet." }
        refuseIf(
            status == MeetingStatus.SCHEDULED && kind != AttendanceEventKind.APOLOGIES && kind != AttendanceEventKind.VOIDED,
            "WRONG_STATE",
        ) { "The register opens when the meeting starts; until then only apologies are recorded." }
        refuseIf(status == MeetingStatus.HELD && kind !in CORRECTIONS, "WRONG_STATE") {
            "The meeting is over: only arrivals, departures, apologies, absence and voiding are corrected."
        }
        return status == MeetingStatus.HELD
    }

    /** The person's state after [event] (one of theirs, not VOIDED); 409 `WRONG_STATE` when the event does not apply. */
    fun next(
        standing: Standing,
        event: AttendanceEventRow,
    ): Standing {
        val state = standing.state
        return when (event.kind) {
            AttendanceEventKind.APOLOGIES -> {
                wrongIf(state != AttendanceState.EXPECTED) { "Apologies are recorded for someone not yet marked." }
                standing.copy(state = AttendanceState.APOLOGIES)
            }

            AttendanceEventKind.ABSENT -> {
                wrongIf(state != AttendanceState.EXPECTED) { "Absent is recorded for someone not yet marked." }
                standing.copy(state = AttendanceState.ABSENT)
            }

            AttendanceEventKind.ARRIVED -> {
                wrongIf(state !in ARRIVING) { "This person is already marked ${state.name.lowercase()}." }
                standing.copy(
                    state = AttendanceState.PRESENT,
                    mode = event.mode,
                    lastMode = event.mode,
                    steppedOutOf = null,
                    firstArrivedAt = standing.firstArrivedAt ?: event.at,
                    arrivedDuringItem = if (standing.firstArrivedAt == null) event.itemId else standing.arrivedDuringItem,
                )
            }

            AttendanceEventKind.MODE_CHANGED -> {
                wrongIf(state != AttendanceState.PRESENT || standing.mode == event.mode) { "Only someone present changes how they attend." }
                standing.copy(mode = event.mode, lastMode = event.mode)
            }

            AttendanceEventKind.LEFT -> {
                wrongIf(state != AttendanceState.PRESENT) { "Only someone present leaves." }
                standing.copy(
                    state = AttendanceState.LEFT,
                    mode = null,
                    steppedOutOf = null,
                    lastLeftAt = event.at,
                    leftDuringItem = event.itemId,
                )
            }

            AttendanceEventKind.STEPPED_OUT -> {
                wrongIf(state != AttendanceState.PRESENT || standing.steppedOutOf != null) {
                    "Only someone present and in the room steps out."
                }
                val item = checkNotNull(event.itemId)
                standing.copy(steppedOutOf = item, steppedOutItems = (standing.steppedOutItems + item).distinct())
            }

            AttendanceEventKind.STEPPED_BACK -> {
                wrongIf(state != AttendanceState.PRESENT || standing.steppedOutOf != event.itemId) {
                    "This person did not step out of this item."
                }
                standing.copy(steppedOutOf = null)
            }

            AttendanceEventKind.VOIDED -> {
                error("a VOIDED event is not replayed")
            }
        }
    }

    /** The events that stand, in the order they happened: VOIDED events and the events they void left out. */
    fun effective(events: Collection<AttendanceEventRow>): List<AttendanceEventRow> {
        val voided = events.filter { it.kind == AttendanceEventKind.VOIDED }.mapNotNull { it.voidsEventId }.toSet()
        return events
            .filter { it.kind != AttendanceEventKind.VOIDED && it.id !in voided }
            .sortedWith(ORDER)
    }

    /**
     * One person's state from their [events] that happened by [until] (all of them when null), voided ones left out; 409
     * `WRONG_STATE` when they do not make a valid sequence (an insertion or a void that would break it).
     */
    fun replay(
        events: Collection<AttendanceEventRow>,
        until: Instant? = null,
    ): Standing =
        effective(events)
            .filter { until == null || !it.at.isAfter(until) }
            .fold(Standing()) { standing, event -> next(standing, event) }

    /** [target] may be voided: it is not itself a void and was not voided already (409 `WRONG_STATE`). */
    fun voidable(
        target: AttendanceEventRow,
        events: Collection<AttendanceEventRow>,
    ) {
        wrongIf(target.kind == AttendanceEventKind.VOIDED) { "A void cannot be voided: record the event again." }
        wrongIf(events.any { it.voidsEventId == target.id }) { "This event is already voided." }
    }

    /**
     * An event's time (422 `TIME_OUT_OF_RANGE`): never ahead of [now]; for arrivals and departures, between the start minus
     * an hour and the end plus six hours.
     */
    fun time(
        kind: AttendanceEventKind,
        at: Instant,
        meeting: MeetingRow,
        now: Instant,
    ): FieldProblem? {
        val future = at.isAfter(now.plus(CLOCK_SKEW))
        val outside = timed(kind) && (at.isBefore(meeting.startsAt.minus(BEFORE_START)) || at.isAfter(meeting.endsAt.plus(AFTER_END)))
        return FieldProblem("at", "TIME_OUT_OF_RANGE", "Give a time during the meeting, not in the future.").takeIf { future || outside }
    }

    /** When arriving starts to be late: when the first item started, else ten minutes after the start. */
    fun lateAfter(
        meeting: MeetingRow,
        progress: List<ItemProgressRow>,
    ): Instant = progress.minOfOrNull { it.startedAt } ?: meeting.startsAt.plus(LATE_AFTER_START)

    /** Arrived (for the first time) after [threshold]. */
    fun joinedLate(
        standing: Standing,
        threshold: Instant,
    ): Boolean = standing.firstArrivedAt?.isAfter(threshold) == true

    /** The register capacity of a person from their current audience [rows]; null when they are not in the audience. */
    fun capacity(rows: Collection<AudienceRow>): AttendanceCapacity? {
        val sources = rows.map { it.source }.toSet()
        return when {
            AudienceSource.MEMBER in sources -> AttendanceCapacity.MEMBER
            AudienceSource.ALTERNATE in sources -> AttendanceCapacity.ALTERNATE
            AudienceSource.GUEST in sources -> AttendanceCapacity.GUEST
            AudienceSource.ATTENDEE in sources -> AttendanceCapacity.ATTENDEE
            else -> null
        }
    }

    private val ARRIVING = setOf(AttendanceState.EXPECTED, AttendanceState.APOLOGIES, AttendanceState.LEFT)

    private val ORDER = compareBy<AttendanceEventRow>({ it.at }, { it.recordedAt }, { it.id })

    private inline fun wrongIf(
        condition: Boolean,
        message: () -> String,
    ) = refuseIf(condition, "WRONG_STATE", message)
}
