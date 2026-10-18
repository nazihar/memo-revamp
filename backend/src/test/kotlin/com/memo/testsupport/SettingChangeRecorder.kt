package com.memo.testsupport

import com.memo.common.context.ActorContext
import com.memo.common.events.TenantEvents
import com.memo.common.tenant.TenantContext
import com.memo.tenancy.TenantSettingChanged
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Records what a tenant-event listener saw; can be told to fail for a key until released (resubmission tests). */
@Component
class SettingChangeRecorder(
    private val tenantEvents: TenantEvents,
    private val probe: TransactionProbe,
) {
    @Async
    @TransactionalEventListener
    fun on(event: TenantSettingChanged) =
        tenantEvents.handle(event, "setting-recorder") {
            if (event.settingKey in FAILING) error("listener told to fail for ${event.settingKey}")
            val scoped = TenantContext.currentOrNull()?.toString()
            SEEN += Seen(event.settingKey, scoped, probe.databaseTenant(), ActorContext.current().actor.id)
        }

    data class Seen(
        val key: String,
        val scopedTenant: String?,
        val databaseTenant: String?,
        val actor: String?,
    )

    companion object {
        val SEEN = CopyOnWriteArrayList<Seen>()
        val FAILING: MutableSet<String> = ConcurrentHashMap.newKeySet()
    }
}
