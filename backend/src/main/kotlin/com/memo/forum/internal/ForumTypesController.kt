package com.memo.forum.internal

import com.memo.common.authz.Permission
import com.memo.common.authz.RequiresPermission
import com.memo.common.authz.StepUpCheck
import com.memo.common.review.ReviewState
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.FieldProblem
import com.memo.common.web.etagOf
import com.memo.common.web.ifMatchVersion
import com.memo.forum.ActionsPolicy
import com.memo.forum.AgendaPolicy
import com.memo.forum.ForumRetention
import com.memo.forum.ForumRole
import com.memo.forum.MeetingPolicy
import com.memo.forum.MemberAttribute
import com.memo.forum.MinutesPolicy
import com.memo.forum.Outcome
import com.memo.forum.Quorum
import com.memo.forum.QuorumPresence
import com.memo.forum.QuorumResult
import com.memo.forum.QuorumRule
import com.memo.forum.QuorumSeat
import com.memo.forum.SubmissionPolicy
import com.memo.forum.TermPolicy
import com.memo.identity.UserSummaries
import com.memo.workflow.DefinitionChoice
import com.memo.workflow.WorkflowDefinitions
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/** A person as a response names them. */
data class PersonRef(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
)

/** A forum type's policy as an administrator sends it for a draft (ADR-0056, ADR-0057). */
data class ForumTypeSettingsRequest(
    val name: String,
    val description: String? = null,
    val defaultClassificationId: UUID? = null,
    val retention: ForumRetention? = null,
    val memberAttributes: List<MemberAttribute> = emptyList(),
    val quorum: QuorumRule = QuorumRule(),
    val outcomes: List<Outcome> = emptyList(),
    val termPolicy: TermPolicy = TermPolicy(),
    val submissionPolicy: SubmissionPolicy = SubmissionPolicy(),
    val agenda: AgendaPolicy = AgendaPolicy(),
    /** Invitations, replies and the quorum override (Phase 4 E05); left out: the defaults. */
    val meetings: MeetingPolicy = MeetingPolicy(),
    /** Minutes approval, when memos move on, resolution numbers and the template (Phase 4 E06); left out: the defaults. */
    val minutes: MinutesPolicy = MinutesPolicy(),
    /** Action items (Phase 4 E07, design E07 §1.13); left out: the defaults. */
    val actions: ActionsPolicy = ActionsPolicy(),
)

data class CreateForumTypeRequest(
    /** 1 to 10 capital letters, digits or hyphens. */
    val code: String,
    val name: String,
    /** A preset to start from; left out: blank. */
    val preset: ForumTypePreset? = null,
)

data class ForumTypeVersionResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val number: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val state: ReviewState,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val revision: Long,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
    val description: String?,
    val defaultClassificationId: UUID?,
    val retention: ForumRetention?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val memberAttributes: List<MemberAttribute>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val quorum: QuorumRule,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val outcomes: List<Outcome>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val termPolicy: TermPolicy,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val submissionPolicy: SubmissionPolicy,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val agenda: AgendaPolicy,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val meetings: MeetingPolicy,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val minutes: MinutesPolicy,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val actions: ActionsPolicy,
    val createdBy: PersonRef?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val createdAt: Instant,
    val submittedBy: PersonRef?,
    val submittedAt: Instant?,
    val reviewedBy: PersonRef?,
    val reviewedAt: Instant?,
    val reviewRemark: String?,
    val publishedAt: Instant?,
)

data class ForumTypeResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val code: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val active: Boolean,
    /** The published version's name, else the open version's. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
    val publishedVersion: Int?,
    val openVersion: Int?,
    val openState: ReviewState?,
    val openSubmittedBy: PersonRef?,
)

data class ForumTypePresetResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val key: ForumTypePreset,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val settings: ForumTypeSettingsRequest,
)

/** A sample member for the quorum tester: role, vote, attributes and how they attend (absent unless [present]). */
data class QuorumSampleMember(
    val role: ForumRole = ForumRole.MEMBER,
    val voting: Boolean? = null,
    val attributes: Set<String> = emptySet(),
    val present: Boolean = false,
    val remote: Boolean = false,
    val byAlternate: Boolean = false,
    val recused: Boolean = false,
)

data class QuorumPreviewRequest(
    val rule: QuorumRule,
    /** The forum type's member attribute keys, which composition conditions may name. */
    val attributes: Set<String> = emptySet(),
    val members: List<QuorumSampleMember> = emptyList(),
)

/** Remark sent with a review decision. */
data class ReviewRemarkRequest(
    val remark: String? = null,
)

