package com.memo.meeting

import com.memo.common.events.TenantScopedEvent
import java.util.UUID

/** What happened to a meeting (design E03 §2). */
enum class MeetingChange {
    CREATED,
    UPDATED,
    SCHEDULED,
    RESCHEDULED,
    STARTED,
    ENDED,
    CANCELLED,

    /** Its agenda was first published and it took its number ("BRC 3/2026"). */
    NUMBERED,

    /** A guest was added or removed. */
    GUESTS,
}

/**
 * A meeting changed (design E03 §2), published in the changing transaction for E04–E07 (declarations, attendance,
 * minutes, matters arising). Ids only (ADR-0017).
 */
data class MeetingChanged(
    override val tenantId: UUID,
    val meetingId: UUID,
    val change: MeetingChange,
) : TenantScopedEvent

/**
 * Revision [revision] of a meeting's agenda was published (ADR-0060, design E03 §2, §9), in the publishing transaction.
 * The meeting module tells the audience after the commit; E04–E07 read the revision through the meeting's published API.
 */
data class AgendaPublished(
    override val tenantId: UUID,
    val meetingId: UUID,
    val revision: Int,
) : TenantScopedEvent
