package com.memo.identity.session

import com.memo.jooq.tables.references.IDENTITY_HTTP_SESSION
import com.memo.tenancy.TenantSuspended
import org.jooq.DSLContext
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * A suspended tenant's sessions end in the suspension's own transaction (ADR-0031). The session store is a platform
 * table keyed by the principal index `<tenantId>:<userId>`; the resolution filter refuses the host meanwhile anyway.
 */
@Component
class TenantSuspensionSessions(
    private val dsl: DSLContext,
) {
    @EventListener
    fun onSuspended(event: TenantSuspended) {
        dsl
            .deleteFrom(IDENTITY_HTTP_SESSION)
            .where(IDENTITY_HTTP_SESSION.PRINCIPAL_NAME.like("${event.tenantId}:%"))
            .execute()
    }
}