/**
 * Committee types (ADR-0056, design E01 §3): listed by the makers and checkers of `forum.types.*`, created blank or from
 * a preset as draft version 1, and switched on and off. Their versions are in [ForumTypeVersionsController].
 */
@RestController
@Tag(name = "forum")
class ForumTypesController(
    private val types: ForumTypes,
    private val responses: ForumTypeResponses,
    private val definitions: WorkflowDefinitions,
    private val transactions: TenantTransactions,
) {
    @GetMapping("/api/v1/admin/forum-types")
    @RequiresPermission(Permission.FORUM_TYPES_MANAGE, orAnyOf = [Permission.FORUM_TYPES_APPROVE, Permission.FORUMS_MANAGE])
    @Operation(operationId = "listForumTypes", summary = "Committee types with their published and open versions")
    fun list(): ResponseEntity<List<ForumTypeResponse>> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(read { responses.types(types.list()) })

    @GetMapping("/api/v1/admin/forum-types/presets")
    @RequiresPermission(Permission.FORUM_TYPES_MANAGE)
    @Operation(operationId = "listForumTypePresets", summary = "The presets a new committee type can start from")
    fun presets(): List<ForumTypePresetResponse> =
        ForumTypePreset.entries.map {
            ForumTypePresetResponse(it, it.displayName, it.settings.request())
        }

    /** What the Actions tab offers for `actions.definitionKey` (design E07 §1.13, flow AQ): definitions bound to action items. */
    @GetMapping("/api/v1/admin/forum-types/action-definitions")
    @RequiresPermission(Permission.FORUM_TYPES_MANAGE, orAnyOf = [Permission.FORUM_TYPES_APPROVE])
    @Operation(operationId = "listActionDefinitions", summary = "The published workflows a committee type's actions can run on")
    fun actionDefinitions(): List<DefinitionChoice> = read { definitions.bound(ActionsPolicy.SUBJECT) }

    @PostMapping("/api/v1/admin/forum-types")
    @RequiresPermission(Permission.FORUM_TYPES_MANAGE)
    @Operation(operationId = "createForumType", summary = "Create a committee type as draft version 1, blank or from a preset")
    fun create(
        @RequestBody body: CreateForumTypeRequest,
    ): ResponseEntity<ForumTypeResponse> {
        val created = write { responses.types(listOf(types.create(body.code, body.name, body.preset))).single() }
        return ResponseEntity.status(HttpStatus.CREATED).body(created)
    }

    @GetMapping("/api/v1/admin/forum-types/{typeId}")
    @RequiresPermission(Permission.FORUM_TYPES_MANAGE, orAnyOf = [Permission.FORUM_TYPES_APPROVE])
    @Operation(operationId = "getForumType", summary = "One committee type")
    fun get(
        @PathVariable typeId: UUID,
    ): ForumTypeResponse = read { responses.types(listOf(types.get(typeId))).single() }

    @PostMapping("/api/v1/admin/forum-types/{typeId}/activate")
    @RequiresPermission(Permission.FORUM_TYPES_MANAGE)
    @Operation(operationId = "activateForumType", summary = "Let new committees use this type")
    fun activate(
        @PathVariable typeId: UUID,
    ): ForumTypeResponse = write { responses.types(listOf(types.setActive(typeId, true))).single() }

    @PostMapping("/api/v1/admin/forum-types/{typeId}/deactivate")
    @RequiresPermission(Permission.FORUM_TYPES_MANAGE)
    @Operation(operationId = "deactivateForumType", summary = "Stop new committees using this type; existing ones keep it")
    fun deactivate(
        @PathVariable typeId: UUID,
    ): ForumTypeResponse = write { responses.types(listOf(types.setActive(typeId, false))).single() }

    private fun <T> read(block: () -> T): T = transactions.readInTenant(TenantContext.current(), block)

    private fun <T> write(block: () -> T): T = transactions.inTenant(TenantContext.current(), block)
}

/**
 * Committee-type versions (ADR-0056, design E01 §3): drafted and submitted by holders of `forum.types.manage`, published
 * (after a step-up) or sent back by a holder of `forum.types.approve` who did not submit them. Drafts change with
 * `If-Match`; one draft or version in review per type keeps a double click from making two. The quorum tester previews a
 * rule on sample members.
 */
