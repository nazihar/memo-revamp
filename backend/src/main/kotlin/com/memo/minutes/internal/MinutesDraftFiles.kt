package com.memo.minutes.internal

import com.memo.audit.AuditLog
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.id.IdGenerator
import com.memo.common.web.refuseIf
import com.memo.docedit.EditableFilePurpose
import com.memo.files.FileInfo
import com.memo.files.FileType
import com.memo.files.FileVersionCreated
import com.memo.files.Files
import com.memo.files.StorageClass
import com.memo.meeting.MeetingStatus
import com.memo.meeting.MeetingViewers
import com.memo.meeting.Recusals
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The minutes' draft (ADR-0066, design E06 §1.6.1, §6): one .docx per meeting, owned by the meeting's id, encrypted in
 * ordinary storage, every read audited. Read, uploaded and edited in ONLYOFFICE (co-editing, ADR-0047) by the committee's
 * secretaries in office and `forums.manage` holders who are not recused from any minuted item (a recused secretary works
 * through another one), while the meeting is held and the minutes are a draft, circulated or with the chair for approval.
 * Everyone else is refused by the generic files endpoints. T6 adds the reviewer's review mode.
 */
@Component
class MinutesDraftPurpose(
    private val viewers: MeetingViewers,
    private val store: MinutesStore,
    private val recusals: Recusals,
) : EditableFilePurpose {
    override val key = KEY
    override val allowedTypes = setOf(FileType.DOCX)
    override val maxBytes = MAX_BYTES
    override val storage = StorageClass.ORDINARY
    override val auditReads = true

    override fun mayUpload(ownerId: String?): Boolean {
        val meetingId = meetingOf(ownerId)
        return meetingId != null && store.find(meetingId)?.state in EDITABLE && keeper(meetingId, held = true)
    }

    override fun mayRead(file: FileInfo): Boolean {
        val meetingId = meetingOf(file.ownerId)
        return meetingId != null && keeper(meetingId, held = false)
    }

    /** Editing in the browser follows the same rule as uploading a new version (ADR-0037). */
    override fun mayEdit(file: FileInfo): Boolean = mayUpload(file.ownerId)

    /** A secretary or `forums.manage` holder who sees [meetingId], recused from none of its items; [held]: and it is held. */
    private fun keeper(
        meetingId: UUID,
        held: Boolean,
    ): Boolean {
        val user = (ActorContext.currentOrNull()?.actor as? Actor.User)?.userId
        val view =
            user?.let { viewers.find(meetingId) }?.takeIf { it.manages && (!held || it.meeting.status == MeetingStatus.HELD) }
        return view != null && recusals.ofMeeting(meetingId).values.none { view.userId in it }
    }

    private fun meetingOf(ownerId: String?): UUID? = ownerId?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    companion object {
        const val KEY = "minutes.draft"
        const val MAX_BYTES = 25L shl 20

        /** The stages in which the draft may change (design E06 §1.6.1); T6 moves the minutes between them. */
        val EDITABLE = setOf(MinutesStage.DRAFT, MinutesStage.CIRCULATED, MinutesStage.AWAITING_CHAIR)
    }
}

/** What the draft job is writing on this thread, so the version's listener records it as such (design E06 §1.6.1). */
data class DraftWriting(
    val meetingId: UUID,
    val seq: Int,
    val kind: MinutesEventKind,
    val itemId: UUID?,
) {
    companion object {
        private val current = ThreadLocal<DraftWriting>()

        fun current(): DraftWriting? = current.get()

        fun <T> with(
            writing: DraftWriting,
            block: () -> T,
        ): T {
            val before = current.get()
            current.set(writing)
            try {
                return block()
            } finally {
                if (before == null) current.remove() else current.set(before)
            }
        }
    }
}

/**
 * Each version of a minutes draft, in the upload's transaction under the minutes' row lock: the first one becomes the
 * minutes' file (a second file for the meeting is vetoed, 409 `DRAFT_EXISTS`); a version stored while the minutes may not
 * change (in the chair's review, approved) is vetoed (409 `WRONG_STATE`). A version the draft job writes closes its request
 * and is recorded and audited as generated, filled again or restored. Every version gets its structure check (a job).
 */
@Component
class MinutesDraftListener(
    private val store: MinutesStore,
    private val files: Files,
    private val jobs: MinutesJobs,
    private val ids: IdGenerator,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    @EventListener
    fun onVersion(event: FileVersionCreated) {
        if (event.purposeKey != MinutesDraftPurpose.KEY) return
        val meetingId = event.ownerId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val row = meetingId?.let(store::lock) ?: throw MinutesNotFoundException("minutes")
        refuseIf(row.state !in MinutesDraftPurpose.EDITABLE, "WRONG_STATE") { "The minutes cannot change at this stage." }
        refuseIf(row.fileId != null && row.fileId != event.fileId, "DRAFT_EXISTS") {
            "These minutes have a draft already: save a new version of it."
        }
        val writing = DraftWriting.current()?.takeIf { it.meetingId == row.meetingId && it.seq == row.workSeq }
        val after =
            if (writing == null) {
                row.copy(fileId = event.fileId)
            } else {
                row.copy(fileId = event.fileId, workState = null, workError = null)
            }
        if (after != row) check(store.update(row, after)) { "the locked minutes moved on" }
        if (writing != null) generated(row, writing, event.fileId, event.versionId)
        jobs.check(row.meetingId, event.versionId)
    }

    private fun generated(
        row: MinutesRow,
        writing: DraftWriting,
        fileId: UUID,
        versionId: UUID,
    ) {
        val sha256 = files.version(versionId)?.sha256
        store.insertEvent(
            MinutesEventRow(
                id = ids.next(),
                meetingId = row.meetingId,
                kind = writing.kind,
                itemId = writing.itemId,
                actor = actorOrNull(),
                fileVersionId = versionId,
                sha256 = sha256,
                at = clock.instant().truncatedTo(ChronoUnit.MICROS),
            ),
        )
        val action =
            when (writing.kind) {
                MinutesEventKind.ITEM_REGENERATED -> "minutes.draft.item_regenerated"
                MinutesEventKind.ITEM_RESTORED -> "minutes.draft.item_restored"
                else -> "minutes.draft.generated"
            }
        audit.record(
            action,
            MinutesDrafts.OBJECT_TYPE,
            row.meetingId.toString(),
            after =
                mapOf(
                    "seq" to writing.seq,
                    "itemId" to writing.itemId?.toString(),
                    "fileId" to fileId.toString(),
                    "versionId" to versionId.toString(),
                    "sha256" to sha256,
                ),
        )
    }
}
