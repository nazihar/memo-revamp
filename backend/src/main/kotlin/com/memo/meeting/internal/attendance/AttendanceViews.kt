package com.memo.meeting.internal.attendance

import com.memo.forum.ForumRole
import com.memo.forum.UnmetCondition
import com.memo.meeting.AttendanceCapacity
import com.memo.meeting.AttendanceEventKind
import com.memo.meeting.AttendanceMode
import com.memo.meeting.AttendanceSource
import com.memo.meeting.AttendanceState
import com.memo.meeting.MeetingStatus
import com.memo.meeting.QuorumChangeKind
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

/** A person named on the register: the member an alternate stands in for, the alternate who fills a seat. */
data class AttendancePersonRef(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val userId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
)

/** One person on the register (design E05 §1.6, flow X). */
data class AttendancePersonResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val userId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val capacity: AttendanceCapacity,
    /** A member's role on the roster on the meeting's date. */
    val role: ForumRole?,
    /** For an alternate: the member they stand in for. */
    val standingInFor: AttendancePersonRef?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val state: AttendanceState,
    /** While PRESENT: in person or online. */
    val mode: AttendanceMode?,
    /** The item they are out of the room for now. */
    val steppedOutOf: UUID?,
    val firstArrivedAt: Instant?,
    val lastLeftAt: Instant?,
    /** "Joined during item 4": the item in progress at their first arrival. */
    val arrivedDuringItem: UUID?,
    /** "Left during item 7": the item in progress when they last left. */
    val leftDuringItem: UUID?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val joinedLate: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val leftEarly: Boolean,
    /** They fill a seat of the live meeting-level quorum now. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val counted: Boolean,
    /** Send as `If-Match` when recording for this person; null while they have no register row yet. */
    val version: Long?,
)

/** One event of the register, as officers see it (corrections and voids included). */
data class AttendanceEventResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val userId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val kind: AttendanceEventKind,
    val mode: AttendanceMode?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val at: Instant,
    val itemId: UUID?,
    /** For VOIDED: the event it cancels. */
    val voidsEventId: UUID?,
    /** Why it was voided or corrected. */
    val reason: String?,
    val note: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val source: AttendanceSource,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val correction: Boolean,
    /** A later VOIDED event cancels this one. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val voided: Boolean,
    val recordedBy: AttendancePersonRef?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val recordedAt: Instant,
)

/** How many people are where on the register. */
data class AttendanceCountsResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val inPerson: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val remote: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val left: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val apologies: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val absent: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val expected: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val total: Int,
)

/** A roster seat in the live quorum (officers): its holder, and who fills it now. */
data class QuorumSeatResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val memberId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val holder: AttendancePersonRef,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val role: ForumRole,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val voting: Boolean,
    /** The holder, or the alternate standing in; null when the seat is empty. */
    val filledBy: AttendancePersonRef?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val byAlternate: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val remote: Boolean,
)

/**
 * The meeting-level quorum at an instant (design E05 §1.7, flow Y): quorate or not, the numbers and the unmet conditions in
 * parts the web turns into a sentence; the seats for the officers only.
 */
data class LiveQuorumResponse(
    /** The instant asked for; null: now. */
    val at: Instant?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val quorate: Boolean,
    /** Counting members in office, or the fixed seats. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val basis: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val needed: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val present: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val unmet: List<UnmetCondition>,
    /** The seats and who fills them, for the officers; null for members and alternates. */
    val seats: List<QuorumSeatResponse>?,
)

/** A flip of the meeting's quorum (design E05 §1.7): reached, lost, restored. */
data class QuorumChangeResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val kind: QuorumChangeKind,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val at: Instant,
    /** The item in progress then. */
    val itemId: UUID?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val needed: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val present: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val unmet: List<UnmetCondition>,
    val causeEventId: UUID?,
)

/** "Now: item n" from [startedAt]. */
data class ItemProgressResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val itemId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val startedAt: Instant,
)

/**
 * A meeting's register (design E05 §3, §4, flow X): every person and event for the officers; the viewer's own row and the
 * counts for the audience; the live meeting-level quorum for officers (with seats), members and alternates (numbers).
 */
data class AttendanceResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val meetingId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val status: MeetingStatus,
    /** Whether the viewer may record (a secretary or `forums.manage`, the register open for them). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val canRecord: Boolean,
    /** The meeting is held: what is recorded now is a correction and needs a reason. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val correcting: Boolean,
    val itemInProgress: ItemProgressResponse?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val counts: AttendanceCountsResponse,
    /** The viewer's own row, when they are on the register. */
    val mine: AttendancePersonResponse?,
    /** Everyone, for the officers; null for everyone else. */
    val people: List<AttendancePersonResponse>?,
    /** Every event, oldest first, for the officers; null for everyone else. */
    val events: List<AttendanceEventResponse>?,
    val quorum: LiveQuorumResponse?,
)

/** An event recorded: the event, the person's row after it (its version is the new ETag) and the live quorum. */
data class RecordedAttendanceResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val event: AttendanceEventResponse,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val person: AttendancePersonResponse,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val quorum: LiveQuorumResponse,
)
