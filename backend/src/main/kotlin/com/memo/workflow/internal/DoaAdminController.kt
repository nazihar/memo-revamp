package com.memo.workflow.internal

import com.memo.common.authz.Permission
import com.memo.common.authz.RequiresPermission
import com.memo.common.authz.StepUpCheck
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.etagOf
import com.memo.common.web.ifMatchVersion
import com.memo.identity.UserSummaries
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class DoaVersionResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val number: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val state: VersionState,
    val effectiveFrom: LocalDate?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val revision: Long,
    val submittedBy: Person?,
    val submittedAt: Instant?,
    val reviewedBy: Person?,
    val reviewedAt: Instant?,
    val reviewRemark: String?,
    val publishedAt: Instant?,
    /** The rules, in order; left out of the list of versions. */
    val rules: List<DoaRule>?,
)

data class DoaDraftRequest(
    val effectiveFrom: LocalDate? = null,
    /** The rules; when left out of a new draft, the version in force is copied. */
    val rules: List<DoaRule>? = null,
)

data class DoaSimulationRequest(
    /** A version number; the one in force today when left out. */
    val version: Int? = null,
    val subjectType: String,
    val initiatorId: UUID,
    /** The item's attribute values, such as `memoTypeId` and `classificationRank`. */
    val attributes: Map<String, Any?> = emptyMap(),
)

data class DoaSimulationGroup(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val needed: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val people: List<Person>,
)

data class DoaSimulationResponse(
    val version: Int?,
    /** The matching rule's position in the version (0 first). */
    val rule: Int?,
    val ruleName: String?,
    /** Why no rule applies: `NO_DOA` (no version in force) or `NO_RULE`. */
    val reason: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val groups: List<DoaSimulationGroup>,
)

/**
 * The delegation of authority (design E04 §3, §5): versions drafted and submitted by holders of `workflow.doa.manage`,
 * published (after a step-up) or sent back by a holder of `workflow.doa.approve` who did not submit them, and a
 * simulator answering "who approves this?".
 */
