package com.memo.actions.internal

import com.memo.common.authz.SignedIn
import com.memo.common.web.FieldProblem
import com.memo.system.Idempotency
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Marking an action as done: what was done (1 to 2,000 characters) and the evidence. */
data class ActionDoneRequest(
    val note: String? = null,
    /** `action.evidence` files uploaded for this action by the holder, up to 10; required when the committee says so. */
    val fileIds: List<UUID> = emptyList(),
)

/** The verifier's decision, with a remark: optional for closing, required for sending back (up to 2,000 characters). */
data class VerifyActionRequest(
    val decision: VerifyDecision? = null,
    val remark: String? = null,
)

/** The actions the committee agreed to close at the meeting, and the remark they get ("Agreed at BRC 4/2026, item 3" when left out). */
data class CloseAgreedRequest(
    val actionIds: List<UUID> = emptyList(),
    val remark: String? = null,
)

/** How closing one action of a bulk request went. */
data class CloseResult(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val actionId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val closed: Boolean,
    val reference: String? = null,
    /** Why not: a problem code (`NOT_VERIFIER`, `WRONG_STATE`, `CLAIMED`, `COMMITTEE`, `NOT_FOUND`, …). */
    val reason: String? = null,
)

/** `POST /meetings/{id}/actions/close-agreed`: one result per action, each closed in its own transaction. */
data class CloseResultsResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val results: List<CloseResult>,
)

/**
 * Closure verified by someone else (ADR-0067, design E07 §1.9, §3): the holder marks the action as done with a note and
 * evidence; a verifier of the pool closes it or sends it back; at a meeting, the verifier closes the actions the committee
 * agreed in one request (one transaction per action, with per-action results replayed by the key). Reads go through
 * [ActionAccess] (404 when the viewer may not see the action); each change takes If-Match on the action's version and an
 * `Idempotency-Key`.
 */
@RestController
@Tag(name = "actions")
class ActionClosureController(
    private val closure: ActionClosure,
    private val changes: ActionChanges,
    private val calls: ActionCalls,
    private val idempotency: Idempotency,
) {
    @PostMapping("/api/v1/actions/{actionId}/done")
    @SignedIn
    @Operation(operationId = "markActionDone", summary = "Mark the action as done, with what was done and the evidence (the holder)")
    fun done(
        @PathVariable actionId: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: ActionDoneRequest,
    ): ResponseEntity<ActionResponse> =
        tagged(
            changes.write(
                "actions.done",
                actionId,
                ifMatch,
                key,
                body,
            ) { version -> closure.done(actionId, version, body.note, body.fileIds) },
        )

    @PostMapping("/api/v1/actions/{actionId}/verify")
    @SignedIn
    @Operation(operationId = "verifyAction", summary = "Close the action, or send it back to the owner with a remark (the verifier)")
    fun verify(
        @PathVariable actionId: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: VerifyActionRequest,
    ): ResponseEntity<ActionResponse> =
        tagged(
            changes.write("actions.verify", actionId, ifMatch, key, body) { version ->
                closure.verify(actionId, version, body.decision, body.remark)
            },
        )

    @PostMapping("/api/v1/meetings/{meetingId}/actions/close-agreed")
    @SignedIn
    @Operation(operationId = "closeAgreedActions", summary = "Close the actions the committee agreed at the meeting (the verifier)")
    fun closeAgreed(
        @PathVariable meetingId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: CloseAgreedRequest,
    ): ResponseEntity<CloseResultsResponse> {
        calls.limit()
        val ids = body.actionIds.distinct()
        requireNoProblems(
            listOfNotNull(
                FieldProblem("actionIds", "REQUIRED", "Choose the actions the committee agreed to close.").takeIf { ids.isEmpty() },
                FieldProblem("actionIds", "TOO_MANY", "At most ${ActionClosure.CLOSE_AGREED_MAX} actions at once.")
                    .takeIf { ids.size > ActionClosure.CLOSE_AGREED_MAX },
            ),
        )
        val (meeting, remark) = calls.read { closure.agreedAt(meetingId, body.remark) }
        // One transaction per action: a refusal stops nothing else, and a replay of the key replays each one's result.
        val results =
            ids.map { id ->
                runCatching {
                    calls.write {
                        idempotency.once("actions.close-agreed.$id", key, fingerprint("$meetingId\n$id\n$remark")) {
                            closure.verify(id, null, VerifyDecision.CLOSE, remark, agreedAt = meeting).reference.orEmpty()
                        }
                    }
                }.fold(
                    onSuccess = { CloseResult(id, closed = true, reference = it) },
                    onFailure = { CloseResult(id, closed = false, reason = reason(it)) },
                )
            }
        return noStore(CloseResultsResponse(results))
    }
}
