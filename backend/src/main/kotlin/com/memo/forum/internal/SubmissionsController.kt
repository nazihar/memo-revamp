package com.memo.forum.internal

import com.memo.common.authz.SignedIn
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.etagOf
import com.memo.common.web.ifMatchVersion
import com.memo.identity.UserSummaries
import com.memo.memo.MemoReaders
import com.memo.workflow.ForumOutcome
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class SubmissionResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val forumId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val forumName: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val subjectType: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val subjectId: UUID,
    /** The memo's number and subject, when the subject is a memo the reader may read; otherwise null ("restricted"). */
    val memo: SubmittedMemo?,
    /** Whether the item waits for the committee's decision (ADR-0058). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val waits: Boolean,
    val previousId: UUID?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val purpose: SubmissionPurpose,
    val resolutionSought: String?,
    val presenter: PersonRef?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val status: SubmissionStatus,
    val targetDate: LocalDate?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val submittedBy: PersonRef,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val submittedAt: Instant,
    val triagedBy: PersonRef?,
    val triagedAt: Instant?,
    val reason: String?,
    val outcome: ForumOutcome?,
    val decidedAt: Instant?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val version: Long,
)

/** A submitted memo as its reader sees it. */
data class SubmittedMemo(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    val reference: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val subject: String,
)

data class CoverSheetRequest(
    val purpose: SubmissionPurpose,
    val resolutionSought: String? = null,
    val presenterId: UUID? = null,
    val targetDate: LocalDate? = null,
)

data class AcceptRequest(
    val targetDate: LocalDate? = null,
)

data class TriageRequest(
    val reason: String? = null,
    val targetDate: LocalDate? = null,
)

data class ReferRequest(
    val forumId: UUID,
    val reason: String? = null,
)

/**
 * A committee's queue of submissions (ADR-0059, design E02 §3): read by its officers; triaged by its secretaries and
 * administrators with `If-Match`. Returning and declining resume the waiting item through the workflow (ADR-0058).
 */