@RestController
@Tag(name = "forum")
class ForumTypeVersionsController(
    private val lifecycle: ForumTypeLifecycle,
    private val minutesTemplates: MinutesTemplateService,
    private val responses: ForumTypeResponses,
    private val stepUp: StepUpCheck,
    private val transactions: TenantTransactions,
) {
    @GetMapping("/api/v1/admin/forum-types/{typeId}/versions")
    @RequiresPermission(Permission.FORUM_TYPES_MANAGE, orAnyOf = [Permission.FORUM_TYPES_APPROVE])
    @Operation(operationId = "listForumTypeVersions", summary = "A committee type's versions, newest first")
    fun versions(
        @PathVariable typeId: UUID,
    ): ResponseEntity<List<ForumTypeVersionResponse>> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(read { responses.versions(lifecycle.list(typeId)) })

    @GetMapping("/api/v1/admin/forum-types/{typeId}/versions/{number}")
    @RequiresPermission(Permission.FORUM_TYPES_MANAGE, orAnyOf = [Permission.FORUM_TYPES_APPROVE])
    @Operation(operationId = "getForumTypeVersion", summary = "One version of a committee type")
    fun version(
        @PathVariable typeId: UUID,
        @PathVariable number: String,
    ): ResponseEntity<ForumTypeVersionResponse> {
        val version = read { responses.version(lifecycle.get(typeId, number(number))) }
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .eTag(etagOf(version.revision))
            .body(version)
    }

    @PostMapping("/api/v1/admin/forum-types/{typeId}/versions")
    @RequiresPermission(Permission.FORUM_TYPES_MANAGE)
    @Operation(operationId = "createForumTypeDraft", summary = "Start a new draft from the published version")
    fun newDraft(
        @PathVariable typeId: UUID,
    ): ResponseEntity<ForumTypeVersionResponse> {
        val draft = write { responses.version(lifecycle.newDraft(typeId)) }
        return ResponseEntity.status(HttpStatus.CREATED).eTag(etagOf(draft.revision)).body(draft)
    }

    @PutMapping("/api/v1/admin/forum-types/{typeId}/versions/{number}")
    @RequiresPermission(Permission.FORUM_TYPES_MANAGE)
    @Operation(operationId = "replaceForumTypeDraft", summary = "Replace a draft's policy")
    fun replace(
        @PathVariable typeId: UUID,
        @PathVariable number: String,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: ForumTypeSettingsRequest,
    ): ResponseEntity<ForumTypeVersionResponse> {
        val revision = ifMatchVersion(ifMatch)
        val draft = write { responses.version(lifecycle.replace(typeId, number(number), revision, body.settings())) }
        return ResponseEntity.ok().eTag(etagOf(draft.revision)).body(draft)
    }

    @PostMapping("/api/v1/admin/forum-types/{typeId}/versions/{number}/submit")
    @RequiresPermission(Permission.FORUM_TYPES_MANAGE)
    @Operation(operationId = "submitForumTypeVersion", summary = "Send a draft for review")
    fun submit(
        @PathVariable typeId: UUID,
        @PathVariable number: String,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
    ): ResponseEntity<ForumTypeVersionResponse> {
        val revision = ifMatchVersion(ifMatch)
        // The minutes template is read and checked outside the transaction (design E06 §1.8); the If-Match keeps it current.
        minutesTemplates.requireUsable(typeId, number(number), revision)
        val submitted = write { responses.version(lifecycle.submit(typeId, number(number), revision)) }
        return ResponseEntity.ok().eTag(etagOf(submitted.revision)).body(submitted)
    }

    @PostMapping("/api/v1/admin/forum-types/{typeId}/versions/{number}/approve")
    @RequiresPermission(Permission.FORUM_TYPES_APPROVE)
    @Operation(operationId = "approveForumTypeVersion", summary = "Publish a version in review (not your own submission)")
    fun approve(
        @PathVariable typeId: UUID,
        @PathVariable number: String,
        @RequestBody(required = false) body: ReviewRemarkRequest?,
    ): ForumTypeVersionResponse {
        stepUp.require()
        return write { responses.version(lifecycle.approve(typeId, number(number), body?.remark)) }
    }

    @PostMapping("/api/v1/admin/forum-types/{typeId}/versions/{number}/send-back")
    @RequiresPermission(Permission.FORUM_TYPES_APPROVE)
    @Operation(operationId = "sendBackForumTypeVersion", summary = "Return a version in review to draft, saying why")
    fun sendBack(
        @PathVariable typeId: UUID,
        @PathVariable number: String,
        @RequestBody body: ReviewRemarkRequest,
    ): ForumTypeVersionResponse = write { responses.version(lifecycle.sendBack(typeId, number(number), body.remark)) }

    @PostMapping("/api/v1/forums/quorum-preview")
    @RequiresPermission(Permission.FORUM_TYPES_MANAGE, orAnyOf = [Permission.FORUM_TYPES_APPROVE])
    @Operation(operationId = "previewQuorum", summary = "Evaluate a quorum rule on sample members; nothing is saved")
    fun preview(
        @RequestBody body: QuorumPreviewRequest,
    ): QuorumResult = quorumPreview(body)

    private fun <T> read(block: () -> T): T = transactions.readInTenant(TenantContext.current(), block)

    private fun <T> write(block: () -> T): T = transactions.inTenant(TenantContext.current(), block)
}

