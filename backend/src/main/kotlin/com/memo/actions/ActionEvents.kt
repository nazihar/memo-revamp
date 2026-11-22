package com.memo.actions

import com.memo.common.events.TenantScopedEvent
import java.util.UUID

/** What happened to an action item (design E07 §2.1), for E08's indexing and analytics. */
enum class ActionChange {
    ISSUED,
    UPDATED,
    DUE_CHANGED,
    DONE,
    CLOSED,
    CANCELLED,
    REOPENED,
}

/**
 * An action item changed (design E07 §2.1), published in the changing transaction. A draft is never told: it becomes an
 * action when it is issued. Ids only (ADR-0017).
 */
data class ActionChanged(
    override val tenantId: UUID,
    val actionId: UUID,
    val forumId: UUID,
    val kind: ActionChange,
) : TenantScopedEvent
