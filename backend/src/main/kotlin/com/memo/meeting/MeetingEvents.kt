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

    /** Its minutes were confirmed by the chair (E06, ADR-0066): HELD → MINUTES_APPROVED; attendance and declarations freeze. */
    MINUTES_APPROVED,
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

/**
 * A declaration of interest changed (ADR-0062, design E04 §2): made, its intent changed, ruled on or withdrawn, published
 * in the changing transaction once per change. [recused] is whether [userId] is now recused from [itemId] by it (an
 * effective declaration: RECUSED or AWAITING_RULING). E05 refreshes live quorum, E06 its decision screen, E08 its search
 * rows; they read the rest through [Recusals]. Ids only (ADR-0017).
 */
data class DeclarationChanged(
    override val tenantId: UUID,
    val meetingId: UUID,
    val itemId: UUID,
    val userId: UUID,
    val recused: Boolean,
) : TenantScopedEvent

/**
 * A meeting's attendance changed (ADR-0064, design E05 §2): an event was recorded or voided on its register, published
 * in the changing transaction. E06 refreshes its decision screen and reads the register through [MeetingAttendance].
 * Ids only (ADR-0017).
 */
data class AttendanceChanged(
    override val tenantId: UUID,
    val meetingId: UUID,
) : TenantScopedEvent