@RestController
@Tag(name = "forum")
class SubmissionsController(
    private val submissions: ForumSubmissions,
    private val triage: SubmissionTriage,
    private val responses: SubmissionResponses,
    private val transactions: TenantTransactions,
) {
    @GetMapping("/api/v1/forums/{forumId}/submissions")
    @SignedIn
    @Operation(operationId = "listForumSubmissions", summary = "A committee's submissions, oldest first (open ones by default)")
    fun queue(
        @PathVariable forumId: UUID,
        @RequestParam(required = false) status: Set<SubmissionStatus>?,
    ): ResponseEntity<List<SubmissionResponse>> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            read { responses.of(submissions.queue(forumId, status.orEmpty())) },
        )

    @GetMapping("/api/v1/forums/{forumId}/submissions/{id}")
    @SignedIn
    @Operation(operationId = "getForumSubmission", summary = "One submission")
    fun get(
        @PathVariable forumId: UUID,
        @PathVariable id: UUID,
    ): ResponseEntity<SubmissionResponse> {
        val row = read { responses.of(listOf(submissions.get(forumId, id))).single() }
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .eTag(etagOf(row.version))
            .body(row)
    }

    @PatchMapping("/api/v1/forums/{forumId}/submissions/{id}")
    @SignedIn
    @Operation(operationId = "editForumSubmission", summary = "Change a submission's cover sheet")
    fun edit(
        @PathVariable forumId: UUID,
        @PathVariable id: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: CoverSheetRequest,
    ): SubmissionResponse {
        val version = ifMatchVersion(ifMatch)
        val sheet = CoverSheet(body.purpose, body.resolutionSought, body.presenterId, body.targetDate)
        return write { triage.edit(forumId, id, version, sheet) }
    }

    @PostMapping("/api/v1/forums/{forumId}/submissions/{id}/accept")
    @SignedIn
    @Operation(operationId = "acceptForumSubmission", summary = "Accept a submission for a meeting")
    fun accept(
        @PathVariable forumId: UUID,
        @PathVariable id: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody(required = false) body: AcceptRequest?,
    ): SubmissionResponse {
        val version = ifMatchVersion(ifMatch)
        return write { triage.accept(forumId, id, version, body?.targetDate) }
    }

    @PostMapping("/api/v1/forums/{forumId}/submissions/{id}/defer")
    @SignedIn
    @Operation(operationId = "deferForumSubmission", summary = "Defer a submission, saying why")
    fun defer(
        @PathVariable forumId: UUID,
        @PathVariable id: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: TriageRequest,
    ): SubmissionResponse {
        val version = ifMatchVersion(ifMatch)
        return write { triage.defer(forumId, id, version, body.reason, body.targetDate) }
    }

    @PostMapping("/api/v1/forums/{forumId}/submissions/{id}/return")
    @SignedIn
    @Operation(operationId = "returnForumSubmission", summary = "Return a submission to its author for more information")
    fun returnForInformation(
        @PathVariable forumId: UUID,
        @PathVariable id: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: TriageRequest,
    ): SubmissionResponse {
        val version = ifMatchVersion(ifMatch)
        return write { triage.returnForInformation(forumId, id, version, body.reason) }
    }

    @PostMapping("/api/v1/forums/{forumId}/submissions/{id}/decline")
    @SignedIn
    @Operation(operationId = "declineForumSubmission", summary = "Decline a submission, saying why; the item goes back to its author")
    fun decline(
        @PathVariable forumId: UUID,
        @PathVariable id: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: TriageRequest,
    ): SubmissionResponse {
        val version = ifMatchVersion(ifMatch)
        return write { triage.decline(forumId, id, version, body.reason) }
    }

    @PostMapping("/api/v1/forums/{forumId}/submissions/{id}/refer")
    @SignedIn
    @Operation(operationId = "referForumSubmission", summary = "Refer a submission to another committee")
    fun refer(
        @PathVariable forumId: UUID,
        @PathVariable id: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: ReferRequest,
    ): SubmissionResponse {
        val version = ifMatchVersion(ifMatch)
        return write { triage.refer(forumId, id, version, body.forumId, body.reason) }
    }

    @GetMapping("/api/v1/memos/{memoId}/forum-submissions")
    @SignedIn
    @Operation(operationId = "listMemoForumSubmissions", summary = "Where a memo stands with committees, newest first")
    fun ofMemo(
        @PathVariable memoId: UUID,
    ): ResponseEntity<List<SubmissionResponse>> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(read { responses.of(submissions.ofMemo(memoId)) })

    private fun <T> read(block: () -> T): T = transactions.readInTenant(TenantContext.current(), block)

    private fun write(block: () -> SubmissionRow): SubmissionResponse =
        transactions.inTenant(TenantContext.current()) { responses.of(listOf(block())).single() }
}

/** Submission responses with names, one lookup per call. */
@Component
class SubmissionResponses(
    private val users: UserSummaries,
    private val forums: ForumStore,
    private val memos: MemoReaders,
) {
    fun of(rows: List<SubmissionRow>): List<SubmissionResponse> {
        val forumNames = forums.all().associate { it.id to it.name }
        val readable = memos.readable(rows.filter { it.subjectType == MemoReaders.SUBJECT_TYPE }.map { it.subjectId }.toSet())
        val names =
            users
                .of(
                    rows
                        .flatMap {
                            listOfNotNull(it.submittedBy, it.presenterId, it.triagedBy)
                        }.toSet(),
                ).mapValues { it.value.displayName }

        fun person(id: UUID?) = id?.let { PersonRef(it, names[it].orEmpty()) }
        return rows.map {
            SubmissionResponse(
                id = it.id,
                forumId = it.forumId,
                forumName = forumNames[it.forumId].orEmpty(),
                subjectType = it.subjectType,
                subjectId = it.subjectId,
                memo = readable[it.subjectId]?.let { m -> SubmittedMemo(m.id, m.reference, m.subject) },
                waits = it.waits,
                previousId = it.previousId,
                purpose = it.purpose,
                resolutionSought = it.resolutionSought,
                presenter = person(it.presenterId),
                status = it.status,
                targetDate = it.targetDate,
                submittedBy = checkNotNull(person(it.submittedBy)),
                submittedAt = it.submittedAt,
                triagedBy = person(it.triagedBy),
                triagedAt = it.triagedAt,
                reason = it.reason,
                outcome = it.outcome,
                decidedAt = it.decidedAt,
                version = it.version,
            )
        }
    }
}
