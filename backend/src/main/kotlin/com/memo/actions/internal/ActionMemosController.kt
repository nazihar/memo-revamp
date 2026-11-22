package com.memo.actions.internal

import com.memo.common.authz.SignedIn
import com.memo.memo.MemoDrafts
import com.memo.system.Idempotency
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.util.UUID

/** Raising a follow-up memo from an action (flow AP). */
data class RaiseMemoRequest(
    /** A memo type the person may raise. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val typeId: UUID,
    /** The memo's subject; the action's title when left out. */
    val subject: String? = null,
    /** The memo's level; the action's when left out. A lower one needs [confirmLowerLevel]. */
    val classificationId: UUID? = null,
    /** The person confirmed a level lower than the action's (422 `LOWER_THAN_ACTION` without it). */
    val confirmLowerLevel: Boolean = false,
)

/** Linking an existing memo the person may read to an action as its follow-up. */
data class LinkMemoRequest(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val memoId: UUID,
)

/** The draft memo raised from an action, and the action with it. */
data class RaisedMemoResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val memoId: UUID,
    /** Whether the memo's document was started from its type's template (ADR-0048); otherwise the initiator adds it. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val templateFilled: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val action: ActionResponse,
)

/**
 * Follow-up memos of an action (ADR-0067 D1, design E07 §1.11, §3): its owner or a contributor with `memo.create` raises
 * a draft memo from it, which goes on the memo's own route; its owner, a contributor or a secretary links a memo they may
 * read. 404 for an action the person may not see, 403 for one they see but may not do this on, 409 `WRONG_STATE` once the
 * work is done, 422 for the memo's own refusals. Both need an `Idempotency-Key` and share the agenda's per-person limit.
 */
@RestController
@Tag(name = "actions")
class ActionMemosController(
    private val followUps: ActionFollowUps,
    private val drafts: MemoDrafts,
    private val access: ActionAccess,
    private val calls: ActionCalls,
    private val idempotency: Idempotency,
    private val json: JsonMapper,
) {
    @PostMapping("/api/v1/actions/{actionId}/memos")
    @SignedIn
    @Operation(operationId = "raiseActionMemo", summary = "Raise a draft follow-up memo from an action (its owner and contributors)")
    fun raise(
        @PathVariable actionId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: RaiseMemoRequest,
    ): ResponseEntity<RaisedMemoResponse> {
        calls.limit()
        val request = "$actionId\n${json.writeValueAsString(body)}"
        val memoId =
            calls
                .write {
                    idempotency.once("actions.memo.raise", key, fingerprint(request)) {
                        followUps
                            .raise(actionId, RaiseMemoInput(body.typeId, body.subject, body.classificationId, body.confirmLowerLevel))
                            .toString()
                    }
                }.let(UUID::fromString)
        // ADR-0048, as POST /memos does: the type's template fills the document once the memo is committed.
        val filled = drafts.fillTemplate(memoId)
        val action = calls.read { access.action(actionId) }
        return ResponseEntity
            .created(URI.create("/api/v1/memos/$memoId"))
            .cacheControl(CacheControl.noStore())
            .body(RaisedMemoResponse(memoId, filled, action))
    }

    @PostMapping("/api/v1/actions/{actionId}/memos/link")
    @SignedIn
    @Operation(operationId = "linkActionMemo", summary = "Link a memo the actor may read to an action as its follow-up")
    fun link(
        @PathVariable actionId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: LinkMemoRequest,
    ): ResponseEntity<ActionResponse> {
        calls.limit()
        return tagged(
            calls.write {
                idempotency.once("actions.memo.link", key, fingerprint("$actionId\n${body.memoId}")) {
                    followUps.link(actionId, body.memoId).id.toString()
                }
                access.action(actionId)
            },
        )
    }
}
