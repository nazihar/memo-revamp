package com.memo.workflow.internal

import com.memo.common.authz.Permission
import com.memo.common.authz.RequiresPermission
import com.memo.common.authz.StepUpCheck
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.identity.UserSummaries
import com.memo.system.Idempotency
import com.memo.workflow.BlockedReason
import com.memo.workflow.TaskItem
import com.memo.workflow.WorkflowQueries
import com.memo.workflow.WorkflowRefusedException
import com.memo.workflow.WorkflowView
import com.memo.workflow.Workflows
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/** The subject of an open task as administrators see it: the title only if they are cleared for it (design E01 §5). */
data class AdminTaskSubject(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val type: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    val reference: String?,
    /** Null when [restricted]. */
    val title: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val restricted: Boolean,
)

data class Person(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
)

data class AdminTaskResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val taskId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val subject: AdminTaskSubject,
    /** Null while nobody is found for the step (blocked `UNRESOLVED`, design E04 §4.1). */
    val assignee: Person?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val round: Int,
    val blocked: BlockedReason?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val createdAt: Instant,
)

data class AdminTaskPage(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val items: List<AdminTaskResponse>,
    val nextCursor: String?,
)

data class ReassignRequest(
    val newAssigneeId: UUID,
    val reason: String? = null,
)

/**
 * Open tasks for reassignment (design E01 §5, T6), for holders of `workflow.tasks.reassign`. Reassigning needs a fresh
 * step-up, a reason, and an `Idempotency-Key`; the engine checks that the new assignee may hold the task.
 */
@RestController
@RequestMapping("/api/v1/admin/workflow/tasks")
@Tag(name = "workflow")
class WorkflowAdminController(
    private val queries: WorkflowQueries,
    private val workflows: Workflows,
    private val subjects: AdminSubjects,
    private val users: UserSummaries,
    private val stepUp: StepUpCheck,
    private val idempotency: Idempotency,
    private val transactions: TenantTransactions,
    private val json: JsonMapper,
) {
    @GetMapping
    @RequiresPermission(Permission.WORKFLOW_TASKS_REASSIGN)
    @Operation(operationId = "listOpenTasks", summary = "Open workflow tasks, or only the blocked ones")
    fun list(
        @RequestParam(defaultValue = "open") filter: String,
        @RequestParam(required = false) assignee: UUID?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "25") limit: Int,
    ): ResponseEntity<AdminTaskPage> {
        require(filter == "open" || filter == "blocked") { "filter is open or blocked" }
        val page =
            transactions.readInTenant(TenantContext.current()) {
                val tasks = queries.openTasks(filter == "blocked", assignee, cursor, limit)
                AdminTaskPage(responses(tasks.items), tasks.nextCursor)
            }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(page)
    }

    @PostMapping("/{taskId}/reassign")
    @RequiresPermission(Permission.WORKFLOW_TASKS_REASSIGN)
    @Operation(operationId = "reassignTask", summary = "Give an open task to someone else")
    fun reassign(
        @PathVariable taskId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: ReassignRequest,
    ): WorkflowView {
        stepUp.require()
        return transactions.inTenant(TenantContext.current()) {
            val user = (ActorContext.current().actor as? Actor.User)?.userId
            val request = "$user\n$taskId\n${json.writeValueAsString(body)}"
            idempotency.once("workflow.task.reassign", key, request) {
                workflows.reassign(taskId, body.newAssigneeId, body.reason).instanceId.toString()
            }
            // The instance as it is now (a replayed key answers with the current state, like the memo endpoints).
            val subject = checkNotNull(queries.subjectOfTask(taskId))
            checkNotNull(queries.viewOf(subject.type, subject.id))
        }
    }

    private fun responses(tasks: List<TaskItem>): List<AdminTaskResponse> {
        val shown = subjects.of(tasks.map { it.subject })
        val names = users.of(tasks.mapNotNull { it.assigneeId }.toSet())
        return tasks.map { task ->
            AdminTaskResponse(
                taskId = task.taskId,
                subject = shown.getValue(task.subject),
                assignee = task.assigneeId?.let { Person(it, names[it]?.displayName.orEmpty()) },
                round = task.round,
                blocked = task.blocked,
                createdAt = task.createdAt,
            )
        }
    }
}

data class CancelRequest(
    /** Why the workflow stops; recorded in the history and shown to the people involved. */
    val reason: String?,
)

/** Stopping a running workflow (design E03 §4.4, §5): administrators with `workflow.instances.cancel`, after a step-up. */
@RestController
@RequestMapping("/api/v1/admin/workflow/instances")
@Tag(name = "workflow")
class WorkflowInstancesAdminController(
    private val queries: WorkflowQueries,
    private val workflows: Workflows,
    private val stepUp: StepUpCheck,
    private val idempotency: Idempotency,
    private val transactions: TenantTransactions,
    private val json: JsonMapper,
) {
    @PostMapping("/{instanceId}/cancel")
    @RequiresPermission(Permission.WORKFLOW_INSTANCES_CANCEL)
    @Operation(operationId = "cancelWorkflow", summary = "Cancel a running workflow with a reason")
    fun cancel(
        @PathVariable instanceId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: CancelRequest,
    ): WorkflowView {
        stepUp.require()
        return transactions.inTenant(TenantContext.current()) {
            val user = (ActorContext.current().actor as? Actor.User)?.userId
            idempotency.once("workflow.instance.cancel", key, "$user\n$instanceId\n${json.writeValueAsString(body)}") {
                workflows.cancel(instanceId, body.reason).instanceId.toString()
            }
            val subject = queries.subjectOfInstance(instanceId) ?: throw WorkflowRefusedException("INSTANCE_NOT_FOUND")
            checkNotNull(queries.viewOf(subject.type, subject.id))
        }
    }
}
