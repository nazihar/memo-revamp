package com.memo.workflow.internal

import com.memo.common.authz.Permission
import com.memo.common.authz.RequiresPermission
import com.memo.common.authz.StepUpCheck
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.identity.UserStatus
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
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

data class SubstitutionResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val principal: Person,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val substitute: Person,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val validFrom: LocalDate,
    /** Exclusive; open-ended when null. */
    val validTo: LocalDate?,
    val reason: String?,
)

data class SubstitutionRequest(
    val principalId: UUID,
    val substituteId: UUID,
    val validFrom: LocalDate,
    val validTo: LocalDate? = null,
    val reason: String? = null,
)

data class AppliedResponse(
    /** How many open tasks moved to the substitute. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val moved: Int,
)

/**
 * Substitutions (design E04 §4.5, §5), for holders of `workflow.tasks.reassign` until Phase 1b adds self-service: who
 * acts for whom, from when to when, and moving a principal's open tasks to their substitute (after a step-up).
 */
@RestController
@RequestMapping("/api/v1/admin/workflow/substitutions")
@Tag(name = "workflow")
class SubstitutionsAdminController(
    private val substitutions: Substitutions,
    private val users: UserSummaries,
    private val stepUp: StepUpCheck,
    private val transactions: TenantTransactions,
) {
    @GetMapping
    @RequiresPermission(Permission.WORKFLOW_TASKS_REASSIGN)
    @Operation(operationId = "listSubstitutions", summary = "Substitutions, newest first, of everyone or one person")
    fun list(
        @RequestParam(required = false) principal: UUID?,
    ): ResponseEntity<List<SubstitutionResponse>> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(transactions.readInTenant(TenantContext.current()) { responses(substitutions.list(principal)) })

    @PostMapping
    @RequiresPermission(Permission.WORKFLOW_TASKS_REASSIGN)
    @Operation(operationId = "createSubstitution", summary = "Name someone to act for a person between two dates")
    fun create(
        @RequestBody body: SubstitutionRequest,
    ): ResponseEntity<SubstitutionResponse> {
        require((body.reason?.length ?: 0) <= MAX_REASON) { "The reason is at most $MAX_REASON characters." }
        val created =
            transactions.inTenant(TenantContext.current()) {
                val people = users.of(listOf(body.principalId, body.substituteId))
                require(people[body.substituteId]?.status == UserStatus.ACTIVE) { "The substitute is not an active user." }
                require(body.principalId in people) { "The person is not a user." }
                val row = substitutions.create(body.principalId, body.substituteId, body.validFrom, body.validTo, body.reason?.trim())
                responses(listOf(row)).single()
            }
        return ResponseEntity.status(HttpStatus.CREATED).body(created)
    }

    @PostMapping("/{id}/end")
    @RequiresPermission(Permission.WORKFLOW_TASKS_REASSIGN)
    @Operation(operationId = "endSubstitution", summary = "End a substitution today")
    fun end(
        @PathVariable id: UUID,
    ): SubstitutionResponse = transactions.inTenant(TenantContext.current()) { responses(listOf(substitutions.end(id))).single() }

    @PostMapping("/{id}/apply")
    @RequiresPermission(Permission.WORKFLOW_TASKS_REASSIGN)
    @Operation(operationId = "applySubstitution", summary = "Move the person's open tasks to their substitute")
    fun apply(
        @PathVariable id: UUID,
    ): AppliedResponse {
        stepUp.require()
        return AppliedResponse(transactions.inTenant(TenantContext.current()) { substitutions.apply(id) })
    }

    private fun responses(rows: List<SubstitutionRow>): List<SubstitutionResponse> {
        val names = users.of(rows.flatMap { listOf(it.principalId, it.substituteId) }.toSet())

        fun person(id: UUID) = Person(id, names[id]?.displayName.orEmpty())
        return rows.map {
            SubstitutionResponse(
                it.id,
                person(it.principalId),
                person(it.substituteId),
                it.validFrom,
                it.validTo,
                it.reason,
            )
        }
    }

    private companion object {
        const val MAX_REASON = 2000
    }
}
