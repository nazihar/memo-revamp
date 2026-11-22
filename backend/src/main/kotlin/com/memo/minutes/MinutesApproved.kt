package com.memo.minutes

import com.memo.common.events.TenantScopedEvent
import java.util.UUID

/**
 * A meeting's minutes were confirmed by the chair (design E06 §2, ADR-0066), published in the confirming transaction for
 * E07 (actions closed at approval) and E08 (the register's approval dates): the meeting is MINUTES_APPROVED and its
 * decisions final. Ids only (ADR-0017).
 */
data class MinutesApproved(
    override val tenantId: UUID,
    val meetingId: UUID,
    val forumId: UUID,
) : TenantScopedEvent
