package com.memo.org

import com.memo.common.events.TenantScopedEvent
import java.util.UUID

/** Something in the org directory changed (ids only, ADR-0017); later modules refresh routing caches on it. */
data class OrgChanged(
    override val tenantId: UUID,
    /** `legal_entity`, `unit`, `position`, `assignment` or `reporting_line`. */
    val kind: String,
    val objectId: UUID,
) : TenantScopedEvent
