package com.memo.memo.internal

import com.memo.common.authz.SignedIn
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.ifMatchVersion
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** A change to an attachment: a new title, a stricter level (or `sameAsMemo`), a position. */
data class AttachmentChangeRequest(
    val title: String? = null,
    val classificationId: UUID? = null,
    /** Back to the memo's own level. */
    val sameAsMemo: Boolean = false,
    val position: Int? = null,
)

/**
 * Attachments of a memo (ADR-0046). They are added by uploading a file of purpose `memo.attachment` with the memo as
 * owner (the files API); here they are listed, changed and removed, and each round's binding is read. Memos the actor
 * may not see answer 404; changes need the memo's initiator while it is editable.
 */
@RestController
@RequestMapping("/api/v1/memos/{id}")
@Tag(name = "memo")
class MemoAttachmentsController(
    private val attachments: MemoAttachments,
    private val transactions: TenantTransactions,
) {
    @GetMapping("/attachments")
    @SignedIn
    @Operation(operationId = "listMemoAttachments", summary = "The memo's attachments, in order; restricted ones without details")
    fun list(
        @PathVariable id: UUID,
    ): List<AttachmentView> = transactions.readInTenant(TenantContext.current()) { attachments.list(id) }

    @PatchMapping("/attachments/{attachmentId}")
    @SignedIn
    @Operation(operationId = "changeMemoAttachment", summary = "Retitle, reclassify (stricter only) or move an attachment")
    fun change(
        @PathVariable id: UUID,
        @PathVariable attachmentId: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: AttachmentChangeRequest,
    ): AttachmentView {
        val version = ifMatchVersion(ifMatch)
        return transactions.inTenant(TenantContext.current()) {
            attachments.change(
                id,
                attachmentId,
                version,
                AttachmentChange(body.title, body.classificationId, body.sameAsMemo, body.position),
            )
        }
    }

    @DeleteMapping("/attachments/{attachmentId}")
    @SignedIn
    @Operation(operationId = "removeMemoAttachment", summary = "Remove an attachment while the memo is a draft or returned")
    fun remove(
        @PathVariable id: UUID,
        @PathVariable attachmentId: UUID,
    ): ResponseEntity<Void> {
        transactions.inTenant(TenantContext.current()) { attachments.remove(id, attachmentId) }
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/rounds/{round}/attachments")
    @SignedIn
    @Operation(operationId = "listMemoRoundAttachments", summary = "The attachments a submission bound, with their versions")
    fun round(
        @PathVariable id: UUID,
        @PathVariable round: Int,
    ): List<AttachmentView> = transactions.readInTenant(TenantContext.current()) { attachments.round(id, round) }
}
