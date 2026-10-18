package com.memo.tenancy

import com.memo.common.events.TenantScopedEvent
import java.util.UUID

/** A tenant was created (ids only; ADR-0017). */
data class TenantCreated(
    override val tenantId: UUID,
) : TenantScopedEvent

/** A tenant setting was created, changed or deleted (ids only; the value stays in the database). */
data class TenantSettingChanged(
    override val tenantId: UUID,
    val settingKey: String,
) : TenantScopedEvent
