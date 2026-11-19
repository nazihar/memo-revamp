package com.memo.meeting.internal

import com.memo.common.authz.ForbiddenException
import com.memo.common.authz.Permission
import com.memo.common.id.IdGenerator
import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import com.memo.files.FileInfo
import com.memo.files.FilePurpose
import com.memo.files.FileType
import com.memo.files.FileVersionCreated
import com.memo.files.Files
import com.memo.files.RenditionKind
import com.memo.files.StorageClass
import com.memo.forum.AgendaLimits
import com.memo.meeting.PaperAccess
import com.memo.memo.MemoPaperFile
import com.memo.memo.MemoPapers
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.util.UUID

/** A paper put on an item: a file of the item's memo (back into the pack), or a `meeting.paper` upload of this meeting. */
data class PaperInput(
    val fileId: UUID,
    /** Null: the memo file's title, or the upload's file name. */
    val title: String? = null,
    val restrictedTo: List<UUID>? = null,
    /** Null: the item's level (a memo file's own when higher). */
    val classificationId: UUID? = null,
)

/** A paper's editable fields, as a whole. */
data class PaperChange(
    val title: String?,
    val restrictedTo: List<UUID>? = null,
    /** Null: keep the paper's level. */
    val classificationId: UUID? = null,
)

/**
 * Papers of the draft (ADR-0061, design E03 §1.8): the memo's pack, which the secretary can trim and restore, and
 * secretary uploads (`meeting.paper`), at most [AgendaRules.MAX_PAPERS] per item. An upload placed after the paper
 * deadline follows the late mode; a memo paper rides with its item. A paper is never below its item's level.
 */
@Service
class AgendaPapers(
    private val guard: AgendaGuard,
    private val writes: AgendaWrites,
    private val papers: PaperStore,
    private val files: Files,
    private val memoPapers: MemoPapers,
    private val people: AgendaPeople,
    private val lateness: AgendaLateness,
    private val placing: AgendaPlacing,
    private val ids: IdGenerator,
) {
    fun add(
        meetingId: UUID,
        itemId: UUID,
        version: Long,
        input: PaperInput,
    ): UUID {
        val edit = guard.edit(meetingId, version, tabled = true)
        val item = edit.item(itemId)
        refuseIf(item.kind == ItemKind.BREAK, "WRONG_KIND") { "A break has no papers." }
        val present = edit.rows.papers.filter { it.itemId == item.id }
        refuseIf(present.any { it.fileId == input.fileId }, "ALREADY_IN_PACK") { "This file is already a paper of the item." }
        val memoFile = memoFile(item, input.fileId)
        val upload = if (memoFile == null) upload(meetingId, input.fileId) else null
        requireNoProblems(
            listOfNotNull(
                FieldProblem("fileId", "unknown", "Not a file of the item's memo nor a paper uploaded for this meeting.")
                    .takeIf { memoFile == null && upload == null },
                FieldProblem("fileId", "TOO_MANY", "An item has at most ${AgendaRules.MAX_PAPERS} papers.")
                    .takeIf { present.size >= AgendaRules.MAX_PAPERS },
            ),
        )
        val floor = memoFile?.let { people.higher(item.classificationId, it.classificationId) } ?: item.classificationId
        val level = input.classificationId ?: floor
        val name = memoFile?.title ?: upload?.currentVersionId?.let { files.version(it)?.name }
        val (title, titleProblem) =
            AgendaRules.title(
                "title",
                input.title ?: name?.take(AgendaLimits.MAX_ITEM_TITLE),
                AgendaLimits.MAX_ITEM_TITLE,
            )
        val (restricted, restrictionProblems) = people.restriction("restrictedTo", input.restrictedTo, meetingId, level)
        requireNoProblems(listOfNotNull(titleProblem, people.level("classificationId", level, floor)) + restrictionProblems)
        val late = if (memoFile != null) ridesWith(edit) else lateness.of(edit.meeting)
        val row =
            PaperRow(
                id = ids.next(),
                meetingId = meetingId,
                itemId = item.id,
                position = present.size,
                source = memoFile?.source?.paper() ?: PaperSource.UPLOAD,
                fileId = input.fileId,
                title = title,
                restrictedTo = restricted,
                classificationId = level,
                late = late,
            )
        papers.insert(row, edit.agenda.currentRevision, actorOrNull())
        writes.record("meeting.agenda.paper_added", meetingId, after = audited(row))
        if (late == Lateness.AWAITING_CHAIR) placing.requested(edit.meeting, mapOf("paperId" to row.id.toString()), item.scope)
        guard.done(meetingId)
        return row.id
    }

    fun update(
        meetingId: UUID,
        paperId: UUID,
        version: Long,
        change: PaperChange,
    ) {
        val edit = guard.edit(meetingId, version)
        val before = edit.paper(paperId)
        val item = edit.item(before.itemId)
        val floor =
            memoFile(item, before.fileId)?.let { people.higher(item.classificationId, it.classificationId) } ?: item.classificationId
        val level = change.classificationId ?: before.classificationId
        val (title, titleProblem) = AgendaRules.title("title", change.title, AgendaLimits.MAX_ITEM_TITLE)
        val (restricted, restrictionProblems) = people.restriction("restrictedTo", change.restrictedTo, meetingId, level)
        requireNoProblems(listOfNotNull(titleProblem, people.level("classificationId", level, floor)) + restrictionProblems)
        val after = before.copy(title = title, restrictedTo = restricted, classificationId = level)
        papers.update(after)
        writes.record("meeting.agenda.paper_updated", meetingId, audited(before), audited(after))
        guard.done(meetingId)
    }

    /** Takes a paper out of the item: a memo paper left out of the pack can be put back with [add]. */
    fun remove(
        meetingId: UUID,
        paperId: UUID,
        version: Long,
    ) {
        val edit = guard.edit(meetingId, version)
        val paper = edit.paper(paperId)
        writes.removePaper(edit, paper)
        writes.record("meeting.agenda.paper_removed", meetingId, before = audited(paper))
        guard.done(meetingId)
    }

    /** A memo paper put back while the meeting is in progress is tabled; otherwise it rides with its item. */
    private fun ridesWith(edit: AgendaEdit): Lateness =
        if (AgendaChanges.of(edit.meeting.status) == AgendaChanges.TABLED_ONLY) Lateness.TABLED else Lateness.NONE

    private fun memoFile(
        item: ItemRow,
        fileId: UUID,
    ): MemoPaperFile? =
        item.memoId
            ?.let { memoPapers.pack(it) }
            ?.files
            ?.firstOrNull { it.fileId == fileId }

    private fun upload(
        meetingId: UUID,
        fileId: UUID,
    ): FileInfo? =
        files.info(fileId)?.takeIf {
            it.purpose == MeetingPaperPurpose.KEY && it.ownerId == meetingId.toString() && it.currentVersionId != null
        }

    companion object {
        fun audited(paper: PaperRow): Map<String, Any?> =
            mapOf(
                "paperId" to paper.id.toString(),
                "itemId" to paper.itemId.toString(),
                "source" to paper.source.name,
                "fileId" to paper.fileId.toString(),
                "title" to paper.title,
                "restrictedTo" to paper.restrictedTo?.map { it.toString() },
                "classificationId" to paper.classificationId.toString(),
                "late" to paper.late.name,
            )
    }
}

