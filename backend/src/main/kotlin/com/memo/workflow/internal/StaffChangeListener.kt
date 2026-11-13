package com.memo.workflow.internal

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.tenant.TenantDsl
import com.memo.identity.UserDeactivated
import com.memo.jooq.tables.references.WORKFLOW_STEP
import com.memo.jooq.tables.references.WORKFLOW_TASK
import com.memo.org.OrgChanged
import com.memo.org.OrgDirectory
import com.memo.workflow.WorkflowRefusedException
import com.memo.workflow.engine.Block
import com.memo.workflow.engine.Reresolve
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Staff changes reach running items in the changing transaction (design E04 §4.6, ADR-0040): when a position's holder
 * changes, active steps resolved from that position resolve again; when a person is deactivated, their open tasks are
 * blocked for reassignment. Recorded as reassignments by whoever made the change; changes made by the system (seeding)
 * leave items alone.
 */
@Component
class StaffChangeListener(
    private val tenantDsl: TenantDsl,
    private val directory: OrgDirectory,
    private val runner: WorkflowRunner,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener
    fun onOrgChanged(event: OrgChanged) {
        val actor = ActorContext.current().actor as? Actor.User
        val position = event.takeIf { it.objectType == ASSIGNMENT }?.let { directory.positionOfAssignment(it.objectId) }
        if (actor == null || position == null) return
        val holder = directory.holders(listOf(position))[position]
        // A vacant step stays vacant until someone holds the position again.
        stepsResolvedFrom(position).forEach { (instance, step, holders) ->
            if (holders != setOfNotNull(holder)) quietly { runner.staffChange(instance, actor) { Reresolve(it, step) } }
        }
    }

    @EventListener
    fun onUserDeactivated(event: UserDeactivated) {
        val actor = ActorContext.current().actor as? Actor.User ?: return
        openTasksOf(event.userId).forEach { (instance, task) ->
            quietly { runner.staffChange(instance, actor) { Block(it, task) } }
        }
    }

    /** Active steps whose resolution read [position], with their instance and the people holding their open decisions. */
    private fun stepsResolvedFrom(position: UUID): List<Triple<UUID, UUID, Set<UUID>>> {
        val containing = JSONB.valueOf("""{"resolved":[{"positionId":"$position"}]}""")
        val steps =
            tenantDsl
                .current()
                .select(WORKFLOW_STEP.INSTANCE_ID, WORKFLOW_STEP.ID)
                .from(WORKFLOW_STEP)
                .where(WORKFLOW_STEP.STATE.eq(ACTIVE), DSL.condition("{0} @> {1}", WORKFLOW_STEP.RESOLUTION, DSL.value(containing)))
                .fetch { it.value1()!! to it.value2()!! }
        return steps.map { (instance, step) ->
            val holders =
                tenantDsl
                    .current()
                    .select(WORKFLOW_TASK.ON_BEHALF_OF, WORKFLOW_TASK.ASSIGNEE_ID)
                    .from(WORKFLOW_TASK)
                    .where(WORKFLOW_TASK.STEP_ID.eq(step), WORKFLOW_TASK.STATE.eq(OPEN), WORKFLOW_TASK.KIND.eq(DECIDE))
                    .fetch { it.value1() ?: it.value2() }
                    .filterNotNull()
                    .toSet()
            Triple(instance, step, holders)
        }
    }

    private fun openTasksOf(user: UUID): List<Pair<UUID, UUID>> =
        tenantDsl
            .current()
            .select(WORKFLOW_TASK.INSTANCE_ID, WORKFLOW_TASK.ID)
            .from(WORKFLOW_TASK)
            .where(WORKFLOW_TASK.ASSIGNEE_ID.eq(user), WORKFLOW_TASK.STATE.eq(OPEN), WORKFLOW_TASK.BLOCKED_REASON.isNull)
            .fetch { it.value1()!! to it.value2()!! }

    /** An item that cannot take the change any more (finished meanwhile) keeps going; the change itself still commits. */
    private fun quietly(change: () -> Unit) {
        try {
            change()
        } catch (refused: WorkflowRefusedException) {
            log.info("Staff change not applied to an item: {}", refused.reason)
        }
    }

    private companion object {
        const val ASSIGNMENT = "assignment"
        const val ACTIVE = "ACTIVE"
        const val OPEN = "OPEN"
        const val DECIDE = "DECIDE"
    }
}
