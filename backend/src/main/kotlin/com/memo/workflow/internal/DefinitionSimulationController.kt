package com.memo.workflow.internal

import com.memo.common.authz.Permission
import com.memo.common.authz.RequiresPermission
import com.memo.common.context.ActorContext
import com.memo.common.ratelimit.RateLimit
import com.memo.common.ratelimit.RateLimiter
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.FieldProblem
import com.memo.identity.UserSummaries
import com.memo.workflow.BlockedReason
import com.memo.workflow.RouteEntry
import com.memo.workflow.definition.Definition
import com.memo.workflow.definition.DefinitionCompiler
import com.memo.workflow.definition.DefinitionJson
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

data class SimulationRequest(
    /** An unsaved body to simulate; omitted: the stored version. */
    val body: Map<String, Any?>? = null,
    /** The kind of item, `memo` by default. */
    val subjectType: String = "memo",
    /** Who would start it. */
    val initiatorId: UUID,
    /** Sample values of the item's attributes, such as `classificationRank` and `memoTypeId`. */
    val attributes: Map<String, Any?> = emptyMap(),
    /** The initiator's route, for definitions with a route step. */
    val route: List<RouteEntry> = emptyList(),
)

data class SimulatedPersonResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
    /** Set when they would hold the task as this person's substitute. */
    val actingFor: Person?,
)

data class SimulatedStepResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val stepId: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val type: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val outcome: SimulatedOutcome,
    /** Who would get the task, or a notify step's recipients. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val people: List<SimulatedPersonResponse>,
    val blockedReason: BlockedReason?,
    /** For a branch: the condition that matched (0 first), or null when `otherwise` was taken. */
    val branch: Int?,
    /** For a branch: the matching condition's text. */
    val condition: String?,
    /** For a branch: the step it led to. */
    val next: String?,
    /** For a DoA step: the approval group (0 first). */
    val group: Int?,
    /** The step's deadline as written in the definition. */
    val sla: Map<String, Any?>?,
)

data class SimulationResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val steps: List<SimulatedStepResponse>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val ended: SimulationEnd,
)

/**
 * The definition simulation (design E06 §4): a version, or an unsaved body, run against sample values with the
 * organisation as it is now. Read-only, for holders of `workflow.definitions.manage` or `.approve`, rate-limited per user.
 */
@RestController
@RequestMapping("/api/v1/admin/workflow-definitions")
@Tag(name = "workflow")
class DefinitionSimulationController(
    private val reads: DefinitionReads,
    private val checks: DefinitionChecks,
    private val responses: DefinitionResponses,
    private val simulator: DefinitionSimulator,
    private val users: UserSummaries,
    private val limiter: RateLimiter,
    private val transactions: TenantTransactions,
    private val json: JsonMapper,
) {
    @PostMapping("/{key}/versions/{number}/simulate")
    @RequiresPermission(Permission.WORKFLOW_DEFINITIONS_MANAGE, orAnyOf = [Permission.WORKFLOW_DEFINITIONS_APPROVE])
    @Operation(operationId = "simulateWorkflowDefinition", summary = "Who a version, or an unsaved body, would reach for a sample item")
    fun simulate(
        @PathVariable key: String,
        @PathVariable number: String,
        @RequestBody request: SimulationRequest,
    ): SimulationResponse {
        limiter.consume(
            RateLimit.WORKFLOW_SIMULATION_PER_USER,
            ActorContext
                .current()
                .actor.id
                .orEmpty(),
        )
        return transactions.readInTenant(TenantContext.current()) {
            val loaded = reads.version(key, versionNumber(number))
            val definition = compiled(request.body?.let(responses::tree) ?: reads.body(loaded.version))
            val simulation = simulator.simulate(definition, request.subjectType, request.initiatorId, request.attributes, request.route)
            response(definition, simulation)
        }
    }

    /** The body validated, its fragments inlined, as the runtime would read it once published. */
    private fun compiled(body: JsonNode): Definition {
        val validation = checks.validate(body)
        if (!validation.valid) {
            throw DefinitionInvalidException(validation.problems.map { FieldProblem(it.pointer, it.code, it.message) })
        }
        return DefinitionCompiler.read(checks.compile(checkNotNull(validation.definition)), Definition.SCHEMA_VERSION)
    }

    private fun response(
        definition: Definition,
        simulation: Simulation,
    ): SimulationResponse {
        val printed = DefinitionJson.print(definition)["steps"].associateBy { it["id"].asString() }
        val ids = simulation.steps.flatMap { step -> step.people.flatMap { listOfNotNull(it.id, it.actingFor) } }.toSet()
        val names = users.of(ids).mapValues { it.value.displayName }

        fun person(id: UUID) = Person(id, names[id].orEmpty())
        val steps =
            simulation.steps.map { step ->
                val node = definition.step(step.nodeId)
                SimulatedStepResponse(
                    stepId = step.nodeId,
                    name = node.name ?: node.id,
                    type = node.type.json,
                    outcome = step.outcome,
                    people = step.people.map { SimulatedPersonResponse(it.id, names[it.id].orEmpty(), it.actingFor?.let(::person)) },
                    blockedReason = step.blockedReason,
                    branch = step.branch,
                    condition = step.branch?.let { node.branches[it].condition },
                    next = step.next,
                    group = step.group,
                    sla = printed[step.nodeId]?.get("sla")?.let { json.convertValue(it, MAP) },
                )
            }
        return SimulationResponse(steps, simulation.ended)
    }

    private companion object {
        val MAP = object : tools.jackson.core.type.TypeReference<Map<String, Any?>>() {}
    }
}
