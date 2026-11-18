package com.memo.meeting.internal

import com.memo.files.Files
import com.memo.forum.AgendaPolicy
import com.memo.forum.AgendaSectionKind
import com.memo.forum.LateMode
import com.memo.forum.StandingKind
import com.memo.forum.SubmissionPlacements
import com.memo.forum.SubmissionPurpose
import com.memo.identity.UserSummaries
import com.memo.memo.MemoPack
import com.memo.memo.MemoPapers
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

data class PersonResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
)

/** A presenter: a user (with their name) or a typed name. */
data class PresenterResponse(
    val userId: UUID?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
)

data class AgendaPaperResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val source: PaperSource,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val fileId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val title: String,
    /** "Only these people"; null: everyone who sees the item. */
    val restrictedTo: List<PersonResponse>?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val classificationId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val late: Lateness,
    /** The file's current version: name, type, size and pages (PDF). */
    val fileName: String?,
    val mediaType: String?,
    val sizeBytes: Long?,
    val pages: Int?,
)

/** A file of the item's memo left out of the pack: it can be put back. */
data class PackFileResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val fileId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val source: PaperSource,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val title: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val classificationId: UUID,
)

data class AgendaItemResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    /** The number it would be published with ("3", "3.1"); null for breaks and items waiting for the chair. */
    val number: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val kind: ItemKind,
    val standingKind: StandingKind?,
    val submissionId: UUID?,
    val memoId: UUID?,
    /** The memo's number, for officers. */
    val memoReference: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val title: String,
    val description: String?,
    val purpose: SubmissionPurpose?,
    val resolutionSought: String?,
    val minutes: Int?,
    /** Computed: the meeting's start plus the minutes of the items before it; null for sub-items. */
    val startsAt: Instant?,
    val localStartsAt: LocalDateTime?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val presenters: List<PresenterResponse>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val attendees: List<PersonResponse>,
    val restrictedTo: List<PersonResponse>?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val titleHidden: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val classificationId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val late: Lateness,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val papers: List<AgendaPaperResponse>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val outOfPack: List<PackFileResponse>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val subItems: List<AgendaItemResponse>,
    /** The viewer's own open declaration of interest on the item (design E04 §3). */
    val myDeclaration: DeclarationResponse? = null,
    /** The others' open declarations on the item: names and states, never the nature. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val declarations: List<DeclarationLineResponse> = emptyList(),
)

data class AgendaSectionResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val title: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val kind: AgendaSectionKind,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val membersOnly: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val receivesSubmissions: Boolean,
    /** The planned minutes of its items. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val minutes: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val items: List<AgendaItemResponse>,
)

/** What a copy of an earlier meeting left behind (design E03 §1.7). */
data class CopyReportResponse(
    /** People dropped from restrictions: not on this meeting, or not cleared. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val dropped: List<PersonResponse>,
    /** Items left out: their restriction would have named nobody on this meeting. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val skipped: List<String>,
)

/** The chair's agreement to the first publication: in the app, or recorded by a secretary on the chair's behalf. */
data class ChairAgreementResponse(
    val by: PersonResponse?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val at: Instant,
    /** The secretary who recorded it; null when the chair agreed in the app. */
    val onBehalfBy: PersonResponse?,
    /** How it was given ("by email on 4 Dec"). */
    val note: String?,
)

data class ChairCommentResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val text: String,
    val by: PersonResponse?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val at: Instant,
)

