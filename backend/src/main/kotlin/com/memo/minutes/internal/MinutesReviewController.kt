package com.memo.minutes.internal

import com.memo.common.authz.ForbiddenException
import com.memo.common.authz.SignedIn
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.etagOf
import com.memo.common.web.ifMatchVersion
import com.memo.files.FileServing
import com.memo.files.OpenAudit
import com.memo.files.ServeOutcome
import com.memo.files.ServedContent
import com.memo.identity.UserSummaries
import com.memo.meeting.MeetingViewers
import com.memo.system.Idempotency
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The reviewer's answer; a secretary records an agreement given outside the app with [how]. */
data class MinutesReviewRequest(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val decision: ReviewDecision,
    /** Comments to the secretary (required to send the draft back), 0 to 1,000 characters. */
    val comment: String? = null,
    /** On the reviewer's behalf: how they agreed. */
    val how: AgreedHow? = null,
)

/** Circulate for comments: the deadline's day (default: the committee's working days), and why nobody reviewed. */
data class MinutesCirculateRequest(
    val commentsUntil: LocalDate? = null,
    /** Required when no chair or vice-chair is able to review (they declared an interest, or there are none). */
    val reason: String? = null,
)

data class MinutesDeadlineRequest(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val commentsUntil: LocalDate,
)

data class MinutesReasonRequest(
    val reason: String? = null,
)

/** Ask people outside the circulation for comments on named items (CGI §6.1's technical review). */
data class MinutesCommentRequestRequest(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val userIds: List<UUID>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val itemIds: List<UUID>,
)

/** "No comments", or a suggestion on an item or the minutes in general (1 to 2,000 characters). */
data class MinutesCommentInput(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val kind: CommentKind,
    val itemId: UUID? = null,
    val text: String? = null,
)

data class MinutesCommentResponseRequest(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val response: CommentResponse,
    val note: String? = null,
)

/** A comment as its reader (their own) or the officers (all) see it, with the secretary's response. */
data class MinutesCommentResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val by: MinutesPerson,
    val itemId: UUID?,
    val itemNumber: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val kind: CommentKind,
    val text: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val at: Instant,
    val response: CommentResponse?,
    val responseNote: String?,
    val respondedBy: MinutesPerson?,
    val respondedAt: Instant?,
    /** The viewer's own comment. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val mine: Boolean,
)

data class MinutesCommentsResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val comments: List<MinutesCommentResponse>,
    /** The officers see everyone's comments; a reader their own. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val all: Boolean,
)

/** The comments as the API answers them, with names and item numbers. */
@Service
class MinutesCommentViews(
    private val comments: MinutesComments,
    private val reading: MinutesReading,
    private val users: UserSummaries,
) {
    fun list(meetingId: UUID): MinutesCommentsResponse {
        val (rows, all) = comments.commentsFor(meetingId)
        val view = reading.actorView(meetingId)
        val numbers = view.agenda.items.associate { it.id to it.number }
        val names = users.names(rows.flatMap { listOfNotNull(it.userId, it.respondedBy) }.toSet())
        return MinutesCommentsResponse(rows.map { response(it, view.userId, numbers, names) }, all)
    }

    fun one(
        meetingId: UUID,
        row: CommentRow,
    ): MinutesCommentResponse {
        val view = reading.actorView(meetingId)
        val names = users.names(listOfNotNull(row.userId, row.respondedBy).toSet())
        return response(row, view.userId, view.agenda.items.associate { it.id to it.number }, names)
    }

    private fun response(
        c: CommentRow,
        viewer: UUID,
        numbers: Map<UUID, String?>,
        names: Map<UUID, String>,
    ) = MinutesCommentResponse(
        id = c.id,
        by = MinutesPerson(c.userId, names[c.userId].orEmpty()),
        itemId = c.itemId,
        itemNumber = c.itemId?.let { numbers[it] },
        kind = c.kind,
        text = c.text,
        at = c.at,
        response = c.response,
        responseNote = c.responseNote,
        respondedBy = c.respondedBy?.let { MinutesPerson(it, names[it].orEmpty()) },
        respondedAt = c.respondedAt,
        mine = c.userId == viewer,
    )
}

/**
 * The chair's review, the circulation, the readers' copies and the chair's confirmation (design E06 §3, flows AD–AG;
 * ADR-0066). Each change takes `Idempotency-Key` and, for a step of the minutes, `If-Match` on the minutes' version, and
 * answers the page. Who may comes first (404, 403), then the structure check and the step-up, then the change.
 */
