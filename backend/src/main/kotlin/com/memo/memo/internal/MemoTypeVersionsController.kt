package com.memo.memo.internal

import com.memo.common.authz.Permission
import com.memo.common.authz.RequiresPermission
import com.memo.common.authz.StepUpCheck
import com.memo.common.review.ReviewState
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.FieldProblem
import com.memo.common.web.etagOf
import com.memo.common.web.ifMatchVersion
import com.memo.identity.UserSummaries
import com.memo.org.OrgDirectory
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
import java.util.UUID

/** A memo type's settings as an administrator sends them for a draft (ADR-0043). */
data class MemoTypeSettingsRequest(
    val name: String,
    /** 1 to 10 capital letters, digits or hyphens; used in memo numbers. */
    val code: String,
    /** Null: the tenant's lowest classification level. */
    val defaultClassificationId: UUID? = null,
    /** A published workflow definition. */
    val definitionKey: String,
    /** `INITIATOR_DEFINED`, `FIXED` or `BOTH`; it has to fit the definition. */
    val routePolicy: String,
    val allowedApprovers: AllowedApprovers = AllowedApprovers(),
    val doaCheck: Boolean = false,
    val stepUpOnApprove: Boolean = true,
    /** What the initiator fills in, in order. */
    val fields: List<MemoField> = emptyList(),
    /** Who may raise memos of the type; empty: everyone who may create memos. */
    val raiseScope: RaiseScope = RaiseScope(),
    /** How its memos are numbered; left out: the `general` series format `M-YYYY-NNNNN`, numbered on submit. */
    val numbering: Numbering = Numbering(),
)

/** One version of a memo type, with who made, submitted and reviewed it. */
data class MemoTypeVersionResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val number: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val state: ReviewState,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val revision: Long,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val code: String,
    val defaultClassificationId: UUID?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val definitionKey: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val routePolicy: RoutePolicy,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val allowedApprovers: AllowedApprovers,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val doaCheck: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val stepUpOnApprove: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val fields: List<MemoField>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val raiseScope: RaiseScope,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val numbering: Numbering,
    /** The names of the allowed people and the titles of the allowed positions, by id. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val allowedNames: Map<UUID, String>,
    /** Null: created by the system. */
    val createdBy: Named?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val createdAt: Instant,
    val submittedBy: Named?,
    val submittedAt: Instant?,
    val reviewedBy: Named?,
    val reviewedAt: Instant?,
    val reviewRemark: String?,
    val publishedAt: Instant?,
)

/**
 * Memo-type versions (ADR-0043, design E01 §3): drafted, changed and submitted by holders of `memo.types.manage`;
 * published (after a step-up) or sent back by a holder of `memo.types.approve` who did not submit them. Drafts are
 * changed with `If-Match`; one draft or version in review per type keeps a double click from making two.
 */