/** A published revision, as officers list it. */
data class RevisionResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val number: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val publishedAt: Instant,
    val publishedBy: PersonResponse?,
    /** What changed, in one line (from revision 2). */
    val note: String?,
    /** Whether the audience was told. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val notified: Boolean,
)

/** The working draft for officers (design E03 §3): numbers, times, totals, the paper deadline and the tray's size. */
data class AgendaDraftResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val meetingId: UUID,
    /** False until the secretary starts it; [template] is then what it would start from. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val started: Boolean,
    val origin: AgendaOrigin?,
    val sourceMeetingId: UUID?,
    /** The last published revision; 0 before the first. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val currentRevision: Int,
    /** Changes since the last revision. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val dirty: Boolean,
    /** What the draft accepts now, by the meeting's status. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val changes: AgendaChanges,
    /** Whether the viewer may change it (the chair reads only). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val canEdit: Boolean,
    /** Whether the committee type asks for the chair's agreement before the first publication. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val chairAgreementRequired: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val startsAt: Instant,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val endsAt: Instant,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val timeZone: String,
    /** The last day papers are on time, in the meeting's zone. */
    val paperDeadline: LocalDate?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val deadlinePassed: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val lateMode: LateMode,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val totalMinutes: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val plannedEndsAt: Instant,
    /** The agenda as planned runs past the meeting's end. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val overrun: Boolean,
    /** Submissions ready for an agenda (the tray). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val traySize: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val sections: List<AgendaSectionResponse>,
    val template: AgendaPolicy?,
    /** DRAFT until revision 1 is published. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val state: AgendaState,
    /** The chair's agreement to the first publication, once given. */
    val chairAgreement: ChairAgreementResponse?,
    /** Whether the viewer is the chair in office (the vice-chair when there is none): they agree and decide late papers. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val canAgree: Boolean,
    /** The chair's comments to the secretaries, the oldest first. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val chairComments: List<ChairCommentResponse>,
    /** The published revisions, the first first. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val revisions: List<RevisionResponse>,
    /** Only in the answer to a copy. */
    val copied: CopyReportResponse?,
    /** The agenda's version: send it as If-Match with every change. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val version: Long,
)

/** The draft as its officers read it (design E03 §1.2, §3). */
@Component
class AgendaDraftViews(
    private val guard: AgendaGuard,
    private val start: AgendaStart,
    private val lateness: AgendaLateness,
    private val placements: SubmissionPlacements,
    private val memoPapers: MemoPapers,
    private val files: Files,
    private val users: UserSummaries,
    private val access: MeetingAccess,
    private val comments: ChairCommentStore,
    private val revisions: RevisionStore,
    private val declarations: DeclarationStore,
    private val declarationViews: DeclarationViews,
) {
    fun draft(
        meetingId: UUID,
        copied: CopyReport? = null,
    ): AgendaDraftResponse {
        val meeting = guard.requireReader(meetingId)
        val rows = guard.rows(meeting.id)
        val policy = lateness.policy(meeting)
        val deadline = lateness.deadline(meeting)
        val sections = rows?.sections.orEmpty()
        val times = AgendaRules.times(meeting.startsAt, meeting.endsAt, sections, rows?.items.orEmpty())
        return AgendaDraftResponse(
            meetingId = meeting.id,
            started = rows != null,
            origin = rows?.agenda?.origin,
            sourceMeetingId = rows?.agenda?.sourceMeetingId,
            currentRevision = rows?.agenda?.currentRevision ?: 0,
            dirty = rows?.agenda?.dirty ?: false,
            changes = AgendaChanges.of(meeting.status),
            canEdit = guard.canEdit(meeting),
            chairAgreementRequired = policy.agenda.chairAgreement,
            startsAt = meeting.startsAt,
            endsAt = meeting.endsAt,
            timeZone = meeting.zone.id,
            paperDeadline = deadline,
            deadlinePassed = lateness.passed(meeting, deadline),
            lateMode = policy.submission.lateMode,
            totalMinutes = times.totalMinutes,
            plannedEndsAt = times.endsAt,
            overrun = times.overrun,
            traySize = placements.ready(meeting.forumId, meeting.date).size,
            sections = rows?.let { sections(it, times, meeting.zone) }.orEmpty(),
            template = if (rows == null) start.template(meeting) else null,
            state = rows?.agenda?.state ?: AgendaState.DRAFT,
            chairAgreement = rows?.agenda?.chairAgreement?.let { agreement(it) },
            canAgree = access.chairs(meeting.forumId),
            chairComments = if (rows == null) emptyList() else commentsOf(meeting.id),
            revisions = if (rows == null) emptyList() else revisionsOf(meeting.id),
            copied = copied?.let { report -> CopyReportResponse(people(report.dropped), report.skipped) },
            version = rows?.agenda?.version ?: 0,
        )
    }

    private fun sections(
        rows: AgendaRows,
        times: AgendaTimes,
        zone: ZoneId,
    ): List<AgendaSectionResponse> {
        val numbers = AgendaRules.numbers(rows.sections, rows.items)
        val packs =
            rows.items
                .mapNotNull { it.memoId }
                .distinct()
                .mapNotNull { memoPapers.pack(it) }
                .associateBy { it.memoId }
        val names =
            users.names(
                (
                    rows.items.flatMap { it.presenters.mapNotNull { p -> p.userId } + it.restrictedTo.orEmpty() } +
                        rows.attendees.values.flatten() + rows.papers.flatMap { it.restrictedTo.orEmpty() }
                ).toSet(),
            )
        val versions = files.currentVersions(rows.papers.map { it.fileId }.toSet())
        val open = declarations.ofMeeting(rows.agenda.meetingId).filter { it.state != DeclarationState.WITHDRAWN }
        val declared = DraftDeclarations(actorOrNull(), open.groupBy { it.itemId }, numbers, declarationViews.names(open))
        val view = ItemView(rows, numbers, times, zone, packs, names, versions, declared)
        val ordered = AgendaRules.ordered(rows.sections, rows.items)
        return rows.sections.sortedBy { it.position }.map { s ->
            AgendaSectionResponse(
                id = s.id,
                title = s.title,
                kind = s.kind,
                membersOnly = s.membersOnly,
                receivesSubmissions = s.receivesSubmissions,
                minutes = times.sectionMinutes[s.id] ?: 0,
                items =
                    ordered.filter { it.first.sectionId == s.id }.map { (item, subs) ->
                        view.item(item, subs.map { view.item(it, emptyList()) })
                    },
            )
        }
    }

    private fun people(ids: Collection<UUID>): List<PersonResponse> {
        val names = users.names(ids)
        return ids.sorted().map { PersonResponse(it, names[it].orEmpty()) }
    }

    private fun agreement(agreement: ChairAgreement): ChairAgreementResponse {
        val names = users.names(listOfNotNull(agreement.by, agreement.onBehalfBy))
        return ChairAgreementResponse(
            by = agreement.by?.let { PersonResponse(it, names[it].orEmpty()) },
            at = agreement.at,
            onBehalfBy = agreement.onBehalfBy?.let { PersonResponse(it, names[it].orEmpty()) },
            note = agreement.note,
        )
    }

    private fun commentsOf(meetingId: UUID): List<ChairCommentResponse> {
        val all = comments.of(meetingId)
        val names = users.names(all.mapNotNull { it.by })
        return all.map { c -> ChairCommentResponse(c.id, c.text, c.by?.let { PersonResponse(it, names[it].orEmpty()) }, c.at) }
    }

    private fun revisionsOf(meetingId: UUID): List<RevisionResponse> {
        val heads = revisions.heads(meetingId)
        val names = users.names(heads.mapNotNull { it.publishedBy })
        return heads.map { h ->
            RevisionResponse(h.number, h.publishedAt, h.publishedBy?.let { PersonResponse(it, names[it].orEmpty()) }, h.note, h.notified)
        }
    }
}

