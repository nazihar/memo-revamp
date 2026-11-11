package com.memo.workflow.internal

import com.memo.common.authz.Permission
import com.memo.common.authz.RequiresPermission
import com.memo.common.authz.StepUpCheck
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.etagOf
import com.memo.common.web.ifMatchVersion
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Changing workflow definitions (design E03 §5–§6, ADR-0038): authors (`workflow.definitions.manage`) create, edit with
 * `If-Match` and submit drafts; reviewers (`.approve`) approve or send back what someone else submitted, and retire,
 * approving and retiring after a fresh step-up.
 */
@RestController
@RequestMapping("/api/v1/admin/workflow-definitions")
@Tag(name = "workflow")
class DefinitionChangesController(
    private val lifecycle: DefinitionLifecycle,
    private val responses: DefinitionResponses,
    private val stepUp: StepUpCheck,
    private val transactions: TenantTransactions,
) {
    @PostMapping
    @RequiresPermission(Permission.WORKFLOW_DEFINITIONS_MANAGE)
    @Operation(operationId = "createWorkflowDefinition", summary = "A new definition, its body saved as draft version 1")
    fun create(
        @RequestBody body: CreateDefinitionRequest,
    ): ResponseEntity<VersionResponse> {
        val key = body.key.trim()
        require(KEY.matches(key)) { "The key is lower case letters, digits and dashes, starting with a letter." }
        val name = body.name.trim()
        require(name.length in 1..MAX_NAME) { "The name is 1 to $MAX_NAME characters." }
        val description = body.description?.trim()?.ifEmpty { null }
        require((description?.length ?: 0) <= MAX_DESCRIPTION) { "The description is at most $MAX_DESCRIPTION characters." }
        val created = write { lifecycle.create(key, name, description, body.fragment, responses.tree(body.body)) }
        return ResponseEntity.status(HttpStatus.CREATED).eTag(etagOf(created.revision)).body(created)
    }

    @PostMapping("/{key}/versions")
    @RequiresPermission(Permission.WORKFLOW_DEFINITIONS_MANAGE)
    @Operation(operationId = "newWorkflowDefinitionDraft", summary = "A new draft, from a body or a copy of the published version")
    fun newDraft(
        @PathVariable key: String,
        @RequestBody(required = false) body: DraftRequest?,
    ): ResponseEntity<VersionResponse> {
        val draft = write { lifecycle.newDraft(key, body?.body?.let(responses::tree)) }
        return ResponseEntity.status(HttpStatus.CREATED).eTag(etagOf(draft.revision)).body(draft)
    }

    @PutMapping("/{key}/versions/{number}")
    @RequiresPermission(Permission.WORKFLOW_DEFINITIONS_MANAGE)
    @Operation(operationId = "replaceWorkflowDefinitionDraft", summary = "Replace a draft's body (import or save)")
    fun replace(
        @PathVariable key: String,
        @PathVariable number: String,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: DraftRequest,
    ): ResponseEntity<VersionResponse> {
        val revision = ifMatchVersion(ifMatch)
        val replacement = responses.tree(requireNotNull(body.body) { "A body is required." })
        val draft = write { lifecycle.replace(key, versionNumber(number), revision, replacement) }
        return ResponseEntity.ok().eTag(etagOf(draft.revision)).body(draft)
    }

    @PostMapping("/{key}/versions/{number}/submit")
    @RequiresPermission(Permission.WORKFLOW_DEFINITIONS_MANAGE)
    @Operation(operationId = "submitWorkflowDefinition", summary = "Send a valid draft for review")
    fun submit(
        @PathVariable key: String,
        @PathVariable number: String,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
    ): ResponseEntity<VersionResponse> {
        val revision = ifMatchVersion(ifMatch)
        val submitted = write { lifecycle.submit(key, versionNumber(number), revision) }
        return ResponseEntity.ok().eTag(etagOf(submitted.revision)).body(submitted)
    }

    @PostMapping("/{key}/versions/{number}/approve")
    @RequiresPermission(Permission.WORKFLOW_DEFINITIONS_APPROVE)
    @Operation(operationId = "approveWorkflowDefinition", summary = "Publish a version in review (not your own submission)")
    fun approve(
        @PathVariable key: String,
        @PathVariable number: String,
        @RequestBody(required = false) body: ReviewRequest?,
    ): VersionResponse {
        stepUp.require()
        return write { lifecycle.approve(key, versionNumber(number), body?.remark) }
    }

    @PostMapping("/{key}/versions/{number}/send-back")
    @RequiresPermission(Permission.WORKFLOW_DEFINITIONS_APPROVE)
    @Operation(operationId = "sendBackWorkflowDefinition", summary = "Return a version in review to draft, saying why")
    fun sendBack(
        @PathVariable key: String,
        @PathVariable number: String,
        @RequestBody body: ReviewRequest,
    ): VersionResponse = write { lifecycle.sendBack(key, versionNumber(number), body.remark) }

    @PostMapping("/{key}/versions/{number}/retire")
    @RequiresPermission(Permission.WORKFLOW_DEFINITIONS_APPROVE)
    @Operation(operationId = "retireWorkflowDefinition", summary = "Retire the published version of a definition nothing uses")
    fun retire(
        @PathVariable key: String,
        @PathVariable number: String,
    ): VersionResponse {
        stepUp.require()
        return write { lifecycle.retire(key, versionNumber(number)) }
    }

    /** Runs [change] in a tenant transaction and answers with the version as it is afterwards. */
    private fun write(change: () -> LoadedVersion): VersionResponse =
        transactions.inTenant(TenantContext.current()) { responses.version(change()) }

    private companion object {
        val KEY = Regex("^[a-z][a-z0-9-]{0,99}$")
        const val MAX_NAME = 200
        const val MAX_DESCRIPTION = 2000
    }
}
