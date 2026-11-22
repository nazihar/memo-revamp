package com.memo.actions.internal

import com.memo.common.authz.SignedIn
import com.memo.common.web.ifMatchVersion
import com.memo.system.Idempotency
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID

/** A progress update: what is happening, up to 4,000 characters, with the owner's word and evidence. */
data class ActionUpdateRequest(
    val text: String? = null,
    /** The owner (or a secretary writing for them) says how it is going. */
    val confidence: Confidence? = null,
    /** `action.evidence` files uploaded for this action by the person writing, up to 10. */
    val fileIds: List<UUID> = emptyList(),
    /** A secretary writing for the owner names them, and how they heard. */
    val onBehalfOf: UUID? = null,
    val how: How? = null,
    /** The number of an earlier update of yours this one corrects. */
    val corrects: Int? = null,
) {
    fun input() = UpdateInput(text, confidence, fileIds, onBehalfOf, how, corrects)
}

/** "I have started", by the owner, or by a secretary for them. */
data class ActionStartedRequest(
    val onBehalfOf: UUID? = null,
    val how: How? = null,
)

/** A new due date and why, up to 500 characters: asked for by the owner, or set directly. */
data class ActionDueRequest(
    val newDate: LocalDate? = null,
    val reason: String? = null,
)

/** The answer to an owner's request for a new date, with an optional note up to 500 characters. */
data class DecideDueRequest(
    val decision: DueDecision? = null,
    val note: String? = null,
)

/** An override of the action's colour, with its reason; until a date, or until the next update when there is none. */
data class RagOverrideRequest(
    val rag: RagColour? = null,
    val reason: String? = null,
    val until: LocalDate? = null,
)

/** A new owner (a person or a position), and why. */
data class OwnerChangeRequest(
    val owner: OwnerInput? = null,
    val reason: String? = null,
)

/** Cancelling: the kind, the reason (up to 500 characters), and the action that replaces it for SUPERSEDED. */
data class CancelActionRequest(
    val kind: CancelKind? = null,
    val reason: String? = null,
    val supersededBy: UUID? = null,
    /** RISK_ACCEPTED recorded by a secretary for the chair: how the chair told them. */
    val how: How? = null,
) {
    fun input() = CancelInput(kind, reason, supersededBy, how)
}

/** Reopening a closed or cancelled action within 90 days, with its new due date and why. */
data class ReopenActionRequest(
    val dueDate: LocalDate? = null,
    val reason: String? = null,
)

/**
 * Progress, dates, owners and endings of an issued action (ADR-0067, design E07 §1.5–§1.10, §3): updates with the
 * owner's confidence and evidence, "I have started", requests for a new date and their answers, direct date changes, the
 * colour's override, a new owner, cancelling and reopening; and the timeline. Reads go through [ActionAccess] (404 when
 * the viewer may not see the action). Every change takes If-Match on the action's version and, but clearing an override,
 * an `Idempotency-Key`; each answers the action as the viewer now sees it, with its ETag.
 */
