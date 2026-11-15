package com.memo.memo.internal.rendition

import com.memo.common.authz.Policy
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.files.FileInfo
import com.memo.files.FilePurpose
import com.memo.files.FileType
import com.memo.files.StorageClass
import com.memo.memo.internal.MemoStore
import com.memo.memo.internal.MemoTypes
import com.memo.memo.internal.RetentionFrom
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Final records of memos (ADR-0053): PDF only, record storage (object-locked, encrypted per tenant), every open audited,
 * served as attachments. Only the rendition job stores them; a reader needs the memo's read rule and a clearance at least
 * the record's classification, checked at download time, not when the record was made (threat model I1, I2). Retention
 * follows the memo type when it counts from approval; otherwise the tenant's default, extended at closure.
 */
@Component
class MemoRenditionPurpose(
    private val memos: MemoStore,
    private val types: MemoTypes,
    private val records: MemoRenditionQueries,
    private val policy: Policy,
) : FilePurpose {
    override val key = KEY
    override val allowedTypes = setOf(FileType.PDF)
    override val maxBytes = MAX_BYTES
    override val maxPdfPages = MAX_PAGES
    override val storage = StorageClass.RECORD
    override val auditReads = true

    override fun mayUpload(ownerId: String?): Boolean = (ActorContext.current().actor as? Actor.System)?.name == JOB

    override fun mayRead(file: FileInfo): Boolean {
        val memoId = file.ownerId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val user = (ActorContext.current().actor as? Actor.User)?.userId
        val rank = records.rankOfFile(file.id)
        return memoId != null && user != null && rank != null &&
            policy.clearanceRank() >= rank && memos.visible(memoId, user, policy.clearanceRank()) != null
    }

    override fun retentionYears(ownerId: String?): Int? {
        val memo = ownerId?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let(memos::find) ?: return null
        val retention = types.versionFor(memo)?.settings?.retention
        return retention?.takeIf { it.from == RetentionFrom.APPROVAL }?.years
    }

    companion object {
        const val KEY = "memo.rendition"

        /** The rendition job's system name: the only actor that stores records. */
        const val JOB = "memo-final-rendition"
        const val MAX_BYTES = 1L shl 30
        const val MAX_PAGES = 5_000
    }
}
