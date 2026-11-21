package com.memo.meeting

import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.files.FileUploads
import com.memo.files.Files
import com.memo.forum.SubmissionPurpose
import com.memo.meeting.internal.AgendaChair
import com.memo.meeting.internal.AgendaGuard
import com.memo.meeting.internal.AgendaItems
import com.memo.meeting.internal.AgendaPapers
import com.memo.meeting.internal.AgendaPublisher
import com.memo.meeting.internal.AgendaSections
import com.memo.meeting.internal.AgendaStore
import com.memo.meeting.internal.ItemChange
import com.memo.meeting.internal.ItemInput
import com.memo.meeting.internal.ItemKind
import com.memo.meeting.internal.LateDecision
import com.memo.meeting.internal.Lateness
import com.memo.meeting.internal.MeetingPaperPurpose
import com.memo.meeting.internal.PaperInput
import com.memo.meeting.internal.Presenter
import com.memo.meeting.internal.SectionInput
import org.springframework.stereotype.Service
import java.util.UUID

/** An item a seeder puts on an agenda, in the section titled [section]. */
data class SeedItem(
    val section: String,
    val title: String,
    val purpose: SubmissionPurpose = SubmissionPurpose.INFORMATION,
    val minutes: Int? = null,
    val description: String? = null,
    val resolutionSought: String? = null,
    /** Presents the item, and attends for it. */
    val presenterId: UUID? = null,
    /** "Only these people" see its papers; null: everyone who sees the item. */
    val restrictedTo: List<UUID>? = null,
)

/**
 * Agendas for seeders (the demo tenant, Phase 4 E03 T9), through the same services as the API, as the user bound to the
 * current unit of work: sections, items, placing from the queue, papers, the chair's answers and publishing. Never called
 * from requests. Each call is its own tenant transaction at the agenda's current version, except uploads, which run
 * outside one as the files service needs.
 */
@Service
class AgendaSeeding(
    private val agendas: AgendaStore,
    private val guard: AgendaGuard,
    private val sections: AgendaSections,
    private val items: AgendaItems,
    private val papers: AgendaPapers,
    private val chair: AgendaChair,
    private val publisher: AgendaPublisher,
    private val files: Files,
    private val uploads: FileUploads,
    private val transactions: TenantTransactions,
) {
    /** Adds a section at the end; [membersOnly]: in camera. */
    fun section(
        meetingId: UUID,
        title: String,
        membersOnly: Boolean,
    ): UUID = tx { sections.add(meetingId, version(meetingId), SectionInput(title, membersOnly = membersOnly), null) }

    /** Adds [item] at the end of its section, with its details, presenter and restriction. */
    fun item(
        meetingId: UUID,
        item: SeedItem,
    ): UUID =
        tx {
            val section = sectionId(meetingId, item.section)
            val id =
                items.add(
                    meetingId,
                    version(meetingId),
                    ItemInput(ItemKind.ITEM, sectionId = section, title = item.title, purpose = item.purpose, minutes = item.minutes),
                )
            items.update(
                meetingId,
                id,
                version(meetingId),
                ItemChange(
                    title = item.title,
                    description = item.description,
                    purpose = item.purpose,
                    resolutionSought = item.resolutionSought,
                    minutes = item.minutes,
                    presenters = listOfNotNull(item.presenterId?.let { Presenter(userId = it) }),
                    restrictedTo = item.restrictedTo,
                ),
            )
            id
        }

    /** Places [submissionId] from the committee's queue (accepting it if it is still queued); the new item's id. */
    fun place(
        meetingId: UUID,
        submissionId: UUID,
        minutes: Int?,
    ): UUID =
        tx {
            items.add(meetingId, version(meetingId), ItemInput(ItemKind.SUBMISSION, submissionId = submissionId, minutes = minutes))
        }

    /** Uploads [bytes] as a meeting paper and puts it on [itemId]; the file's id. Late after the paper deadline. */
    fun paper(
        meetingId: UUID,
        itemId: UUID,
        name: String,
        bytes: ByteArray,
    ): UUID {
        val uploaded = uploads.upload(MeetingPaperPurpose.KEY, meetingId.toString(), name, bytes, UUID.randomUUID().toString())
        return tx {
            val fileId = checkNotNull(files.version(uploaded)) { "version $uploaded" }.fileId
            papers.add(meetingId, itemId, version(meetingId), PaperInput(fileId))
            fileId
        }
    }

    /** A new version of the paper [fileId]: the draft takes it, published revisions keep theirs. */
    fun replace(
        fileId: UUID,
        name: String,
        bytes: ByteArray,
    ) {
        val current = tx { checkNotNull(files.info(fileId)) { "file $fileId" } }
        uploads.uploadVersion(current.id, current.version, name, bytes, UUID.randomUUID().toString())
    }

    /** A secretary records that the chair agreed the agenda, saying how ([note]). */
    fun agreedOnBehalf(
        meetingId: UUID,
        note: String,
    ) {
        tx { chair.agree(meetingId, version(meetingId), true, note) }
    }

    /** The chair, in the app, agrees every late paper waiting for them; how many. */
    fun agreeLatePapers(meetingId: UUID): Int =
        tx {
            val waiting = checkNotNull(guard.rows(meetingId)).papers.filter { it.late == Lateness.AWAITING_CHAIR }
            waiting.forEach { chair.decidePaper(meetingId, it.id, version(meetingId), LateDecision(true)) }
            waiting.size
        }

    /** Publishes the next revision, telling the audience; its number. */
    fun publish(
        meetingId: UUID,
        note: String?,
    ): Int = tx { publisher.publish(meetingId, version(meetingId), note, notify = true) }

    private fun version(meetingId: UUID): Long = checkNotNull(agendas.find(meetingId)) { "agenda of $meetingId" }.version

    private fun sectionId(
        meetingId: UUID,
        title: String,
    ): UUID = checkNotNull(checkNotNull(guard.rows(meetingId)).sections.firstOrNull { it.title == title }) { "section $title" }.id

    private fun <T> tx(block: () -> T): T = transactions.inTenant(TenantContext.current(), block)
}
