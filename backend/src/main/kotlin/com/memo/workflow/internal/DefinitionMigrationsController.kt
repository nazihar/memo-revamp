package com.memo.workflow.internal

import com.memo.common.authz.Permission
import com.memo.common.authz.RequiresPermission
import com.memo.common.authz.StepUpCheck
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.system.Idempotency
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

data class MigrationPreviewRequest(
    /** The instances to check; omitted: every instance running on another version (oldest first, at most 500). */
    val instanceIds: List<UUID>? = null,
)

data class MigrationRequest(
    /** At most 100 instances. */
    val instanceIds: List<UUID>,
)

data class MigrationItem(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val instanceId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val subject: AdminTaskSubject,
    /** The version it is on; null when that is not a version of this definition. */
    val fromVersion: Int?,
    /** It can move to the target version now. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val migratable: Boolean,
    /** It is on the target version. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val migrated: Boolean,
    /** Why it cannot move: `WRONG_STATE`, `STEP_MISSING`, `STEP_CHANGED`, `NOT_OLDER` or `OTHER_DEFINITION`. */
    val reason: String?,
    /** The steps it is at, by name. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val openSteps: List<String>,
)

data class MigrationResponse(
    /** The published version instances move to. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val toVersion: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val items: List<MigrationItem>,
)

/**
 * Migrating running instances to the published version (design E03 §4.7, §5), for holders of
 * `workflow.definitions.approve`: a preview, then a migration of listed instances with a fresh step-up and an
 * `Idempotency-Key`. Both answer with the instances as they are, so a replayed key shows the outcome too.
 */
@RestController
@RequestMapping("/api/v1/admin/workflow-definitions/{key}/migrations")
@Tag(name = "workflow")
class DefinitionMigrationsController(
    private val migrations: InstanceMigrations,
    private val subjects: AdminSubjects,
    private val stepUp: StepUpCheck,
    private val idempotency: Idempotency,
    private val transactions: TenantTransactions,
    private val json: JsonMapper,
) {
    @PostMapping("/preview")
    @RequiresPermission(Permission.WORKFLOW_DEFINITIONS_APPROVE)
    @Operation(operationId = "previewWorkflowMigration", summary = "Which running instances can move to the published version")
    fun preview(
        @PathVariable key: String,
        @RequestBody(required = false) body: MigrationPreviewRequest?,
    ): MigrationResponse = transactions.readInTenant(TenantContext.current()) { response(migrations.preview(key, body?.instanceIds)) }

    @PostMapping
    @RequiresPermission(Permission.WORKFLOW_DEFINITIONS_APPROVE)
    @Operation(operationId = "migrateWorkflowInstances", summary = "Move listed instances to the published version")
    fun migrate(
        @PathVariable key: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestBody body: MigrationRequest,
    ): MigrationResponse {
        stepUp.require()
        return transactions.inTenant(TenantContext.current()) {
            val user = (ActorContext.current().actor as? Actor.User)?.userId
            val request = "$user\n$key\n${json.writeValueAsString(body)}"
            idempotency.once("workflow.definition.migrate", idempotencyKey, request) {
                migrations.migrate(key, body.instanceIds)
                key
            }
            response(migrations.preview(key, body.instanceIds))
        }
    }

    private fun response(plan: Pair<Int, List<MigrationCandidate>>): MigrationResponse {
        val (target, candidates) = plan
        val shown = subjects.of(candidates.map { it.subject })
        return MigrationResponse(
            target,
            candidates.map {
                MigrationItem(
                    instanceId = it.instanceId,
                    subject = shown.getValue(it.subject),
                    fromVersion = it.fromVersion,
                    migratable = it.reason == null && !it.onTarget,
                    migrated = it.onTarget,
                    reason = it.reason,
                    openSteps = it.openSteps,
                )
            },
        )
    }
}