@RestController
@RequestMapping("/api/v1/admin/memo-types/{typeId}/versions")
@Tag(name = "memo")
class MemoTypeVersionsController(
    private val lifecycle: MemoTypeLifecycle,
    private val users: UserSummaries,
    private val directory: OrgDirectory,
    private val stepUp: StepUpCheck,
    private val transactions: TenantTransactions,
) {
    @GetMapping
    @RequiresPermission(Permission.MEMO_TYPES_MANAGE, orAnyOf = [Permission.MEMO_TYPES_APPROVE])
    @Operation(operationId = "listMemoTypeVersions", summary = "A memo type's versions, newest first")
    fun list(
        @PathVariable typeId: UUID,
    ): ResponseEntity<List<MemoTypeVersionResponse>> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(transactions.readInTenant(TenantContext.current()) { responses(lifecycle.list(typeId)) })

    @GetMapping("/{number}")
    @RequiresPermission(Permission.MEMO_TYPES_MANAGE, orAnyOf = [Permission.MEMO_TYPES_APPROVE])
    @Operation(operationId = "getMemoTypeVersion", summary = "One version of a memo type")
    fun get(
        @PathVariable typeId: UUID,
        @PathVariable number: String,
    ): ResponseEntity<MemoTypeVersionResponse> {
        val version = transactions.readInTenant(TenantContext.current()) { response(lifecycle.get(typeId, number(number))) }
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .eTag(etagOf(version.revision))
            .body(version)
    }

    @PostMapping
    @RequiresPermission(Permission.MEMO_TYPES_MANAGE)
    @Operation(operationId = "createMemoTypeDraft", summary = "Start a new draft, from the given settings or the published version")
    fun create(
        @PathVariable typeId: UUID,
        @RequestBody(required = false) body: MemoTypeSettingsRequest?,
    ): ResponseEntity<MemoTypeVersionResponse> {
        val draft = write { lifecycle.newDraft(typeId, body?.settings()) }
        return ResponseEntity.status(HttpStatus.CREATED).eTag(etagOf(draft.revision)).body(draft)
    }

    @PutMapping("/{number}")
    @RequiresPermission(Permission.MEMO_TYPES_MANAGE)
    @Operation(operationId = "replaceMemoTypeDraft", summary = "Replace a draft's settings")
    fun replace(
        @PathVariable typeId: UUID,
        @PathVariable number: String,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: MemoTypeSettingsRequest,
    ): ResponseEntity<MemoTypeVersionResponse> {
        val revision = ifMatchVersion(ifMatch)
        val draft = write { lifecycle.replace(typeId, number(number), revision, body.settings()) }
        return ResponseEntity.ok().eTag(etagOf(draft.revision)).body(draft)
    }

    @PostMapping("/{number}/submit")
    @RequiresPermission(Permission.MEMO_TYPES_MANAGE)
    @Operation(operationId = "submitMemoTypeVersion", summary = "Send a draft for review")
    fun submit(
        @PathVariable typeId: UUID,
        @PathVariable number: String,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
    ): ResponseEntity<MemoTypeVersionResponse> {
        val revision = ifMatchVersion(ifMatch)
        val submitted = write { lifecycle.submit(typeId, number(number), revision) }
        return ResponseEntity.ok().eTag(etagOf(submitted.revision)).body(submitted)
    }

    @PostMapping("/{number}/approve")
    @RequiresPermission(Permission.MEMO_TYPES_APPROVE)
    @Operation(operationId = "approveMemoTypeVersion", summary = "Publish a version in review (not your own submission)")
    fun approve(
        @PathVariable typeId: UUID,
        @PathVariable number: String,
        @RequestBody(required = false) body: RemarkRequest?,
    ): MemoTypeVersionResponse {
        stepUp.require()
        return write { lifecycle.approve(typeId, number(number), body?.remark) }
    }

    @PostMapping("/{number}/send-back")
    @RequiresPermission(Permission.MEMO_TYPES_APPROVE)
    @Operation(operationId = "sendBackMemoTypeVersion", summary = "Return a version in review to draft, saying why")
    fun sendBack(
        @PathVariable typeId: UUID,
        @PathVariable number: String,
        @RequestBody body: RemarkRequest,
    ): MemoTypeVersionResponse = write { lifecycle.sendBack(typeId, number(number), body.remark) }

    private fun write(change: () -> MemoTypeVersionRow): MemoTypeVersionResponse =
        transactions.inTenant(TenantContext.current()) { response(change()) }

    private fun response(version: MemoTypeVersionRow): MemoTypeVersionResponse = responses(listOf(version)).single()

    private fun responses(versions: List<MemoTypeVersionRow>): List<MemoTypeVersionResponse> {
        val people =
            users
                .of(
                    versions
                        .flatMap {
                            listOfNotNull(
                                it.createdBy,
                                it.submittedBy,
                                it.reviewedBy,
                            ) + it.settings.allowedApprovers.people
                        }.toSet(),
                ).mapValues { it.value.displayName }
        val positions =
            directory
                .positions(
                    versions.flatMap { it.settings.allowedApprovers.positions }.toSet(),
                ).mapValues { it.value.title }

        fun named(id: UUID?) = id?.let { Named(it, people[it].orEmpty()) }
        return versions.map { v ->
            val s = v.settings
            MemoTypeVersionResponse(
                number = v.number,
                state = v.state,
                revision = v.revision,
                name = s.name,
                code = s.code,
                defaultClassificationId = s.defaultClassificationId,
                definitionKey = s.definitionKey,
                routePolicy = s.routePolicy,
                allowedApprovers = s.allowedApprovers,
                doaCheck = s.doaCheck,
                stepUpOnApprove = s.stepUpOnApprove,
                fields = s.fields,
                raiseScope = s.raiseScope,
                numbering = s.numbering,
                allowedNames =
                    people.filterKeys { it in s.allowedApprovers.people } + positions.filterKeys { it in s.allowedApprovers.positions },
                createdBy = named(v.createdBy),
                createdAt = v.createdAt,
                submittedBy = named(v.submittedBy),
                submittedAt = v.submittedAt,
                reviewedBy = named(v.reviewedBy),
                reviewedAt = v.reviewedAt,
                reviewRemark = v.reviewRemark,
                publishedAt = v.publishedAt,
            )
        }
    }

    /** The settings as sent: trimmed name and definition key; an unknown route policy is a field problem. */
    private fun MemoTypeSettingsRequest.settings(): MemoTypeSettings {
        val policy =
            RoutePolicy.entries.firstOrNull { it.name == routePolicy }
                ?: throw MemoRejectedException(
                    listOf(FieldProblem("routePolicy", "unknown", "INITIATOR_DEFINED, FIXED or BOTH.")),
                )
        return MemoTypeSettings(
            name = name.trim(),
            code = code.trim(),
            defaultClassificationId = defaultClassificationId,
            definitionKey = definitionKey.trim(),
            routePolicy = policy,
            allowedApprovers = allowedApprovers,
            doaCheck = doaCheck,
            stepUpOnApprove = stepUpOnApprove,
            fields = fields.map { it.normalized() },
            raiseScope = raiseScope,
            numbering = numbering.copy(series = numbering.series.trim(), parts = numbering.parts.map { it.copy(text = it.text?.trim()) }),
        )
    }
}

private fun number(path: String): Int = path.toIntOrNull() ?: throw MemoNotFoundException("memo type version")
