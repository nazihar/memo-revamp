package com.memo.memo.internal

import com.memo.audit.AuditLog
import com.memo.common.authz.ForbiddenException
import com.memo.common.authz.Permission
import com.memo.common.authz.Policy
import com.memo.common.authz.StepUpCheck
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.FieldProblem
import com.memo.common.web.RefusedException
import com.memo.workflow.WorkflowRefusedException
import com.memo.workflow.WorkflowTeamQueries
import com.memo.workflow.Workflows
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.stereotype.Service
import java.util.UUID

/** What a bulk action may do (design E05 §4): following and taking or handing on tasks; never a decision. */
enum class BulkAction(
    /** Memo ids (following) or task ids (tasks). */
    val onTasks: Boolean,
) {
    WATCH(false),
    UNWATCH(false),
    CLAIM(true),
    RELEASE(true),
    REASSIGN(true),
}

/** How one item of a bulk action went: done, not allowed, changed meanwhile, or not there (or not visible). */
enum class BulkOutcome { OK, FORBIDDEN, CHANGED, NOT_FOUND }

data class BulkRequest(
    /** WATCH, UNWATCH, CLAIM, RELEASE or REASSIGN. Anything else (APPROVE, RETURN…) is refused: no bulk decisions. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val action: String,
    /** Memo ids for WATCH and UNWATCH, task ids for the others; 1 to 100. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val ids: List<UUID>,
    /** REASSIGN: the new holder. */
    val to: UUID? = null,
    val reason: String? = null,
)

data class BulkItemResult(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val outcome: BulkOutcome,
)

data class BulkResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val results: List<BulkItemResult>,
)

/**
 * Bulk actions on the inbox (design E05 §4, flow J): each item is checked and done as if alone, in its own transaction,
 * so one item's failure changes nothing for the others; the answer gives every item's outcome. Reassigning needs
 * `workflow.tasks.reassign` and step-up, as one reassignment does. One audit entry per bulk action.
 */
@Service
class InboxBulk(
    private val watches: MemoWatches,
    private val tasks: MemoTasks,
    private val workflows: Workflows,
    private val policy: Policy,
    private val stepUp: StepUpCheck,
    private val transactions: TenantTransactions,
    private val audit: AuditLog,
    private val scopes: TeamScopes,
    private val team: WorkflowTeamQueries,
) {
    fun perform(request: BulkRequest): BulkResponse {
        val action = check(request)
        if (action == BulkAction.REASSIGN) stepUp.require()
        val tenant = TenantContext.current()
        // Heads and holders of inbox.team.read may reassign the tasks of the people seated in their scope (design E05 §5).
        val members by lazy { transactions.readInTenant(tenant) { scopes.seated(scopes.of(viewer(), Permission.INBOX_TEAM_READ)).keys } }
        val results =
            request.ids.distinct().map { id ->
                BulkItemResult(id, transactions.inTenant(tenant) { one(action, id, request) { members } })
            }
        transactions.inTenant(tenant) {
            audit.record(
                "inbox.bulk.performed",
                Memos.OBJECT_TYPE,
                null,
                after =
                    mapOf(
                        "action" to action.name,
                        "count" to results.size,
                        "results" to results.groupingBy { it.outcome.name }.eachCount(),
                    ),
            )
        }
        return BulkResponse(results)
    }

    private fun one(
        action: BulkAction,
        id: UUID,
        request: BulkRequest,
        members: () -> Set<UUID>,
    ): BulkOutcome =
        try {
            act(action, id, request, members)
            BulkOutcome.OK
        } catch (_: MemoNotFoundException) {
            BulkOutcome.NOT_FOUND
        } catch (_: ForbiddenException) {
            BulkOutcome.FORBIDDEN
        } catch (e: WorkflowRefusedException) {
            if (e.reason in GONE) BulkOutcome.NOT_FOUND else BulkOutcome.CHANGED
        } catch (_: RefusedException) {
            BulkOutcome.CHANGED
        } catch (_: ConcurrentModificationException) {
            BulkOutcome.CHANGED
        }

    private fun act(
        action: BulkAction,
        id: UUID,
        request: BulkRequest,
        members: () -> Set<UUID>,
    ) {
        when (action) {
            BulkAction.WATCH -> {
                watches.watch(id)
            }

            BulkAction.UNWATCH -> {
                watches.unwatch(id)
            }

            BulkAction.CLAIM -> {
                tasks.handOn(id) { it.claim(id) }
            }

            BulkAction.RELEASE -> {
                tasks.handOn(id) { it.release(id) }
            }

            BulkAction.REASSIGN -> {
                if (!mayReassign(id, members)) throw ForbiddenException(Permission.WORKFLOW_TASKS_REASSIGN)
                workflows.reassign(id, requireNotNull(request.to), request.reason)
            }
        }
    }

    /**
     * As the administrators' reassignment (the permission, not read access to the memo, decides), or a head reassigning
     * the task of someone in their team (design E05 §5).
     */
    private fun mayReassign(
        id: UUID,
        members: () -> Set<UUID>,
    ): Boolean = policy.allows(Permission.WORKFLOW_TASKS_REASSIGN) || team.holderOf(id)?.let { it in members() } == true

    /** The action, or 422: an unknown or decision action, no or too many items, or a reassignment without a holder. */
    private fun check(request: BulkRequest): BulkAction {
        val action = BulkAction.entries.firstOrNull { it.name == request.action }
        val problems =
            listOfNotNull(
                FieldProblem("action", "NOT_ALLOWED", "Only watch, unwatch, claim, release and reassign can be done in bulk.")
                    .takeIf { action == null },
                FieldProblem(
                    "ids",
                    "COUNT",
                    "Choose 1 to $MAX_ITEMS items.",
                ).takeIf { request.ids.isEmpty() || request.ids.size > MAX_ITEMS },
                FieldProblem("to", "REQUIRED", "Choose who to give the tasks to.").takeIf {
                    action == BulkAction.REASSIGN &&
                        request.to == null
                },
            )
        if (problems.isNotEmpty() || action == null) throw MemoRejectedException(problems)
        return action
    }

    private fun viewer(): UUID =
        checkNotNull((ActorContext.current().actor as? Actor.User)?.userId) { "bulk actions need a signed-in user" }

    companion object {
        const val MAX_ITEMS = 100

        /** The workflow's reasons for a task or instance that is not there. */
        private val GONE = setOf("TASK_NOT_FOUND", "INSTANCE_NOT_FOUND")
    }
}
