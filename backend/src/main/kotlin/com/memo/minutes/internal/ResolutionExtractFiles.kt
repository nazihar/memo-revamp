package com.memo.minutes.internal

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.files.FileInfo
import com.memo.files.FilePurpose
import com.memo.files.FileType
import com.memo.files.StorageClass
import com.memo.memo.FixityRecord
import com.memo.memo.RecordFixitySource
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * A certified resolution extract (design E06 §1.6.6): one PDF/A-2u file per extract in record storage (object-locked,
 * encrypted per tenant) for the committee type's retention, counted from when it is stored. Stored by the
 * `minutes-extract` job alone (owner: the meeting) and never served by the generic files endpoints: the extract's content
 * endpoint streams it to its readers, auditing each open.
 */
@Component
class MinutesExtractPurpose(
    private val retention: MinutesRetention,
) : FilePurpose {
    override val key = KEY
    override val allowedTypes = setOf(FileType.PDF)
    override val maxBytes = MAX_BYTES
    override val maxPdfPages = MAX_PAGES
    override val storage = StorageClass.RECORD
    override val auditReads = true

    override fun mayUpload(ownerId: String?): Boolean = (ActorContext.currentOrNull()?.actor as? Actor.System)?.name == JOB

    override fun mayRead(file: FileInfo): Boolean = false

    override fun retentionYears(ownerId: String?): Int? = retention.years(ownerId)

    companion object {
        const val KEY = "minutes.extract"

        /** The extract job's system name: the only actor that stores extracts. */
        const val JOB = "minutes-extract"
        const val MAX_BYTES = 64L shl 20
        const val MAX_PAGES = 20
    }
}

/**
 * The certified extracts in the memo module's weekly fixity run (ADR-0053, design E06 §9): each made extract re-hashed
 * against the SHA-256 recorded when it was stored; a mismatch is audited `minutes.extract.mismatch` on the meeting, each
 * run `minutes.extract.verified` with its counts.
 */
@Component
class ExtractFixity(
    private val extracts: MinutesExtractStore,
) : RecordFixitySource {
    override val verifiedAction = "minutes.extract.verified"
    override val mismatchAction = "minutes.extract.mismatch"
    override val objectType = DecisionEffects.OBJECT_TYPE

    override fun page(
        after: UUID?,
        limit: Int,
    ): List<FixityRecord> =
        extracts.readyAfter(after, limit).map { e ->
            FixityRecord(
                id = e.id,
                fileVersionId = checkNotNull(e.fileVersionId),
                sha256 = checkNotNull(e.sha256),
                objectType = DecisionEffects.OBJECT_TYPE,
                objectId = e.meetingId.toString(),
                details = mapOf("extractId" to e.id.toString(), "resolutionId" to e.resolutionId.toString()),
            )
        }
}