/**
 * Papers a secretary uploads for a meeting (ADR-0061): the owner is the meeting; record storage; reads audited; a PDF
 * and text built for each version (ADR-0045). Its secretaries (and `forums.manage`) upload until the meeting is held or
 * cancelled. Reads follow the paper rule ([PaperAccess.readsFile], ADR-0061): through these generic endpoints only the
 * forum's officers, when every paper carrying the file passes the rule for them; members read the version a revision
 * pinned through the meeting's own paper endpoint.
 */
@Component
class MeetingPaperPurpose(
    private val meetings: MeetingStore,
    private val access: MeetingAccess,
    private val viewers: PaperViewers,
    private val papers: PaperAccess,
) : FilePurpose {
    override val key = KEY
    override val allowedTypes =
        setOf(
            FileType.PDF,
            FileType.PNG,
            FileType.JPEG,
            FileType.WEBP,
            FileType.TEXT,
            FileType.DOCX,
            FileType.XLSX,
            FileType.PPTX,
            FileType.ODT,
            FileType.ODS,
            FileType.ODP,
        )
    override val maxBytes = MAX_BYTES
    override val storage = StorageClass.RECORD
    override val auditReads = true
    override val inlineTypes = setOf(FileType.PDF, FileType.PNG, FileType.JPEG, FileType.WEBP)
    override val renditions = setOf(RenditionKind.PDF, RenditionKind.TEXT)

    override fun mayUpload(ownerId: String?): Boolean {
        val meeting = meetingOf(ownerId)
        return meeting != null && access.manages(meeting.forumId) && access.cleared(meeting) &&
            AgendaChanges.of(meeting.status) != AgendaChanges.NONE
    }

    override fun mayRead(file: FileInfo): Boolean {
        val meeting = meetingOf(file.ownerId)?.takeIf { access.relation(it) != null }
        val viewer = meeting?.let { viewers.actor(it) }
        return meeting != null && viewer != null && papers.readsFile(viewer, meeting.id, file.id)
    }

    private fun meetingOf(ownerId: String?): MeetingRow? =
        ownerId?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let { meetings.find(it) }

    companion object {
        const val KEY = "meeting.paper"
        const val MAX_BYTES = 50L shl 20
    }
}

/**
 * A new version of an uploaded paper (design E03 §1.8), in the upload's transaction: the papers carrying the file take
 * it (published revisions pin versions, T3), audited `meeting.agenda.paper_replaced`; refused once the meeting is held or
 * cancelled, or for an officer recused from an item carrying it (403), which rolls the upload back.
 */
@Component
class MeetingPaperListener(
    private val papers: PaperStore,
    private val meetings: MeetingStore,
    private val guard: AgendaGuard,
    private val writes: AgendaWrites,
) {
    @EventListener
    fun onVersion(event: FileVersionCreated) {
        if (event.purposeKey != MeetingPaperPurpose.KEY) return
        val carried = papers.ofFile(event.fileId)
        val meeting = carried.firstOrNull()?.let { meetings.find(it.meetingId) }
        if (meeting != null) {
            AgendaRules.requireChange(meeting.status, tabled = true)
            val edit = checkNotNull(guard.system(meeting))
            // A recused officer replaces no paper of an item they are recused from (design E04 §1.7).
            val recused = guard.recused(edit.rows)
            if (carried.any { it.itemId in recused }) throw ForbiddenException(Permission.FORUMS_MANAGE)
            writes.record(
                "meeting.agenda.paper_replaced",
                meeting.id,
                after =
                    mapOf(
                        "fileId" to event.fileId.toString(),
                        "versionId" to event.versionId.toString(),
                        "paperIds" to carried.map { it.id.toString() },
                    ),
            )
            guard.done(meeting.id)
        }
    }
}
