package com.memo.minutes.internal

import com.memo.memo.FixityRecord
import com.memo.memo.RecordFixitySource
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * The approved minutes' records in the memo module's weekly fixity run (ADR-0053, ADR-0066, design E06 §9): every made
 * version, re-hashed against the SHA-256 recorded when it was stored; a mismatch is audited `minutes.record.mismatch` on
 * the minutes, each run `minutes.record.verified` with its counts.
 */
@Component
class MinutesRecordFixity(
    private val records: MinutesRecordStore,
) : RecordFixitySource {
    override val verifiedAction = "minutes.record.verified"
    override val mismatchAction = "minutes.record.mismatch"
    override val objectType = MinutesDrafts.OBJECT_TYPE

    override fun page(
        after: UUID?,
        limit: Int,
    ): List<FixityRecord> =
        records.readyAfter(after, limit).map { r ->
            val version = checkNotNull(r.fileVersionId)
            FixityRecord(
                id = r.id,
                fileVersionId = version,
                sha256 = checkNotNull(r.sha256),
                objectType = MinutesDrafts.OBJECT_TYPE,
                objectId = r.meetingId.toString(),
                details = mapOf("recordVersion" to r.recordVersion, "fileVersionId" to version.toString()),
            )
        }
}

/**
 * The readers' approved copies in the weekly fixity run (each a record of its own, `minutes.approved-copy`): audited
 * `minutes.copy.mismatch` and `minutes.copy.verified`. The draft's copies are not records and are not checked.
 */
@Component
class ApprovedCopyFixity(
    private val copies: MinutesCopyStore,
) : RecordFixitySource {
    override val verifiedAction = "minutes.copy.verified"
    override val mismatchAction = "minutes.copy.mismatch"
    override val objectType = MinutesDrafts.OBJECT_TYPE

    override fun page(
        after: UUID?,
        limit: Int,
    ): List<FixityRecord> =
        copies.approvedAfter(after, limit).map { c ->
            FixityRecord(
                id = c.id,
                fileVersionId = checkNotNull(c.fileVersionId),
                sha256 = checkNotNull(c.sha256),
                objectType = MinutesDrafts.OBJECT_TYPE,
                objectId = c.meetingId.toString(),
                details = mapOf("copyId" to c.id.toString(), "recordVersion" to c.recordVersion, "setKey" to c.setKey),
            )
        }
}