/**
 * The open declarations on the draft's items (design E04 §3): the viewer's own on each item, and the names and states of
 * the others' (the nature only in the viewer's own; the meeting register is T4's). T2 turns the items the viewer is
 * recused from into lines.
 */
private class DraftDeclarations(
    private val actor: UUID?,
    private val byItem: Map<UUID, List<DeclarationRow>>,
    private val numbers: Map<UUID, String>,
    private val names: Map<UUID, String>,
) {
    fun mine(item: ItemRow): DeclarationResponse? =
        byItem[item.id]?.firstOrNull { it.userId == actor }?.let { DeclarationViews.of(it, numbers[item.id], item.title, names) }

    fun others(item: ItemRow): List<DeclarationLineResponse> =
        byItem[item.id].orEmpty().filter { it.userId != actor }.map { DeclarationViews.line(it, names) }
}

/** One item of the draft as officers see it. */
private class ItemView(
    private val rows: AgendaRows,
    private val numbers: Map<UUID, String>,
    private val times: AgendaTimes,
    private val zone: ZoneId,
    private val packs: Map<UUID, MemoPack>,
    private val names: Map<UUID, String>,
    private val versions: Map<UUID, com.memo.files.FileVersionInfo>,
    private val declared: DraftDeclarations,
) {
    fun item(
        item: ItemRow,
        subItems: List<AgendaItemResponse>,
    ): AgendaItemResponse {
        val papers = rows.papers.filter { it.itemId == item.id }.sortedBy { it.position }
        val pack = item.memoId?.let { packs[it] }
        val startsAt = times.starts[item.id]
        return AgendaItemResponse(
            id = item.id,
            number = numbers[item.id],
            kind = item.kind,
            standingKind = item.standingKind,
            submissionId = item.submissionId,
            memoId = item.memoId,
            memoReference = pack?.reference,
            title = item.title,
            description = item.description,
            purpose = item.purpose,
            resolutionSought = item.resolutionSought,
            minutes = item.minutes,
            startsAt = startsAt,
            localStartsAt = startsAt?.let { LocalDateTime.ofInstant(it, zone) },
            presenters = item.presenters.map { PresenterResponse(it.userId, it.userId?.let { u -> names[u] } ?: it.name.orEmpty()) },
            attendees =
                rows.attendees[item.id]
                    .orEmpty()
                    .sorted()
                    .map { person(it) },
            restrictedTo = item.restrictedTo?.map { person(it) },
            titleHidden = item.titleHidden,
            classificationId = item.classificationId,
            late = item.late,
            papers = papers.map { paper(it) },
            outOfPack =
                pack
                    ?.files
                    .orEmpty()
                    .filter { f -> papers.none { it.fileId == f.fileId } }
                    .map { PackFileResponse(it.fileId, it.source.paper(), it.title, it.classificationId) },
            subItems = subItems,
            myDeclaration = declared.mine(item),
            declarations = declared.others(item),
        )
    }

    private fun paper(paper: PaperRow): AgendaPaperResponse {
        val version = versions[paper.fileId]
        return AgendaPaperResponse(
            id = paper.id,
            source = paper.source,
            fileId = paper.fileId,
            title = paper.title,
            restrictedTo = paper.restrictedTo?.map { person(it) },
            classificationId = paper.classificationId,
            late = paper.late,
            fileName = version?.name,
            mediaType = version?.mediaType,
            sizeBytes = version?.sizeBytes,
            pages = version?.pdfPages,
        )
    }

    private fun person(id: UUID) = PersonResponse(id, names[id].orEmpty())
}
