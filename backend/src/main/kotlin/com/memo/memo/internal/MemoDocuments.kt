package com.memo.memo.internal

import com.memo.common.authz.Policy
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.web.refuseIf
import com.memo.files.FileInfo
import com.memo.files.FilePurpose
import com.memo.files.FileType
import com.memo.files.FileVersionCreated
import com.memo.files.StorageClass
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * The memo's document (ADR-0036): one DOCX file per memo, kept as a record (object-locked versions), every read audited.
 * Only the initiator uploads, and only while they hold the memo (draft or returned), which is also the review lock:
 * during review the files API refuses new versions (403). Whoever may see the memo may read every version.
 */
@Component
class MemoDocumentPurpose(
    private val store: MemoStore,
    private val policy: Policy,
) : FilePurpose {
    override val key = KEY
    override val allowedTypes = setOf(FileType.DOCX)
    override val maxBytes = MAX_BYTES
    override val storage = StorageClass.RECORD
    override val auditReads = true

    override fun mayUpload(ownerId: String?): Boolean {
        val memo = memoOf(ownerId) ?: return false
        return memo.initiatorId == currentUser() && memo.status.editable
    }

    override fun mayRead(file: FileInfo): Boolean = memoOf(file.ownerId) != null

    /** The owner memo if the actor may see it. */
    private fun memoOf(ownerId: String?): Memo? {
        val id = ownerId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val user = currentUser()
        return if (id == null || user == null) null else store.visible(id, user, policy.clearanceRank())
    }

    private fun currentUser(): UUID? = (ActorContext.currentOrNull()?.actor as? Actor.User)?.userId

    companion object {
        const val KEY = "memo.document"
        const val MAX_BYTES = 25L shl 20
    }
}

/**
 * Records a memo's document when its first version is stored, in the upload's transaction. Checks again under the memo's
 * row lock, because the memo may have been submitted since the upload was allowed: then, or when the memo already has
 * another document file, the upload is vetoed and rolls back (409).
 */
@Component
class MemoDocumentListener(
    private val store: MemoStore,
    private val clock: Clock,
) {
    @EventListener
    fun onVersion(event: FileVersionCreated) {
        if (event.purposeKey != MemoDocumentPurpose.KEY) return
        val memo = event.ownerId?.let { store.lock(UUID.fromString(it)) } ?: throw MemoNotFoundException()
        refuseIf(!memo.status.editable, "WRONG_STATE") { "The document can change only while the memo is a draft or returned to you." }
        refuseIf(memo.documentFileId != null && memo.documentFileId != event.fileId, "DOCUMENT_EXISTS") {
            "This memo has a document already: upload a new version of it."
        }
        if (memo.documentFileId ==
            null
        ) {
            store.setDocument(memo.id, event.fileId, OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC))
        }
    }
}
