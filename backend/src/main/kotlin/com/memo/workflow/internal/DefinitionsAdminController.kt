package com.memo.workflow.internal

import com.memo.common.authz.Permission
import com.memo.common.authz.RequiresPermission
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.FieldProblem
import com.memo.common.web.etagOf
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.ContentDisposition
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

data class DefinitionSummary(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val key: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
    val description: String?,
    /** Meant to be used as a sub-workflow inside other definitions. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val fragment: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val builtIn: Boolean,
    /** The version new items start on; null when none is published. */
    val publishedVersion: Int?,
    /** The draft or version in review, if there is one. */
    val openVersion: VersionSummary?,
)

data class VersionSummary(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val number: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val state: VersionState,
    val submittedBy: Person?,
    val submittedAt: Instant?,
    val reviewedBy: Person?,
    val reviewedAt: Instant?,
    /** Why the reviewer sent it back, or their note when approving. */
    val reviewRemark: String?,
    val publishedAt: Instant?,
)

data class DefinitionResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val definition: DefinitionSummary,
    /** Newest first. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val versions: List<VersionSummary>,
)

data class VersionResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val key: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val version: VersionSummary,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Send as If-Match when changing or submitting a draft")
    val revision: Long,
    /** The definition body (schema version 2, ADR-0038). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val body: Map<String, Any?>,
    /** What submitting it would refuse (drafts and versions in review), with JSON pointers into [body]. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val problems: List<FieldProblem>,
)

data class ValidationResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val valid: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val problems: List<FieldProblem>,
)

data class CreateDefinitionRequest(
    /** Lower case letters, digits and dashes, starting with a letter; never changes. */
    val key: String,
    val name: String,
    val description: String? = null,
    val fragment: Boolean = false,
    val body: Map<String, Any?>,
)

data class DraftRequest(
    /** The new body; omitted: a copy of the published version. */
    val body: Map<String, Any?>? = null,
)

data class ValidateRequest(
    /** An unsaved body to check; omitted: the stored version. */
    val body: Map<String, Any?>? = null,
)

data class ReviewRequest(
    val remark: String? = null,
)

/**
 * Reading workflow definitions (design E03 §5, ADR-0038): for holders of `workflow.definitions.manage` or `.approve`;
 * validating a body is the authors' (`.manage`). [DefinitionChangesController] changes them.
 */
@RestController
@RequestMapping("/api/v1/admin/workflow-definitions")
@Tag(name = "workflow")
class DefinitionsAdminController(
    private val reads: DefinitionReads,
    private val checks: DefinitionChecks,
    private val responses: DefinitionResponses,
    private val transactions: TenantTransactions,
    private val json: JsonMapper,
) {
    @GetMapping
    @RequiresPermission(Permission.WORKFLOW_DEFINITIONS_MANAGE, orAnyOf = [Permission.WORKFLOW_DEFINITIONS_APPROVE])
    @Operation(operationId = "listWorkflowDefinitions", summary = "Workflow definitions with their published and open versions")
    fun list(): ResponseEntity<List<DefinitionSummary>> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(read { responses.summaries(reads.definitions()) })

    @GetMapping("/{key}")
    @RequiresPermission(Permission.WORKFLOW_DEFINITIONS_MANAGE, orAnyOf = [Permission.WORKFLOW_DEFINITIONS_APPROVE])
    @Operation(operationId = "getWorkflowDefinition", summary = "A workflow definition and its versions")
    fun get(
        @PathVariable key: String,
    ): ResponseEntity<DefinitionResponse> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(read { responses.definition(key) })

    @GetMapping("/{key}/versions/{number}")
    @RequiresPermission(Permission.WORKFLOW_DEFINITIONS_MANAGE, orAnyOf = [Permission.WORKFLOW_DEFINITIONS_APPROVE])
    @Operation(operationId = "getWorkflowDefinitionVersion", summary = "A version's body and what validation finds in it")
    fun version(
        @PathVariable key: String,
        @PathVariable number: String,
    ): ResponseEntity<VersionResponse> {
        val version = read { responses.version(reads.version(key, versionNumber(number))) }
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .eTag(etagOf(version.revision))
            .body(version)
    }

    @PostMapping("/{key}/versions/{number}/validate")
    @RequiresPermission(Permission.WORKFLOW_DEFINITIONS_MANAGE)
    @Operation(operationId = "validateWorkflowDefinition", summary = "Check a version, or an unsaved body, without changing anything")
    fun validate(
        @PathVariable key: String,
        @PathVariable number: String,
        @RequestBody(required = false) body: ValidateRequest?,
    ): ValidationResponse =
        read {
            val loaded = reads.version(key, versionNumber(number))
            val validation = checks.validate(body?.body?.let(responses::tree) ?: reads.body(loaded.version))
            ValidationResponse(validation.valid, validation.problems.map { FieldProblem(it.pointer, it.code, it.message) })
        }

    @GetMapping("/{key}/versions/{number}/export", produces = [MediaType.APPLICATION_JSON_VALUE])
    @RequiresPermission(Permission.WORKFLOW_DEFINITIONS_MANAGE, orAnyOf = [Permission.WORKFLOW_DEFINITIONS_APPROVE])
    @Operation(operationId = "exportWorkflowDefinition", summary = "A version's body as a JSON file")
    fun export(
        @PathVariable key: String,
        @PathVariable number: String,
    ): ResponseEntity<String> {
        val version = versionNumber(number)
        val body = read { json.writeValueAsString(reads.body(reads.version(key, version).version)) }
        val file = ContentDisposition.attachment().filename("$key-v$version.json").build()
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .header("Content-Disposition", file.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
    }

    private fun <T> read(block: () -> T): T = transactions.readInTenant(TenantContext.current(), block)
}
