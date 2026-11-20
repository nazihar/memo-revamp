package com.memo.meeting

import com.memo.meeting.internal.MeetingStore
import com.memo.meeting.internal.attendance.AttendanceRules
import com.memo.meeting.internal.attendance.AttendanceStore
import com.memo.meeting.internal.attendance.MeetingProgressStore
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/** Why a person is on a meeting's register (design E05 §1.6). Only MEMBER and ALTERNATE can count for quorum. */
enum class AttendanceCapacity {
    /** A roster seat holder on the meeting's date. */
    MEMBER,

    /** A seat's alternate standing in for a member who sent apologies: counts for the seat's number only (ADR-0064). */
    ALTERNATE,

    /** "In attendance" for the whole meeting. */
    GUEST,

    /** "In attendance for item n": an item attendee or presenter. */
    ATTENDEE,
}

/** Where a person stands on the register (design E05 §1.6), derived from the events. */
enum class AttendanceState { EXPECTED, PRESENT, LEFT, APOLOGIES, ABSENT }

enum class AttendanceMode { IN_PERSON, REMOTE }

/** What the secretary records (design E05 §1.6); VOIDED cancels one earlier event, with a reason. */
enum class AttendanceEventKind {
    APOLOGIES,
    ABSENT,
    ARRIVED,
    MODE_CHANGED,
    LEFT,
    STEPPED_OUT,
    STEPPED_BACK,
    VOIDED,
}

/** RSVP: apologies written from the replies when the meeting started; RECORDED: by a secretary. */
enum class AttendanceSource { RECORDED, RSVP }

/** A flip of the live quorum (design E05 §1.7): quorate for the first time, lost, and restored after a loss. */
enum class QuorumChangeKind { REACHED, LOST, RESTORED }

/** One person on the register as E06's minutes read it. */
data class AttendeeRecord(
    val userId: UUID,
    val capacity: AttendanceCapacity,
    /** For an alternate: the seat (forum membership) and the member they stood in for. */
    val forMemberId: UUID?,
    val forUserId: UUID?,
    val state: AttendanceState,
    /** While PRESENT: in person or remote; for someone who left, the mode they last had. */
    val mode: AttendanceMode?,
    val firstArrivedAt: Instant?,
    val lastLeftAt: Instant?,
    /** The item in progress when they first arrived ("joined during item 4"). */
    val arrivedDuringItem: UUID?,
    /** The item in progress when they last left ("left during item 7"), while LEFT. */
    val leftDuringItem: UUID?,
    /** Arrived after the first item started, or ten minutes after the start when no item was started. */
    val joinedLate: Boolean,
    /** Left and not back (by the end, once the meeting is held). */
    val leftEarly: Boolean,
    /** The items they stepped out of (a recused member leaving the room). */
    val steppedOutOf: List<UUID>,
)

/** An arrival, a departure, a change of mode, a step out or back, apologies or absence, in time order (voided ones left out). */
data class AttendanceMovement(
    val eventId: UUID,
    val userId: UUID,
    val kind: AttendanceEventKind,
    val mode: AttendanceMode?,
    val at: Instant,
    /** The item in progress then, or the item stepped out of or back into. */
    val itemId: UUID?,
    /** Recorded after the meeting was held. */
    val correction: Boolean,
)

/** The item in progress from [startedAt] ("Now: item 5"). */
data class ItemInProgress(
    val itemId: UUID,
    val startedAt: Instant,
)

/** The meeting-level quorum reached, lost or restored at [at], during [itemId], with the numbers then. */
data class QuorumChangeRecord(
    val kind: QuorumChangeKind,
    val at: Instant,
    val itemId: UUID?,
    val needed: Int,
    val present: Int,
)

/** A meeting's attendance (design E05 §2) for E06's minutes and E08's analytics. */
data class MeetingAttendanceRecord(
    val meetingId: UUID,
    /** Everyone on the register, in no particular order. */
    val people: List<AttendeeRecord>,
    val movements: List<AttendanceMovement>,
    val itemsInProgress: List<ItemInProgress>,
    val quorumChanges: List<QuorumChangeRecord>,
    /** When the register was last corrected after the meeting was held; null when it never was. */
    val lastCorrectedAt: Instant?,
)

/**
 * The attendance register for other modules (ADR-0064, design E05 §2): who attended, how and when, alternates for whom,
 * apologies and absence, arrivals and departures by item, and the meeting's quorum lost and restored. Inside a tenant
 * transaction; no access decision is made here. Changes are told by [AttendanceChanged].
 */
@Component
class MeetingAttendance(
    private val meetings: MeetingStore,
    private val store: AttendanceStore,
    private val log: MeetingProgressStore,
) {
    /** The register of [meetingId]; null when there is no such meeting. Empty before the meeting starts (apologies aside). */
    fun of(meetingId: UUID): MeetingAttendanceRecord? {
        val meeting = meetings.find(meetingId) ?: return null
        val events = store.events(meeting.id)
        val progress = log.progress(meeting.id)
        val effective = AttendanceRules.effective(events)
        val threshold = AttendanceRules.lateAfter(meeting, progress)
        val byPerson = events.groupBy { it.userId }
        val people =
            store.rows(meeting.id).map { row ->
                val standing = AttendanceRules.replay(byPerson[row.userId].orEmpty())
                AttendeeRecord(
                    userId = row.userId,
                    capacity = row.capacity,
                    forMemberId = row.forMemberId,
                    forUserId = row.forUserId,
                    state = standing.state,
                    mode = standing.mode ?: standing.lastMode.takeIf { standing.state == AttendanceState.LEFT },
                    firstArrivedAt = standing.firstArrivedAt,
                    lastLeftAt = standing.lastLeftAt,
                    arrivedDuringItem = standing.arrivedDuringItem,
                    leftDuringItem = standing.leftDuringItem,
                    joinedLate = AttendanceRules.joinedLate(standing, threshold),
                    leftEarly = standing.state == AttendanceState.LEFT,
                    steppedOutOf = standing.steppedOutItems,
                )
            }
        return MeetingAttendanceRecord(
            meetingId = meeting.id,
            people = people,
            movements =
                effective.map {
                    AttendanceMovement(it.id, it.userId, it.kind, it.mode, it.at, it.itemId, it.correction)
                },
            itemsInProgress = progress.map { ItemInProgress(it.itemId, it.startedAt) },
            quorumChanges =
                log.changes(meeting.id).map {
                    QuorumChangeRecord(it.kind, it.at, it.itemId, it.needed, it.present)
                },
            lastCorrectedAt = events.filter { it.correction }.maxOfOrNull { it.recordedAt },
        )
    }
}
