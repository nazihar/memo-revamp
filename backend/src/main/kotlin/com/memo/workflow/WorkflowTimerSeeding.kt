package com.memo.workflow

import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.workflow.internal.InstanceStore
import com.memo.workflow.internal.TimerCatchUp
import com.memo.workflow.internal.instanceOfSubject
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Timers for seeders (the demo tenant, Phase 4 E07 T12), as the user bound to the current unit of work or the system.
 * Never called from requests.
 */
@Service
class WorkflowTimerSeeding(
    private val instances: InstanceStore,
    private val catchUp: TimerCatchUp,
    private val transactions: TenantTransactions,
) {
    /**
     * Brings the timers of the running instance of [subjectType] [subjectId] up to now, as if the engine had run while its
     * due time passed (an action issued as at a past meeting, its date long gone): the due time fires and, with
     * [escalate], the escalation its step names fires at once (the reminders in between are skipped, as they are for any
     * due time already past). How many timers fired; 0 without an instance.
     */
    fun catchUp(
        subjectType: String,
        subjectId: UUID,
        escalate: Boolean,
    ): Int {
        val instance = transactions.inTenant(TenantContext.current()) { instances.instanceOfSubject(subjectType, subjectId) } ?: return 0
        return catchUp.catchUp(instance, escalate)
    }
}
