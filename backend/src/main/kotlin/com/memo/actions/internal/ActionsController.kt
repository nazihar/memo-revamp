package com.memo.actions.internal

import com.memo.common.authz.SignedIn
import com.memo.common.ratelimit.RateLimit
import com.memo.common.ratelimit.RateLimiter
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.RefusedException
import com.memo.common.web.etagOf
import com.memo.common.web.ifMatchVersion
import com.memo.system.Idempotency
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
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
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID

/** A draft's fields as the secretary sends them (design E07 §3); on PATCH they replace the draft's. */
data class ActionFieldsRequest(
    /** What is to be done, 1 to 200 characters; from a condition, its text when left out. */
    val title: String? = null,
    val description: String? = null,
    /** What the owner is told of the source, up to 2,000 characters; from a condition, its text when left out. */
    val context: String? = null,
    val owner: OwnerInput? = null,
    /** Up to five people besides the owner. */
    val contributors: List<UUID> = emptyList(),
    val dueDate: LocalDate? = null,
    val priority: ActionPriority = ActionPriority.NORMAL,
    /** The source's level by default; never lower. */
    val classificationId: UUID? = null,
    /** DIRECT only: "Chair's instruction of 3 December", up to 300 characters. */
    val sourceNote: String? = null,
) {
    fun fields() = ActionFields(title, description, context, owner, contributors, dueDate, priority, classificationId, sourceNote)
}

/** A draft raised from a meeting: from a decision (and one of its conditions), an item, or the meeting as a whole. */
data class RaiseActionRequest(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val source: ActionSource,
    val itemId: UUID? = null,
    val decisionId: UUID? = null,
    /** The condition line of the decision, counted from 0. */
    val conditionIndex: Int? = null,
    val title: String? = null,
    val description: String? = null,
    val context: String? = null,
    val owner: OwnerInput? = null,
    val contributors: List<UUID> = emptyList(),
    val dueDate: LocalDate? = null,
    val priority: ActionPriority = ActionPriority.NORMAL,
    val classificationId: UUID? = null,
) {
    fun input() =
        RaiseInput(
            source,
            itemId,
            decisionId,
            conditionIndex,
            ActionFields(title, description, context, owner, contributors, dueDate, priority, classificationId),
        )
}

/** Discarding a draft: an optional reason, up to 500 characters. */
data class DiscardActionRequest(
    val reason: String? = null,
)

/**
 * Action items of a meeting and a committee (ADR-0067, design E07 §3): drafts raised from a meeting or directly on a
 * committee by its secretaries and `forums.manage`, a meeting's drafts issued at once (one transaction each), a
 * meeting's actions and "My actions". One action is [ActionController]. Reads go through [ActionAccess]: an
 * action the viewer may not see is 404. Writes need an `Idempotency-Key` and share the agenda's per-person rate limit.
 */
@RestController
@Tag(name = "actions")
class ActionsController(
    private val raising: ActionRaising,
    private val issuing: ActionIssuing,
    private val access: ActionAccess,
    private val calls: ActionCalls,
    private val idempotency: Idempotency,
    private val json: JsonMapper,
) {
    @PostMapping("/api/v1/meetings/{meetingId}/actions")
    @SignedIn
    @Operation(operationId = "raiseMeetingAction", summary = "Raise a draft action from a decision, an item or the meeting (secretaries)")
    fun raise(
        @PathVariable meetingId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: RaiseActionRequest,
    ): ResponseEntity<ActionResponse> {
        calls.limit()
        val request = "$meetingId\n${json.writeValueAsString(body)}"
        return created(
            calls.write {
                val id = idempotency.once("actions.raise", key, fingerprint(request)) { raising.raise(meetingId, body.input()).toString() }
                access.action(UUID.fromString(id))
            },
        )
    }

    @PostMapping("/api/v1/forums/{forumId}/actions")
    @SignedIn
    @Operation(operationId = "raiseDirectAction", summary = "Raise a direct draft action on a committee (secretaries)")
    fun raiseDirect(
        @PathVariable forumId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: ActionFieldsRequest,
    ): ResponseEntity<ActionResponse> {
        calls.limit()
        val request = "$forumId\n${json.writeValueAsString(body)}"
        return created(
            calls.write {
                val id =
                    idempotency.once(
                        "actions.raise.direct",
                        key,
                        fingerprint(request),
                    ) { raising.raiseDirect(forumId, body.fields()).toString() }
                access.action(UUID.fromString(id))
            },
        )
    }

    @GetMapping("/api/v1/meetings/{meetingId}/actions")
    @SignedIn
    @Operation(operationId = "getMeetingActions", summary = "A meeting's actions as the viewer may see them")
    fun ofMeeting(
        @PathVariable meetingId: UUID,
    ): ResponseEntity<MeetingActionsResponse> = noStore(calls.read { access.ofMeeting(meetingId) })

    @PostMapping("/api/v1/meetings/{meetingId}/actions/issue")
    @SignedIn
    @Operation(operationId = "issueMeetingActions", summary = "Send every draft of a meeting the actor may issue to its owner")
    fun issueAll(
        @PathVariable meetingId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
    ): ResponseEntity<IssueResultsResponse> {
        calls.limit()
        val drafts =
            calls
                .write {
                    idempotency.once("actions.issue.meeting", key, fingerprint("$meetingId")) {
                        issuing.issuable(meetingId).joinToString(",") { "${it.id}:${it.version}" }
                    }
                }.split(',')
                .filter { it.isNotEmpty() }
                .map { it.substringBefore(':').let(UUID::fromString) to it.substringAfter(':').toLong() }
        // One transaction per draft: a refusal stops nothing else, and a replay of the key replays each one's result.
        val results =
            drafts.map { (id, version) ->
                runCatching {
                    calls.write {
                        idempotency
                            .once("actions.issue.meeting.$id", key, fingerprint("$meetingId\n$id")) {
                                issuing.issue(id, version).reference.orEmpty()
                            }
                    }
                }.fold(
                    onSuccess = { IssueResult(id, issued = true, reference = it) },
                    onFailure = { IssueResult(id, issued = false, reason = reason(it)) },
                )
            }
        return noStore(IssueResultsResponse(results))
    }

    @GetMapping("/api/v1/me/actions")
    @SignedIn
    @Operation(operationId = "getMyActions", summary = "My actions: those I own or hold, by due date")
    fun mine(
        @RequestParam(required = false) tab: MyActionsTab?,
        @RequestParam(required = false) forumId: UUID?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false, defaultValue = "50") limit: Int,
    ): ResponseEntity<MyActionsResponse> = noStore(calls.read { access.mine(tab ?: MyActionsTab.MINE, forumId, cursor, limit) })
}

