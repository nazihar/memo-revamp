package com.memo.meeting

import com.memo.meeting.internal.AudienceStore
import com.memo.meeting.internal.MeetingRow
import com.memo.meeting.internal.MeetingStore
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Where a meeting stands (ADR-0060, design E03 §1.1). E03 moves it up to HELD or CANCELLED; the rest is E06's. */
enum class MeetingStatus {
    DRAFT,
    SCHEDULED,
    IN_PROGRESS,
    HELD,
    MINUTES_APPROVED,
    CLOSED,
    CANCELLED,
}

/** A label only: ordinary and special meetings share one number series. */
enum class MeetingKind { ORDINARY, SPECIAL }

enum class MeetingMode { IN_PERSON, ONLINE, HYBRID }

/** A meeting as other modules see it (E04–E07): no access decision is made here. */
data class MeetingRef(
    val id: UUID,
    val forumId: UUID,
    /** The forum type version pinned at creation: its policies apply. */
    val typeVersion: Int,
    val kind: MeetingKind,
    val title: String?,
    val startsAt: Instant,
    val endsAt: Instant,
    val timeZone: ZoneId,
    val mode: MeetingMode,
    val classificationId: UUID,
    /** "BRC 3/2026", set when the agenda is first published. */
    val label: String?,
    val status: MeetingStatus,
    /** The meeting's number in the committee's series (the 3 of "BRC 3/2026"), set with [label]. */
    val number: Int? = null,
)

/**
 * Meetings for other modules (design E03 §2): find one, a forum's, and those a user is in the audience of. Inside a
 * tenant transaction; callers decide access themselves.
 */
@Component
class Meetings(
    private val store: MeetingStore,
    private val audience: AudienceStore,
) {
    fun find(id: UUID): MeetingRef? = store.find(id)?.ref()

    /** The forum's meetings, oldest first. */
    fun ofForum(forumId: UUID): List<MeetingRef> = store.ofForum(forumId).map { it.ref() }

    /** The meetings [userId] is in the audience of (as a member, guest or item attendee), oldest first. */
    fun meetingsOf(userId: UUID): List<MeetingRef> = store.findAll(audience.meetingsOf(userId)).map { it.ref() }

    /**
     * [id] with its row locked to the end of the transaction, as attendance and quorum snapshots lock it (E06: after the
     * submission rows and the decision row, before the agenda); null when there is none. Its status is then current: a
     * meeting marked as held meanwhile reads HELD.
     */
    fun locked(id: UUID): MeetingRef? = store.lock(id)?.ref()
}

internal fun MeetingRow.ref() =
    MeetingRef(id, forumId, typeVersion, kind, title, startsAt, endsAt, zone, mode, classificationId, label, status, number)
