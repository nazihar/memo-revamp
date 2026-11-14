package com.memo.memo.internal

import com.memo.common.authz.SignedIn
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.etagOf
import com.memo.system.Idempotency
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** A link to make: the other memo by its number, and how this one relates to it. */
data class LinkRequest(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val targetReference: String,
    val kind: LinkKind = LinkKind.REFERS_TO,
)

/**
 * Closing an approved memo and linking memos (design E01 §1, §3). Actions take an `Idempotency-Key` bound to the actor;
 * the answer is the memo as it is now. Memos the actor may not see answer 404.
 */
@RestController
@RequestMapping("/api/v1/memos/{id}")
@Tag(name = "memo")
class MemoLifecycleController(
    private val memos: Memos,
    private val closing: MemoClosing,
    private val links: MemoLinks,
    private val views: MemoViews,
    private val idempotency: Idempotency,
    private val transactions: TenantTransactions,
    private val json: JsonMapper,
) {
    @PostMapping("/close")
    @SignedIn
    @Operation(operationId = "closeMemo", summary = "Close an approved memo for good")
    fun close(
        @PathVariable id: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody(required = false) body: RemarkRequest?,
    ): ResponseEntity<MemoView> = act(key, "memo.close", id, body) { closing.close(id, body?.remark) }

    @PostMapping("/links")
    @SignedIn
    @Operation(operationId = "linkMemo", summary = "Link the memo to another one by its number")
    fun link(
        @PathVariable id: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: LinkRequest,
    ): ResponseEntity<MemoView> =
        act(key, "memo.link", id, body) {
            links.add(id, body.targetReference, body.kind)
            memos.get(id)
        }

    @DeleteMapping("/links/{linkId}")
    @SignedIn
    @Operation(operationId = "unlinkMemo", summary = "Remove a link you made while the memo is a draft or returned")
    fun unlink(
        @PathVariable id: UUID,
        @PathVariable linkId: UUID,
    ): MemoView =
        transactions.inTenant(TenantContext.current()) {
            links.remove(id, linkId)
            views.of(memos.get(id))
        }

    private fun act(
        key: String,
        operation: String,
        id: UUID,
        body: Any?,
        action: () -> Memo,
    ): ResponseEntity<MemoView> {
        val view =
            transactions.inTenant(TenantContext.current()) {
                idempotency.once(operation, key, fingerprint("$id\n${json.writeValueAsString(body)}")) { action().id.toString() }
                views.of(memos.get(id))
            }
        return ResponseEntity.ok().eTag(etagOf(view.version)).body(view)
    }
}
