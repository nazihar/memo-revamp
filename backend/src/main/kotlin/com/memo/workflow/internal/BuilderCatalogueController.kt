package com.memo.workflow.internal

import com.memo.common.authz.Permission
import com.memo.common.authz.RequiresPermission
import com.memo.common.authz.RoleHolders
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.workflow.WorkflowSubject
import com.memo.workflow.definition.StepType
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class CatalogueAttribute(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
    /** `string`, `int`, `decimal`, `bool`, `date`, `timestamp` or a list of one of them (ADR-0039). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val type: String,
)

data class CatalogueSubjectType(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val type: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val attributes: List<CatalogueAttribute>,
)

data class CatalogueRole(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
)

data class CatalogueEntry(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val key: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
)

data class BuilderCatalogue(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val subjectTypes: List<CatalogueSubjectType>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val roles: List<CatalogueRole>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val queues: List<CatalogueEntry>,
    /** Published sub-workflows a `subworkflow` step can run. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val fragments: List<CatalogueEntry>,
    /** The step types the builder offers; `sign` is left out until Phase 5. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val stepTypes: List<String>,
)

/**
 * What the workflow builder's panels choose from (design E06 §5): the attributes conditions may read, roles, queues and
 * published fragments. For authors and reviewers of definitions, so they need neither `authz.roles.manage` nor
 * `workflow.queues.manage` to name a role or a queue. People, units and positions come from the directory.
 */
@RestController
@RequestMapping("/api/v1/admin/workflow-builder")
@Tag(name = "workflow")
class BuilderCatalogueController(
    private val subjects: List<WorkflowSubject>,
    private val roles: RoleHolders,
    private val queues: Queues,
    private val versions: DefinitionVersions,
    private val transactions: TenantTransactions,
) {
    @GetMapping("/catalogue")
    @RequiresPermission(Permission.WORKFLOW_DEFINITIONS_MANAGE, orAnyOf = [Permission.WORKFLOW_DEFINITIONS_APPROVE])
    @Operation(operationId = "getBuilderCatalogue", summary = "Attributes, roles, queues and sub-workflows the builder offers")
    fun catalogue(): ResponseEntity<BuilderCatalogue> {
        val catalogue =
            transactions.readInTenant(TenantContext.current()) {
                val definitions = versions.definitions().filter { it.fragment && !it.builtIn }
                val published =
                    versions
                        .versions(definitions.map { it.id })
                        .filter { it.state == VersionState.PUBLISHED }
                        .map { it.definitionId }
                        .toSet()
                BuilderCatalogue(
                    subjectTypes =
                        subjects
                            .filter { it.attributes.isNotEmpty() }
                            .sortedBy { it.type }
                            .map { s -> CatalogueSubjectType(s.type, s.attributes.map { CatalogueAttribute(it.key, it.value.json) }) },
                    roles = roles.names().map { (id, name) -> CatalogueRole(id, name) },
                    queues = queues.all().map { CatalogueEntry(it.key, it.name) },
                    fragments = definitions.filter { it.id in published }.map { CatalogueEntry(it.key, it.name) },
                    stepTypes = StepType.entries.filter { it != StepType.SIGN }.map { it.json },
                )
            }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(catalogue)
    }
}
