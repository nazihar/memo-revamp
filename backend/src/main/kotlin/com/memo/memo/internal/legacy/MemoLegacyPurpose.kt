package com.memo.memo.internal.legacy

import com.memo.common.authz.Policy
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.files.FileInfo
import com.memo.files.FilePurpose
import com.memo.files.FileType
import com.memo.files.RenditionKind
import com.memo.files.StorageClass
import com.memo.memo.LegacyMemos
import com.memo.memo.internal.MemoStore
import com.memo.memo.internal.MemoTypes
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * The originals of imported memos (ADR-0055, design E06 §10): records, the attachment types plus TIFF and the legacy
 * Office formats (OLE2 `.doc`, `.xls`, `.ppt` typed by the Tika server, and RTF), up to 100 MiB, every open audited, a PDF
 * and text built for each version where a converter can (TIFF has neither: download only). Only the import job stores
 * them, with the memo's reserved id as owner; a reader needs the memo's read rule and a clearance at least the file's
 * effective level. Retention follows the memo's type when it sets one, else the tenant's default, counted from the
 * import (never shorter than counting from the legacy dates).
 */
@Component
class MemoLegacyPurpose(
    private val memos: MemoStore,
    private val types: MemoTypes,
    private val reader: LegacyMemoReader,
    private val policy: Policy,
) : FilePurpose {
    override val key = KEY
    override val allowedTypes =
        setOf(
            FileType.PDF,
            FileType.PNG,
            FileType.JPEG,
            FileType.WEBP,
            FileType.TIFF,
            FileType.TEXT,
            FileType.ZIP,
            FileType.DOCX,
            FileType.XLSX,
            FileType.PPTX,
            FileType.ODT,
            FileType.ODS,
            FileType.ODP,
            FileType.DOC,
            FileType.XLS,
            FileType.PPT,
            FileType.RTF,
        )
    override val maxBytes = MAX_BYTES
    override val storage = StorageClass.RECORD
    override val auditReads = true
    override val inlineTypes = setOf(FileType.PDF, FileType.PNG, FileType.JPEG, FileType.WEBP)
    override val renditions = setOf(RenditionKind.PDF, RenditionKind.TEXT)
    override val acceptsLegacyFormats = true

    override fun mayUpload(ownerId: String?): Boolean = (ActorContext.current().actor as? Actor.System)?.name == JOB

    override fun mayRead(file: FileInfo): Boolean {
        val memoId = file.ownerId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val user = (ActorContext.currentOrNull()?.actor as? Actor.User)?.userId
        if (memoId == null || user == null) return false
        val clearance = policy.clearanceRank()
        // The memo's read rule first, then the original must be this memo's and within the reader's clearance.
        return memos.visible(memoId, user, clearance)?.let { memo ->
            reader.original(file.id)?.let { it.memoId == memo.id && it.effectiveRank(memo.classificationRank) <= clearance }
        } == true
    }

    /** The memo type's years when the memo exists (whatever they count from); else the tenant's default. */
    override fun retentionYears(ownerId: String?): Int? {
        val memo = ownerId?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let(memos::find) ?: return null
        return types
            .versionFor(memo)
            ?.settings
            ?.retention
            ?.years
    }

    companion object {
        const val KEY = LegacyMemos.ORIGINAL_PURPOSE

        /** The import job's system name (design E06 §12): the only actor that stores originals. */
        const val JOB = "legacy-import"
        const val MAX_BYTES = 100L shl 20
    }
}