@RestController
@Tag(name = "minutes")
class MinutesReviewController(
    private val pages: MinutesPages,
    private val review: MinutesReview,
    private val comments: MinutesComments,
    private val confirmation: MinutesConfirmation,
    private val reading: MinutesReading,
    private val drafts: MinutesDrafts,
    private val checks: MinutesStructureChecks,
    private val viewers: MeetingViewers,
    private val idempotency: Idempotency,
    private val transactions: TenantTransactions,
    private val json: JsonMapper,
) {
    @PostMapping("/api/v1/meetings/{meetingId}/minutes/send-for-review")
    @SignedIn
    @Operation(operationId = "sendMinutesForReview", summary = "Send the draft minutes to the chair (or vice-chair) for review")
    fun sendForReview(
        @PathVariable meetingId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
    ): ResponseEntity<MinutesResponse> {
        val version = ifMatchVersion(ifMatch)
        read { drafts.keeper(meetingId) }
        checks.requireReady(meetingId, MinutesStep.REVIEW)
        return changed(meetingId, "minutes.send", key, "$version") { review.sendForReview(meetingId, version) }
    }

    @PostMapping("/api/v1/meetings/{meetingId}/minutes/review")
    @SignedIn
    @Operation(
        operationId = "reviewMinutes",
        summary = "Agree the draft minutes or send them back (the reviewer, or a secretary on their behalf)",
    )
    fun review(
        @PathVariable meetingId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: MinutesReviewRequest,
    ): ResponseEntity<MinutesResponse> {
        val version = ifMatchVersion(ifMatch)
        read { requireReviewerOrSecretary(meetingId) }
        val ready = body.decision != ReviewDecision.AGREE || checks.blocker(meetingId, MinutesStep.CIRCULATE) == null
        return changed(meetingId, "minutes.review", key, "$version\n${json.writeValueAsString(body)}") {
            review.review(meetingId, version, body.decision, body.comment, body.how, ready)
        }
    }

    @PostMapping("/api/v1/meetings/{meetingId}/minutes/circulate")
    @SignedIn
    @Operation(operationId = "circulateMinutes", summary = "Circulate the draft minutes for comments")
    fun circulate(
        @PathVariable meetingId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody(required = false) body: MinutesCirculateRequest?,
    ): ResponseEntity<MinutesResponse> {
        val version = ifMatchVersion(ifMatch)
        read { drafts.keeper(meetingId) }
        checks.requireReady(meetingId, MinutesStep.CIRCULATE)
        return changed(meetingId, "minutes.circulate", key, "$version\n${json.writeValueAsString(body)}") {
            review.circulate(meetingId, version, body?.commentsUntil, body?.reason)
        }
    }

    @PutMapping("/api/v1/meetings/{meetingId}/minutes/comments-until")
    @SignedIn
    @Operation(operationId = "changeMinutesDeadline", summary = "Change the comment deadline of circulated minutes")
    fun deadline(
        @PathVariable meetingId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: MinutesDeadlineRequest,
    ): ResponseEntity<MinutesResponse> {
        val version = ifMatchVersion(ifMatch)
        return changed(meetingId, "minutes.deadline", key, "$version\n${body.commentsUntil}") {
            review.changeDeadline(meetingId, version, body.commentsUntil)
        }
    }

    @PostMapping("/api/v1/meetings/{meetingId}/minutes/withdraw")
    @SignedIn
    @Operation(operationId = "withdrawMinutes", summary = "Take circulated minutes back to the draft, with a reason")
    fun withdraw(
        @PathVariable meetingId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: MinutesReasonRequest,
    ): ResponseEntity<MinutesResponse> {
        val version = ifMatchVersion(ifMatch)
        return changed(meetingId, "minutes.withdraw", key, "$version\n${body.reason}") { review.withdraw(meetingId, version, body.reason) }
    }

    @PostMapping("/api/v1/meetings/{meetingId}/minutes/end-circulation")
    @SignedIn
    @Operation(operationId = "endMinutesCirculation", summary = "End the comment period early (approval by circulation), with a reason")
    fun endCirculation(
        @PathVariable meetingId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: MinutesReasonRequest,
    ): ResponseEntity<MinutesResponse> {
        val version = ifMatchVersion(ifMatch)
        return changed(meetingId, "minutes.end", key, "$version\n${body.reason}") { review.endCirculation(meetingId, version, body.reason) }
    }

    @PostMapping("/api/v1/meetings/{meetingId}/minutes/comment-requests")
    @SignedIn
    @Operation(operationId = "askForMinutesComments", summary = "Ask named people for comments on items of circulated minutes")
    fun ask(
        @PathVariable meetingId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: MinutesCommentRequestRequest,
    ): ResponseEntity<MinutesResponse> =
        changed(
            meetingId,
            "minutes.ask",
            key,
            json.writeValueAsString(body),
        ) { comments.askForComments(meetingId, body.userIds, body.itemIds) }

    @PostMapping("/api/v1/meetings/{meetingId}/minutes/confirm")
    @SignedIn
    @Operation(operationId = "confirmMinutes", summary = "Approve the minutes (the chair, with a fresh step-up): they become the record")
    fun confirm(
        @PathVariable meetingId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
    ): ResponseEntity<MinutesResponse> {
        val version = ifMatchVersion(ifMatch)
        // 404 and 403 before the step-up, so a refusal never asks for one; then the step-up; then the document's check.
        read { confirmation.requireConfirmer(meetingId) }
        confirmation.requireStepUp()
        checks.requireReady(meetingId, MinutesStep.CIRCULATE)
        return changed(meetingId, "minutes.confirm", key, "$version") { confirmation.confirm(meetingId, version) }
    }

    /** The reviewer, or a secretary of the committee (403); 404 for a meeting the actor does not see. */
    private fun requireReviewerOrSecretary(meetingId: UUID) {
        val reader = runCatching { reading.reader(meetingId) }.getOrNull()
        val view = viewers.of(meetingId)
        if (reader?.access != MinutesAccessKind.REVIEWER && !view.manages) throw ForbiddenException(null)
    }

    private fun changed(
        meetingId: UUID,
        operation: String,
        key: String,
        request: String,
        work: () -> Any,
    ): ResponseEntity<MinutesResponse> {
        val page =
            transactions.inTenant(TenantContext.current()) {
                idempotency.once(operation, key, fingerprint("$meetingId\n$request")) {
                    work()
                    meetingId.toString()
                }
                // A reviewer who sent the draft back reads it no more: the step is done, with no page to show (204).
                runCatching { pages.view(meetingId) }.getOrElse { if (it is MinutesNotFoundException) null else throw it }
            }
        return if (page == null) {
            ResponseEntity.noContent().build()
        } else {
            ResponseEntity
                .ok()
                .eTag(etagOf(page.version))
                .cacheControl(CacheControl.noStore())
                .body(page)
        }
    }

    private fun <T : Any> read(block: () -> T): T = transactions.readInTenant(TenantContext.current(), block)
}