/**
 * One action (design E07 §3): read by the one rule ([ActionAccess]; 404 when the viewer may not see it), and, while it is a
 * draft, edited, discarded or issued by the committee's secretaries and `forums.manage` not recused from its item, with
 * If-Match on the action's version (the ETag).
 */
@RestController
@Tag(name = "actions")
class ActionController(
    private val raising: ActionRaising,
    private val issuing: ActionIssuing,
    private val access: ActionAccess,
    private val calls: ActionCalls,
    private val idempotency: Idempotency,
) {
    @GetMapping("/api/v1/actions/{actionId}")
    @SignedIn
    @Operation(operationId = "getAction", summary = "An action as the viewer may see it")
    fun action(
        @PathVariable actionId: UUID,
    ): ResponseEntity<ActionResponse> = tagged(calls.read { access.action(actionId) })

    @PatchMapping("/api/v1/actions/{actionId}")
    @SignedIn
    @Operation(operationId = "editAction", summary = "Replace a draft action's fields (secretaries)")
    fun edit(
        @PathVariable actionId: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: ActionFieldsRequest,
    ): ResponseEntity<ActionResponse> {
        calls.limit()
        val version = ifMatchVersion(ifMatch)
        return tagged(
            calls.write {
                raising.edit(actionId, version, body.fields())
                access.action(actionId)
            },
        )
    }

    @PostMapping("/api/v1/actions/{actionId}/discard")
    @SignedIn
    @Operation(operationId = "discardAction", summary = "Discard a draft action, kept and never numbered (secretaries)")
    fun discard(
        @PathVariable actionId: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody(required = false) body: DiscardActionRequest?,
    ): ResponseEntity<ActionResponse> {
        calls.limit()
        val version = ifMatchVersion(ifMatch)
        val request = "$actionId\n$version\n${body?.reason}"
        return tagged(
            calls.write {
                idempotency.once(
                    "actions.discard",
                    key,
                    fingerprint(request),
                ) { raising.discard(actionId, version, body?.reason).id.toString() }
                access.action(actionId)
            },
        )
    }

    @PostMapping("/api/v1/actions/{actionId}/issue")
    @SignedIn
    @Operation(operationId = "issueAction", summary = "Issue a draft action: its reference, its workflow and its owner told (secretaries)")
    fun issue(
        @PathVariable actionId: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestHeader("Idempotency-Key") key: String,
    ): ResponseEntity<ActionResponse> {
        calls.limit()
        val version = ifMatchVersion(ifMatch)
        return tagged(
            calls.write {
                idempotency.once(
                    "actions.issue",
                    key,
                    fingerprint("$actionId\n$version"),
                ) { issuing.issue(actionId, version).id.toString() }
                access.action(actionId)
            },
        )
    }
}

/** What the actions controllers share: the tenant transaction, the per-person rate limit and the answers' headers. */
@Component
class ActionCalls(
    private val transactions: TenantTransactions,
    private val limiter: RateLimiter,
) {
    fun <T : Any> read(block: () -> T): T = transactions.readInTenant(TenantContext.current(), block)

    fun <T : Any> write(block: () -> T): T = transactions.inTenant(TenantContext.current(), block)

    /** The agenda's per-person write limit. */
    fun limit() {
        actorOrNull()?.let { limiter.consume(RateLimit.AGENDA_WRITE_PER_USER, "${TenantContext.current()}:$it") }
    }
}

internal fun <T : Any> noStore(body: T): ResponseEntity<T> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)

internal fun tagged(action: ActionResponse): ResponseEntity<ActionResponse> =
    ResponseEntity
        .ok()
        .cacheControl(CacheControl.noStore())
        .eTag(etagOf(action.version))
        .body(action)

internal fun created(action: ActionResponse): ResponseEntity<ActionResponse> =
    ResponseEntity
        .status(HttpStatus.CREATED)
        .cacheControl(CacheControl.noStore())
        .eTag(etagOf(action.version))
        .body(action)

/** Why one draft of a bulk issue was not issued: a problem code. */
internal fun reason(failure: Throwable): String =
    when (failure) {
        is ActionRejectedException -> failure.problems.firstOrNull()?.code ?: "REJECTED"
        is RefusedException -> failure.reason
        is com.memo.common.authz.ForbiddenException -> failure.reason ?: "FORBIDDEN"
        is com.memo.common.concurrency.ConcurrentModificationException -> "CONFLICT"
        is ActionNotFoundException -> "NOT_FOUND"
        else -> throw failure
    }