@RestController
@Tag(name = "actions")
class ActionProgressController(
    private val progress: ActionProgress,
    private val dates: ActionDates,
    private val ownership: ActionOwnership,
    private val ending: ActionEnding,
    private val access: ActionAccess,
    private val calls: ActionCalls,
    private val changes: ActionChanges,
) {
    @GetMapping("/api/v1/actions/{actionId}/entries")
    @SignedIn
    @Operation(operationId = "getActionEntries", summary = "An action's timeline, the newest first")
    fun entries(
        @PathVariable actionId: UUID,
    ): ResponseEntity<ActionEntriesResponse> = noStore(calls.read { access.entries(actionId) })

    @PostMapping("/api/v1/actions/{actionId}/updates")
    @SignedIn
    @Operation(
        operationId = "postActionUpdate",
        summary = "Post a progress update (the owner, a contributor, or a secretary for the owner)",
    )
    fun update(
        @PathVariable actionId: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: ActionUpdateRequest,
    ): ResponseEntity<ActionResponse> =
        created(
            changes.write("actions.update", actionId, ifMatch, key, body) { version -> progress.update(actionId, version, body.input()) },
        )

    @PostMapping("/api/v1/actions/{actionId}/started")
    @SignedIn
    @Operation(operationId = "startAction", summary = "Say the work has started (the owner, or a secretary for them)")
    fun started(
        @PathVariable actionId: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody(required = false) body: ActionStartedRequest?,
    ): ResponseEntity<ActionResponse> =
        tagged(
            changes.write("actions.started", actionId, ifMatch, key, body) { version ->
                progress.started(actionId, version, OnBehalf(body?.onBehalfOf, body?.how))
            },
        )

    @PostMapping("/api/v1/actions/{actionId}/due-requests")
    @SignedIn
    @Operation(operationId = "requestActionDue", summary = "Ask for a new due date (the owner, when the committee agrees dates)")
    fun requestDue(
        @PathVariable actionId: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: ActionDueRequest,
    ): ResponseEntity<ActionResponse> =
        created(
            changes.write("actions.due.request", actionId, ifMatch, key, body) { version ->
                dates.request(actionId, version, body.newDate, body.reason)
            },
        )

    @PostMapping("/api/v1/actions/{actionId}/due-requests/{requestId}/decide")
    @SignedIn
    @Operation(operationId = "decideActionDue", summary = "Agree to or decline a request for a new date (a secretary, or the chair)")
    fun decideDue(
        @PathVariable actionId: UUID,
        @PathVariable requestId: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: DecideDueRequest,
    ): ResponseEntity<ActionResponse> =
        tagged(
            changes.write("actions.due.decide", actionId, ifMatch, key, listOf(requestId, body)) { version ->
                dates.decide(actionId, requestId, version, body.decision, body.note)
            },
        )

    @PostMapping("/api/v1/actions/{actionId}/due")
    @SignedIn
    @Operation(
        operationId = "changeActionDue",
        summary = "Change the due date directly (a secretary; the owner when the committee lets them)",
    )
    fun changeDue(
        @PathVariable actionId: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: ActionDueRequest,
    ): ResponseEntity<ActionResponse> =
        tagged(
            changes.write("actions.due.change", actionId, ifMatch, key, body) { version ->
                dates.changeDirectly(actionId, version, body.newDate, body.reason)
            },
        )

    @PostMapping("/api/v1/actions/{actionId}/rag")
    @SignedIn
    @Operation(
        operationId = "overrideActionRag",
        summary = "Override the action's red, amber or green with a reason (a secretary or the chair)",
    )
    fun overrideRag(
        @PathVariable actionId: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: RagOverrideRequest,
    ): ResponseEntity<ActionResponse> =
        tagged(
            changes.write("actions.rag", actionId, ifMatch, key, body) { version ->
                progress.override(actionId, version, body.rag, body.reason, body.until)
            },
        )

    @DeleteMapping("/api/v1/actions/{actionId}/rag")
    @SignedIn
    @Operation(operationId = "clearActionRag", summary = "Clear the override of the action's colour (a secretary or the chair)")
    fun clearRag(
        @PathVariable actionId: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
    ): ResponseEntity<ActionResponse> {
        calls.limit()
        val version = ifMatchVersion(ifMatch)
        return tagged(
            calls.write {
                progress.clear(actionId, version)
                access.action(actionId)
            },
        )
    }

    @PostMapping("/api/v1/actions/{actionId}/owner")
    @SignedIn
    @Operation(operationId = "changeActionOwner", summary = "Give the action to another owner, a person or a position (secretaries)")
    fun changeOwner(
        @PathVariable actionId: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: OwnerChangeRequest,
    ): ResponseEntity<ActionResponse> =
        tagged(
            changes.write("actions.owner", actionId, ifMatch, key, body) { version ->
                ownership.changeOwner(actionId, version, body.owner, body.reason)
            },
        )

    @PostMapping("/api/v1/actions/{actionId}/cancel")
    @SignedIn
    @Operation(operationId = "cancelAction", summary = "Cancel an issued action with a kind and a reason (a secretary or the chair)")
    fun cancel(
        @PathVariable actionId: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: CancelActionRequest,
    ): ResponseEntity<ActionResponse> =
        tagged(changes.write("actions.cancel", actionId, ifMatch, key, body) { version -> ending.cancel(actionId, version, body.input()) })

    @PostMapping("/api/v1/actions/{actionId}/reopen")
    @SignedIn
    @Operation(operationId = "reopenAction", summary = "Reopen a closed or cancelled action within 90 days (secretaries)")
    fun reopen(
        @PathVariable actionId: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: ReopenActionRequest,
    ): ResponseEntity<ActionResponse> =
        tagged(
            changes.write("actions.reopen", actionId, ifMatch, key, body) { version ->
                ending.reopen(actionId, version, body.dueDate, body.reason)
            },
        )
}

/** One change of an action as its endpoints make it (design E07 §3): If-Match, the key, the transaction, the answer. */
@Component
class ActionChanges(
    private val access: ActionAccess,
    private val calls: ActionCalls,
    private val idempotency: Idempotency,
    private val json: JsonMapper,
) {
    /**
     * One change of [actionId] at the If-Match version, once per `Idempotency-Key` (a replay answers the action as it is
     * now), in a tenant transaction under the per-person write limit; the action as the viewer sees it after.
     */
    fun write(
        operation: String,
        actionId: UUID,
        ifMatch: String?,
        key: String,
        body: Any?,
        change: (Long) -> Any,
    ): ActionResponse {
        calls.limit()
        val version = ifMatchVersion(ifMatch)
        val request = "$actionId\n$version\n${json.writeValueAsString(body)}"
        return calls.write {
            idempotency.once(operation, key, fingerprint(request)) {
                change(version)
                actionId.toString()
            }
            access.action(actionId)
        }
    }
}