/** The rule on the sample members (ADR-0057): checked like a saved rule, at most [MAX_SAMPLE] members, nothing saved. */
internal fun quorumPreview(body: QuorumPreviewRequest): QuorumResult {
    val problems = QuorumChecks.problems(body.rule, body.attributes)
    val tooMany = FieldProblem("members", "TOO_MANY", "At most $MAX_SAMPLE members.").takeIf { body.members.size > MAX_SAMPLE }
    requireNoProblems(problems + listOfNotNull(tooMany))
    val seats = body.members.mapIndexed { i, m -> QuorumSeat(UUID(0, i.toLong()), m.role, m.voting ?: m.role.votesByDefault, m.attributes) }
    val attendance =
        body.members
            .mapIndexedNotNull { i, m ->
                (UUID(0, i.toLong()) to QuorumPresence(m.remote, m.byAlternate, m.recused)).takeIf { m.present }
            }.toMap()
    return Quorum.evaluate(body.rule, seats, attendance)
}

private const val MAX_SAMPLE = 100

/** Responses of the committee-type endpoints, one batch of name lookups per call. */
@Component
class ForumTypeResponses(
    private val users: UserSummaries,
) {
    fun types(summaries: List<ForumTypeSummary>): List<ForumTypeResponse> {
        val people = names(summaries.mapNotNull { it.open?.submittedBy })
        return summaries.map { s ->
            ForumTypeResponse(
                id = s.type.id,
                code = s.type.code,
                active = s.type.active,
                name = (s.published ?: s.open)?.settings?.name.orEmpty(),
                publishedVersion = s.published?.number,
                openVersion = s.open?.number,
                openState = s.open?.state,
                openSubmittedBy = s.open?.submittedBy?.let { PersonRef(it, people[it].orEmpty()) },
            )
        }
    }

    fun version(version: ForumTypeVersionRow) = versions(listOf(version)).single()

    fun versions(versions: List<ForumTypeVersionRow>): List<ForumTypeVersionResponse> {
        val people = names(versions.flatMap { listOfNotNull(it.createdBy, it.submittedBy, it.reviewedBy) })

        fun named(id: UUID?) = id?.let { PersonRef(it, people[it].orEmpty()) }
        return versions.map { v ->
            val s = v.settings
            ForumTypeVersionResponse(
                number = v.number,
                state = v.state,
                revision = v.revision,
                name = s.name,
                description = s.description,
                defaultClassificationId = s.defaultClassificationId,
                retention = s.retention,
                memberAttributes = s.memberAttributes,
                quorum = s.quorum,
                outcomes = s.outcomes,
                termPolicy = s.termPolicy,
                submissionPolicy = s.submissionPolicy,
                agenda = s.agenda,
                meetings = s.meetings,
                minutes = s.minutes,
                actions = s.actions,
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

    private fun names(ids: Collection<UUID>): Map<UUID, String> = users.of(ids.toSet()).mapValues { it.value.displayName }
}

/** The policy as sent: trimmed text, empty description as none. */
internal fun ForumTypeSettingsRequest.settings() =
    ForumTypeSettings(
        name = name.trim(),
        description = description?.trim()?.ifEmpty { null },
        defaultClassificationId = defaultClassificationId,
        retention = retention,
        memberAttributes = memberAttributes.map { MemberAttribute(it.key.trim(), it.label.trim()) },
        quorum = quorum,
        outcomes = outcomes.map { it.copy(label = it.label.trim()) },
        termPolicy = termPolicy,
        submissionPolicy = submissionPolicy,
        agenda = agenda.trimmed(),
        meetings = meetings.copy(neutralTitle = meetings.neutralTitle.trim()),
        minutes = minutes,
        actions = actions.copy(definitionKey = actions.definitionKey.trim()),
    )

internal fun ForumTypeSettings.request() =
    ForumTypeSettingsRequest(
        name,
        description,
        defaultClassificationId,
        retention,
        memberAttributes,
        quorum,
        outcomes,
        termPolicy,
        submissionPolicy,
        agenda,
        meetings,
        minutes,
        actions,
    )

private fun number(path: String): Int = path.toIntOrNull() ?: throw ForumNotFoundException("forum type version")

/** The agenda policy as sent, with its titles trimmed. */
private fun AgendaPolicy.trimmed() =
    copy(sections = sections.map { s -> s.copy(title = s.title.trim(), items = s.items.map { it.copy(title = it.title.trim()) }) })
