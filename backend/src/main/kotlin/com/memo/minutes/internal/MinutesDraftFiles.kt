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
import com.memo.notification.NotificationKind
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
 * Everyone else is refused by the generic files endpoints. The reviewer (T6) reads it and edits it in review mode while
 * the draft is with them (their changes are tracked changes).
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
        val meetingId = meetingOf(ownerId) ?: return false
        val state = store.find(meetingId)?.state
        return (state in EDITABLE && keeper(meetingId, held = true)) || reviewing(meetingId, held = true)
    }

    override fun mayRead(file: FileInfo): Boolean {
        val meetingId = meetingOf(file.ownerId)
        return meetingId != null && (keeper(meetingId, held = false) || reviewing(meetingId, held = false))
    }

    /** Editing in the browser follows the same rule as uploading a new version (ADR-0037). */
    override fun mayEdit(file: FileInfo): Boolean = mayUpload(file.ownerId)

    /** The reviewer edits in review mode: their changes are tracked changes the secretary accepts or rejects (ADR-0066). */
    override fun reviewOnly(file: FileInfo): Boolean {
        val meetingId = meetingOf(file.ownerId) ?: return false
        return !keeper(meetingId, held = false) && reviewing(meetingId, held = false)
    }

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

    /** The reviewer of [meetingId]'s draft while it is with them (design E06 §1.6.2), recused from none of its items. */
    private fun reviewing(
        meetingId: UUID,
        held: Boolean,
    ): Boolean {
        val user = (ActorContext.currentOrNull()?.actor as? Actor.User)?.userId
        val row = store.find(meetingId)?.takeIf { it.state == MinutesStage.CHAIR_REVIEW && it.reviewerId != null && it.reviewerId == user }
        val view = row?.let { viewers.find(it.meetingId) }?.takeIf { !held || it.meeting.status == MeetingStatus.HELD }
        return view != null && recusals.ofMeeting(meetingId).values.none { user in it }
    }

    private fun meetingOf(ownerId: String?): UUID? = ownerId?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    companion object {
        const val KEY = "minutes.draft"
        const val MAX_BYTES = 25L shl 20

        /** The stages in which the secretaries may change the draft (design E06 §1.6.1); the reviewer changes it in CHAIR_REVIEW. */
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
 * change is vetoed (409 `WRONG_STATE`): approved, or in the chair's review unless the reviewer saved it (review mode). A
 * version the draft job writes closes its request, is recorded and audited as generated, filled again or restored, and its
 * requester is told. Every version gets its structure check (a job); one saved while circulated or with the chair has the
 * readers' copies rebuilt two minutes later (later saves ask again).
 */
@Component
class MinutesDraftListener(
    private val store: MinutesStore,
    private val files: Files,
    private val jobs: MinutesJobs,
    private val copies: MinutesCopies,
    private val notifier: MinutesNotifier,
    private val ids: IdGenerator,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    @EventListener
    fun onVersion(event: FileVersionCreated) {
        if (event.purposeKey != MinutesDraftPurpose.KEY) return
        val meetingId = event.ownerId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val row = meetingId?.let(store::lock) ?: throw MinutesNotFoundException("minutes")
        val reviewer = row.state == MinutesStage.CHAIR_REVIEW && row.reviewerId != null && row.reviewerId == actorOrNull()
        refuseIf(row.state !in MinutesDraftPurpose.EDITABLE && !reviewer, "WRONG_STATE") { "The minutes cannot change at this stage." }
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
        if (row.state == MinutesStage.CIRCULATED || row.state == MinutesStage.AWAITING_CHAIR) copies.request(row, delayed = true)
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
        // The requester is told the draft is ready (in the app): the job wrote it as them.
        row.workRequestedBy?.let { notifier.notify(listOf(it), NotificationKind.MINUTES_DRAFT_READY, row.meetingId, except = null) }
    }
}