/**
 * The readers' side of the minutes (design E06 §3, flows AE–AG; ADR-0066): the comments, the reader's copy (streamed through
 * `FileServing` after the minutes' own rule, the open audited once per session) and the officers' preview "as", and the
 * approved minutes' stamps with their check.
 */
@RestController
@Tag(name = "minutes")
class MinutesReadingController(
    private val comments: MinutesComments,
    private val confirmation: MinutesConfirmation,
    private val reading: MinutesReading,
    private val commentViews: MinutesCommentViews,
    private val serving: FileServing,
    private val idempotency: Idempotency,
    private val transactions: TenantTransactions,
    private val json: JsonMapper,
) {
    @GetMapping("/api/v1/meetings/{meetingId}/minutes/comments")
    @SignedIn
    @Operation(operationId = "listMinutesComments", summary = "The comments on circulated minutes: all for the officers, a reader's own")
    fun comments(
        @PathVariable meetingId: UUID,
    ): ResponseEntity<MinutesCommentsResponse> = noStore(read { commentViews.list(meetingId) })

    @PostMapping("/api/v1/meetings/{meetingId}/minutes/comments")
    @SignedIn
    @Operation(operationId = "commentOnMinutes", summary = "No comments, or suggest a correction to circulated minutes")
    fun comment(
        @PathVariable meetingId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: MinutesCommentInput,
    ): ResponseEntity<MinutesCommentResponse> {
        val answer =
            transactions.inTenant(TenantContext.current()) {
                val id =
                    idempotency.once("minutes.comment", key, fingerprint("$meetingId\n${json.writeValueAsString(body)}")) {
                        comments.comment(meetingId, body.kind, body.itemId, body.text).id.toString()
                    }
                commentViews.list(meetingId).comments.first { it.id.toString() == id }
            }
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(answer)
    }

    @PostMapping("/api/v1/meetings/{meetingId}/minutes/comments/{commentId}/response")
    @SignedIn
    @Operation(operationId = "respondToMinutesComment", summary = "Adopt or decline a suggestion (secretaries, once)")
    fun respond(
        @PathVariable meetingId: UUID,
        @PathVariable commentId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: MinutesCommentResponseRequest,
    ): ResponseEntity<MinutesCommentResponse> {
        val answer =
            transactions.inTenant(TenantContext.current()) {
                idempotency.once("minutes.comment.response", key, fingerprint("$meetingId\n$commentId\n${json.writeValueAsString(body)}")) {
                    comments.respond(meetingId, commentId, body.response, body.note).id.toString()
                }
                commentViews.list(meetingId).comments.first { it.id == commentId }
            }
        return noStore(answer)
    }

    @GetMapping("/api/v1/meetings/{meetingId}/minutes/approvals")
    @SignedIn
    @Operation(operationId = "listMinutesApprovals", summary = "The approved minutes' authenticated approvals (stamps)")
    fun approvals(
        @PathVariable meetingId: UUID,
    ): ResponseEntity<List<MinutesApprovalResponse>> = noStore(read { confirmation.approvalsOf(meetingId) })

    @PostMapping("/api/v1/meetings/{meetingId}/minutes/approvals/{approvalId}/check")
    @SignedIn
    @Operation(operationId = "checkMinutesApproval", summary = "Recompute an approval's hashes and its place in the committee's chain")
    fun check(
        @PathVariable meetingId: UUID,
        @PathVariable approvalId: UUID,
    ): ResponseEntity<MinutesApprovalCheckResponse> =
        noStore(transactions.inTenant(TenantContext.current()) { confirmation.check(meetingId, approvalId) })

    @GetMapping("/api/v1/meetings/{meetingId}/minutes/document")
    @SignedIn
    @Operation(
        operationId = "getMinutesDocument",
        summary = "The viewer's copy of the draft minutes (PDF, supports Range); 202 while it is being made",
    )
    fun document(
        @PathVariable meetingId: UUID,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        val (reader, lookup) = transactions.inTenant(TenantContext.current()) { reading.document(meetingId) }
        serve(reader, lookup, null, request, response)
    }

    @GetMapping("/api/v1/meetings/{meetingId}/minutes/preview")
    @SignedIn
    @Operation(
        operationId = "previewMinutesCopy",
        summary = "The copy a given reader gets (secretaries), the secretary's own recusals applying; 202 while it is being made",
    )
    fun preview(
        @PathVariable meetingId: UUID,
        @RequestParam("as") userId: UUID,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        val (reader, lookup) = transactions.inTenant(TenantContext.current()) { reading.preview(meetingId, userId) }
        serve(reader, lookup, userId, request, response)
    }

    /** Streams the copy (the open audited once per session, ADR-0061's `FileServing`), or answers 202 while it is made. */
    private fun serve(
        reader: MinutesReader,
        lookup: CopyLookup,
        previewAs: UUID?,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        val copy =
            when (lookup) {
                is CopyLookup.Building -> {
                    response.status = HttpStatus.ACCEPTED.value()
                    response.setHeader("Retry-After", RETRY_AFTER_SECONDS.toString())
                    response.setHeader("Cache-Control", "no-store")
                    return
                }

                is CopyLookup.Ready -> {
                    lookup.copy
                }
            }
        val audit =
            OpenAudit(
                action = "minutes.copy.opened",
                objectType = MinutesDrafts.OBJECT_TYPE,
                objectId = reader.row.meetingId.toString(),
                details =
                    mapOf(
                        "stage" to copy.stage.name,
                        "setKey" to copy.setKey,
                        "sourceVersionId" to copy.sourceVersionId.toString(),
                        "previewAs" to previewAs?.toString(),
                    ),
            )
        val outcome = serving.serve(checkNotNull(copy.fileVersionId), ServedContent.ORIGINAL, request, response, audit)
        if (outcome != ServeOutcome.SERVED) throw MinutesNotFoundException("copy")
    }

    private fun <T : Any> read(block: () -> T): T = transactions.readInTenant(TenantContext.current(), block)

    private fun <T : Any> noStore(body: T): ResponseEntity<T> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)

    private companion object {
        const val RETRY_AFTER_SECONDS = 10
    }
}