@RestController
@RequestMapping("/api/v1/admin/workflow/doa")
@Tag(name = "workflow")
class DoaAdminController(
    private val lifecycle: DoaLifecycle,
    private val matcher: DoaMatcher,
    private val users: UserSummaries,
    private val stepUp: StepUpCheck,
    private val transactions: TenantTransactions,
) {
    @GetMapping
    @RequiresPermission(Permission.WORKFLOW_DOA_MANAGE, orAnyOf = [Permission.WORKFLOW_DOA_APPROVE])
    @Operation(operationId = "listDoaVersions", summary = "The DoA versions, newest first")
    fun list(): ResponseEntity<List<DoaVersionResponse>> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(transactions.readInTenant(TenantContext.current()) { lifecycle.list().map { response(it, null) } })

    @GetMapping("/versions/{number}")
    @RequiresPermission(Permission.WORKFLOW_DOA_MANAGE, orAnyOf = [Permission.WORKFLOW_DOA_APPROVE])
    @Operation(operationId = "getDoaVersion", summary = "A DoA version with its rules")
    fun get(
        @PathVariable number: String,
    ): ResponseEntity<DoaVersionResponse> {
        val version =
            transactions.readInTenant(TenantContext.current()) { lifecycle.get(number(number)).let { response(it.version, it.rules) } }
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .eTag(etagOf(version.revision))
            .body(version)
    }

    @PostMapping("/versions")
    @RequiresPermission(Permission.WORKFLOW_DOA_MANAGE)
    @Operation(operationId = "createDoaDraft", summary = "Start a new draft, from the given rules or the version in force")
    fun create(
        @RequestBody(required = false) body: DoaDraftRequest?,
    ): ResponseEntity<DoaVersionResponse> {
        val draft = write { lifecycle.create(body?.effectiveFrom, body?.rules) }
        return ResponseEntity.status(HttpStatus.CREATED).eTag(etagOf(draft.revision)).body(draft)
    }

    @PutMapping("/versions/{number}")
    @RequiresPermission(Permission.WORKFLOW_DOA_MANAGE)
    @Operation(operationId = "replaceDoaDraft", summary = "Replace a draft's date and rules")
    fun replace(
        @PathVariable number: String,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: DoaDraftRequest,
    ): ResponseEntity<DoaVersionResponse> {
        val revision = ifMatchVersion(ifMatch)
        val rules = requireNotNull(body.rules) { "The rules are required." }
        val draft = write { lifecycle.replace(number(number), revision, body.effectiveFrom, rules) }
        return ResponseEntity.ok().eTag(etagOf(draft.revision)).body(draft)
    }

    @PostMapping("/versions/{number}/submit")
    @RequiresPermission(Permission.WORKFLOW_DOA_MANAGE)
    @Operation(operationId = "submitDoaVersion", summary = "Send a draft for review")
    fun submit(
        @PathVariable number: String,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
    ): ResponseEntity<DoaVersionResponse> {
        val revision = ifMatchVersion(ifMatch)
        val submitted = write { lifecycle.submit(number(number), revision) }
        return ResponseEntity.ok().eTag(etagOf(submitted.revision)).body(submitted)
    }

    @PostMapping("/versions/{number}/approve")
    @RequiresPermission(Permission.WORKFLOW_DOA_APPROVE)
    @Operation(operationId = "approveDoaVersion", summary = "Publish a version in review (not your own submission)")
    fun approve(
        @PathVariable number: String,
        @RequestBody(required = false) body: ReviewRequest?,
    ): DoaVersionResponse {
        stepUp.require()
        return write { lifecycle.approve(number(number), body?.remark) }
    }

    @PostMapping("/versions/{number}/send-back")
    @RequiresPermission(Permission.WORKFLOW_DOA_APPROVE)
    @Operation(operationId = "sendBackDoaVersion", summary = "Return a version in review to draft, saying why")
    fun sendBack(
        @PathVariable number: String,
        @RequestBody body: ReviewRequest,
    ): DoaVersionResponse = write { lifecycle.sendBack(number(number), body.remark) }

    @PostMapping("/simulate")
    @RequiresPermission(Permission.WORKFLOW_DOA_MANAGE, orAnyOf = [Permission.WORKFLOW_DOA_APPROVE])
    @Operation(operationId = "simulateDoa", summary = "Who would approve an item, under a version or the one in force")
    fun simulate(
        @RequestBody body: DoaSimulationRequest,
    ): DoaSimulationResponse =
        transactions.readInTenant(TenantContext.current()) {
            val result = matcher.simulate(body.version, body.subjectType, body.attributes, body.initiatorId)
            val names = users.of(result.groups.flatMap { it.people }.toSet())
            DoaSimulationResponse(
                result.version,
                result.rule,
                result.ruleName,
                result.reason,
                result.groups.map { group ->
                    DoaSimulationGroup(group.needed, group.people.map { Person(it, names[it]?.displayName.orEmpty()) })
                },
            )
        }

    private fun write(change: () -> LoadedDoa): DoaVersionResponse =
        transactions.inTenant(TenantContext.current()) { change().let { response(it.version, it.rules) } }

    private fun response(
        version: DoaVersionRow,
        rules: List<DoaRule>?,
    ): DoaVersionResponse {
        val names = users.of(listOfNotNull(version.submittedBy, version.reviewedBy))

        fun person(id: UUID?) = id?.let { Person(it, names[it]?.displayName.orEmpty()) }
        return DoaVersionResponse(
            version.number,
            version.state,
            version.effectiveFrom,
            version.revision,
            person(version.submittedBy),
            version.submittedAt,
            person(version.reviewedBy),
            version.reviewedAt,
            version.reviewRemark,
            version.publishedAt,
            rules,
        )
    }
}

private fun number(path: String): Int = path.toIntOrNull() ?: throw DoaNotFoundException()
