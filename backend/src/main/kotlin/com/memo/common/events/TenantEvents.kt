package com.memo.common.events

import com.memo.common.context.ActorContext
import com.memo.common.tenant.TenantId
import com.memo.common.tenant.TenantTransactions
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * An application event about one tenant's data (ADR-0017). The tenant travels in the event because a listener may run long
 * after the publishing request (resubmission of an incomplete publication). Events are stored in the event publication
 * registry, a platform table, so they carry ids only, never content (`EventPayloadRulesTest`).
 */
interface TenantScopedEvent {
    val tenantId: UUID
}

/** An application event with no tenant (platform jobs, audit-chain alerts). Ids only as well. */
interface PlatformEvent

/**
 * The only way a listener of a [TenantScopedEvent] touches data: binds the event's tenant and a system actor named after
 * the listener, and runs [block] in a tenant transaction. Listeners are `@Async @TransactionalEventListener` methods.
 */
@Component
class TenantEvents(
    private val transactions: TenantTransactions,
) {
    fun <T> handle(
        event: TenantScopedEvent,
        listener: String,
        block: () -> T,
    ): T = ActorContext.asSystem("event:$listener") { transactions.inTenant(TenantId(event.tenantId), block) }
}
