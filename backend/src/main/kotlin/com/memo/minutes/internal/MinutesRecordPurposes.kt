package com.memo.minutes.internal

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.files.FileInfo
import com.memo.files.FilePurpose
import com.memo.files.FileType
import com.memo.files.StorageClass
import com.memo.forum.ForumDirectory
import com.memo.forum.ForumPolicies
import com.memo.meeting.Meetings
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * How long the approved minutes' record and copies of a meeting are locked (ADR-0066, ADR-0056): the committee type's
 * `retention` years on the version the meeting pinned, counted from when each version is stored (after the meeting, so
 * never shorter than from its date); the tenant's default when the type sets none. Inside the upload's tenant transaction.
 */
@Component
class MinutesRetention(
    private val meetings: Meetings,
    private val directory: ForumDirectory,
    private val policies: ForumPolicies,
) {
    fun years(ownerId: String?): Int? {
        val meeting = ownerId?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let(meetings::find)
        val forum = meeting?.let { directory.forum(it.forumId) }
        return if (meeting == null || forum == null) null else policies.version(forum.typeId, meeting.typeVersion)?.retention?.years
    }
}

/**
 * The approved minutes' record (ADR-0066): one PDF/A-2u file per meeting, a version per record version, in record storage
 * (object-locked, encrypted per tenant) for the committee type's retention. Stored by the `minutes-record` job alone and
 * never served by the generic files endpoints: the minutes' record endpoint streams it to a reader whose redaction set is
 * empty, auditing the open.
 */
@Component
class MinutesRecordPurpose(
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
        const val KEY = "minutes.record"

        /** The record job's system name: the only actor that stores records and approved copies. */
        const val JOB = "minutes-record"
        const val MAX_BYTES = 1L shl 30
        const val MAX_PAGES = 5_000
    }
}

/**
 * A reader's copy of the approved minutes (ADR-0066): a PDF/A record of its own, one per redaction set and record version,
 * naming the items redacted on its cover; record storage, locked like the record. Stored by the record's job alone (the
 * draft's copies are `minutes.copy`, ordinary storage) and served only by the minutes' record endpoint to the reader whose
 * set it is.
 */
@Component
class ApprovedCopyPurpose(
    private val retention: MinutesRetention,
) : FilePurpose {
    override val key = KEY
    override val allowedTypes = setOf(FileType.PDF)
    override val maxBytes = MinutesRecordPurpose.MAX_BYTES
    override val maxPdfPages = MinutesRecordPurpose.MAX_PAGES
    override val storage = StorageClass.RECORD
    override val auditReads = true

    override fun mayUpload(ownerId: String?): Boolean =
        (ActorContext.currentOrNull()?.actor as? Actor.System)?.name == MinutesRecordPurpose.JOB

    override fun mayRead(file: FileInfo): Boolean = false

    override fun retentionYears(ownerId: String?): Int? = retention.years(ownerId)

    companion object {
        const val KEY = "minutes.approved-copy"
    }
}
