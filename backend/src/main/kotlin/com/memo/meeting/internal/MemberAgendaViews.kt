package com.memo.meeting.internal

import com.memo.forum.AgendaSectionKind
import com.memo.forum.StandingKind
import com.memo.forum.SubmissionPurpose
import com.memo.meeting.ItemLink
import com.memo.meeting.LineAccess
import com.memo.meeting.MeetingStatus
import com.memo.meeting.Visibility
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/** A paper the viewer may read, pinned to the version its revision carried. */
data class MemberPaperResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val title: String,
    /** Pages of the PDF, when known. */
    val pages: Int?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val late: PublishedLate,
    /** What this revision changed on it. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val changes: List<PaperFlag>,
    /** False while its PDF is being prepared ("Being prepared"). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val ready: Boolean,
    /** The viewer has opened it before. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val opened: Boolean,
)

/** "Ask to discuss" on a consent item (design E03 §1.5). */
data class DiscussionResponse(
    /** "Lifted for discussion at {names}'s request": the people with an open request, the first first. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val requestedBy: List<PersonResponse>,
    /** The viewer has an open request (they may withdraw it until the meeting starts). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val mine: Boolean,
    /** The viewer may ask now. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val canAsk: Boolean,
)

/**
 * An item of a published revision as the viewer may see it (ADR-0061): in full, or only its line — "{n}. {title} —
 * papers restricted" ([LineAccess.TITLE]) or "{n}. Restricted item" ([LineAccess.HIDDEN], no title). A line carries no
 * description, presenters, papers or requests.
 */
data class MemberItemResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    /** "3", "3.1"; null for breaks. */
    val number: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val access: LineAccess,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val kind: ItemKind,
    val standingKind: StandingKind?,
    /** Null when the title is hidden from the viewer. */
    val title: String?,
    val purpose: SubmissionPurpose?,
    val minutes: Int?,
    val startsAt: Instant?,
    val localStartsAt: LocalDateTime?,
    val description: String?,
    val resolutionSought: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val presenters: List<PresenterResponse>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val late: PublishedLate,
    /** What this revision changed on the item, as far as the viewer sees it. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val changes: List<ItemFlag>,
    /** Its number in the revision before, when it moved. */
    val previousNumber: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val papers: List<MemberPaperResponse>,
    /** For an item the viewer sees in full that is (or was) in a consent section. */
    val discussion: DiscussionResponse?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val subItems: List<MemberItemResponse>,
    /**
     * The viewer is recused from the item (ADR-0062: an effective declaration on it or its parent): its line only, with
     * their own declaration in [myDeclaration]. In a preview, also an item the previewing officer is recused from.
     */
    val recused: Boolean = false,
    /** The viewer's own open declaration of interest on the item, with its nature (design E04 §3). */
    val myDeclaration: DeclarationResponse? = null,
    /** The others' open declarations on an item the viewer sees in full: names and states, never the nature. */
    val declarations: List<DeclarationLineResponse> = emptyList(),
    /**
     * What a standing item the viewer sees in full links to (design E06 §2): the minutes of the previous meeting to read
     * (E06), the action taken report (E07). Only on the latest revision.
     */
    val links: List<ItemLink> = emptyList(),
)

data class MemberSectionResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    /** Null for a members-only section shown to someone outside the membership ("Restricted items"). */
    val title: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val kind: AgendaSectionKind,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val membersOnly: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val items: List<MemberItemResponse>,
)

/** A paper a revision changed, as the viewer may see it. */
data class ChangedPaperResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val title: String,
    /** Taken out by this revision (a struck line). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val removed: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val flags: List<PaperFlag>,
)

/** An item a revision changed, as the viewer may see it (a removed item is a struck line). */
data class ChangedItemResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    val number: String?,
    val previousNumber: String?,
    /** Null when the title is hidden from the viewer. */
    val title: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val access: LineAccess,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val removed: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val flags: List<ItemFlag>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val papers: List<ChangedPaperResponse>,
)

data class RevisionChangesResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val number: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val publishedAt: Instant,
    val note: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val items: List<ChangedItemResponse>,
)

/** "What changed since you last looked": the revisions after the last one the viewer saw, limited to what they see. */
data class WhatChangedResponse(
    /** The last revision the viewer looked at. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val since: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val revisions: List<RevisionChangesResponse>,
    /** How many changed lines, across the revisions. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val count: Int,
)

/** A published revision of the agenda as the viewer reads it (design E03 §3, flow P), or the preview "as a member". */
data class MemberAgendaResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val meetingId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val status: MeetingStatus,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val startsAt: Instant,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val endsAt: Instant,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val timeZone: String,
    /** The revision shown; null when nothing is published yet. In a preview, the number the next revision would take. */
    val revision: Int?,
    /** The latest published revision (0 before the first). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val latestRevision: Int,
    val publishedAt: Instant?,
    /** What changed, in one line (from revision 2). */
    val note: String?,
    /** A preview of the draft as one person would see it if it were published now. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val preview: Boolean,
    /** The last revision the viewer looked at; null when never. */
    val lastSeenRevision: Int?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val sections: List<MemberSectionResponse>,
    /** Only on the latest revision, once the viewer has looked at an earlier one. */
    val whatChanged: WhatChangedResponse?,
)

/** A published revision as the audience lists it: officers also see who published it and whether the audience was told. */
data class PublishedRevisionResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val number: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val publishedAt: Instant,
    /** What changed, in one line; not for `forum.read` holders outside the audience. */
    val note: String?,
    /** Officers only. */
    val publishedBy: PersonResponse?,
    /** Officers only. */
    val notified: Boolean?,
)

/** What a revision's body shows of each item and paper: everything the renderer needs, read once. */
data class MemberAgendaInput(
    val meeting: MeetingRow,
    val snapshot: Snapshot,
    val visibility: Visibility,
    val names: Map<UUID, String>,
    /** Papers whose PDF is ready to read. */
    val ready: Set<UUID>,
    val opened: Set<UUID>,
    /** Open consent requests by item. */
    val requests: Map<UUID, List<ConsentRequestRow>>,
    val viewerId: UUID,
    /** Open (not withdrawn) declarations of interest by item (design E04 §3). */
    val declarations: Map<UUID, List<DeclarationRow>> = emptyMap(),
    /** The viewer may ask to discuss consent items now (the latest revision, the meeting scheduled, a member or guest). */
    val mayAsk: Boolean,
    /** The viewer reads members-only sections (an officer, or a member for them): their headings and titles. */
    val inCamera: Boolean,
    /** What a standing item seen in full links to (design E06 §2), by item and kind; nothing in a preview or an old revision. */
    val links: (UUID, StandingKind) -> List<ItemLink> = { _, _ -> emptyList() },
)

/** Builds what a viewer sees of a revision's body (design E03 §1.9): lines by the paper rule, papers it lets them read. */
object MemberAgendaRender {
    /** The flags a line shows when the viewer sees only its title, or not even that. */
    private val TITLE_FLAGS = setOf(ItemFlag.ADDED, ItemFlag.REMOVED, ItemFlag.MOVED, ItemFlag.RETITLED, ItemFlag.TIME_CHANGED)
    private val HIDDEN_FLAGS = TITLE_FLAGS - ItemFlag.RETITLED

    /** The item flag each paper flag raises (design E03 §1.3). */
    private val PAPER_FLAGS =
        mapOf(
            PaperFlag.ADDED to ItemFlag.PAPER_ADDED,
            PaperFlag.REPLACED to ItemFlag.PAPER_REPLACED,
            PaperFlag.REMOVED to ItemFlag.PAPER_REMOVED,
        )

    fun sections(
        input: MemberAgendaInput,
        reads: (Boolean) -> Boolean,
    ): List<MemberSectionResponse> {
        val body = input.snapshot.body()
        val items = body.items.filter { it.itemId in input.visibility.lines }
        val consent =
            body.sections
                .filter { it.kind == AgendaSectionKind.CONSENT }
                .map { it.sectionId }
                .toSet()
        return body.sections.mapNotNull { section ->
            val inSection = items.filter { it.sectionId == section.sectionId }
            val shown = inSection.map { it.itemId }.toSet()
            val tops = inSection.filter { it.parentId == null || it.parentId !in shown }
            section.takeIf { inSection.isNotEmpty() || reads(it.membersOnly) }?.let {
                MemberSectionResponse(
                    id = it.sectionId,
                    title = if (!it.membersOnly || input.inCamera) it.title else null,
                    kind = it.kind,
                    membersOnly = it.membersOnly,
                    items =
                        tops.map { top ->
                            item(input, top, it.sectionId in consent, inSection.filter { s -> s.parentId == top.itemId })
                        },
                )
            }
        }
    }

    /**
     * [visibility] with the items in [withheld] (an officer's own recusals, design E04 §1.7) cut down to their recused line:
     * no more than the title when it is not hidden, no papers. For the preview: the officer previews someone else's view,
     * never past their own recusal.
     */
    fun withheld(
        visibility: Visibility,
        snapshot: Snapshot,
        withheld: Set<UUID>,
    ): Visibility {
        if (withheld.isEmpty()) return visibility
        val hidden =
            snapshot.items
                .filter { it.titleHidden }
                .map { it.itemId }
                .toSet()
        val lines =
            visibility.lines.mapValues { (item, access) ->
                when {
                    item !in withheld || access == LineAccess.HIDDEN -> access
                    item in hidden -> LineAccess.HIDDEN
                    else -> LineAccess.TITLE
                }
            }
        val papers =
            snapshot.papers
                .filter { it.itemId in withheld }
                .map { it.paperId }
                .toSet()
        return Visibility(lines, visibility.papers - papers, visibility.recused + (withheld intersect lines.keys))
    }

    /**
     * [item] as its line shows it to someone recused from it who reads its title unless hidden (an officer, design E04
     * §1.7): the title ("" when hidden) and the line's flags, nothing else.
     */
    fun line(item: RevisionItem): RevisionItem {
        val access = if (item.titleHidden) LineAccess.HIDDEN else LineAccess.TITLE
        return item.copy(
            title = if (item.titleHidden) "" else item.title,
            description = null,
            resolutionSought = null,
            presenters = emptyList(),
            attendees = emptySet(),
            restrictedTo = null,
            changes = flags(item.changes, access, emptySet()).toSet(),
        )
    }

    /** The changed lines of one revision the viewer sees (removed rows judged as they were published). */
    fun changes(
        snapshot: Snapshot,
        visibility: Visibility,
    ): List<ChangedItemResponse> {
        val papers = snapshot.papers.filter { it.paperId in visibility.papers && it.changes.isNotEmpty() }.groupBy { it.itemId }
        return snapshot.items.mapNotNull { item ->
            val access = visibility.lines[item.itemId] ?: return@mapNotNull null
            val changed = if (access == LineAccess.FULL) papers[item.itemId].orEmpty() else emptyList()
            val flags = flags(item.changes, access, changed.flatMap { it.changes }.toSet())
            if (flags.isEmpty() && changed.isEmpty()) return@mapNotNull null
            ChangedItemResponse(
                id = item.itemId,
                number = item.number,
                previousNumber = item.previousNumber,
                title = item.title.takeIf { access != LineAccess.HIDDEN },
                access = access,
                removed = item.removed,
                flags = flags,
                papers = changed.map { ChangedPaperResponse(it.paperId, it.title, it.removed, it.changes.sorted()) },
            )
        }
    }

    private fun item(
        input: MemberAgendaInput,
        item: RevisionItem,
        inConsent: Boolean,
        subs: List<RevisionItem>,
    ): MemberItemResponse {
        val access = input.visibility.lines.getValue(item.itemId)
        val full = access == LineAccess.FULL
        val zone: ZoneId = input.meeting.zone
        val requests = input.requests[item.itemId].orEmpty()
        val title = item.title.takeIf { access != LineAccess.HIDDEN }
        val discussion =
            if (full && (inConsent || requests.isNotEmpty())) {
                val mine = requests.any { it.userId == input.viewerId }
                DiscussionResponse(
                    requestedBy = requests.map { PersonResponse(it.userId, input.names[it.userId].orEmpty()) },
                    mine = mine,
                    canAsk = input.mayAsk && inConsent && !mine,
                )
            } else {
                null
            }
        return MemberItemResponse(
            id = item.itemId,
            number = item.number,
            access = access,
            kind = item.kind,
            standingKind = item.standingKind,
            title = title,
            purpose = item.purpose,
            minutes = item.minutes,
            startsAt = item.startsAt,
            localStartsAt = item.startsAt?.let { LocalDateTime.ofInstant(it, zone) },
            description = item.description.takeIf { full },
            resolutionSought = item.resolutionSought.takeIf { full },
            presenters =
                if (full) {
                    item.presenters.map { PresenterResponse(it.userId, it.userId?.let { u -> input.names[u] } ?: it.name.orEmpty()) }
                } else {
                    emptyList()
                },
            late = if (full) item.late else PublishedLate.NONE,
            changes = flags(item.changes, access, paperChanges(input, item)),
            previousNumber = item.previousNumber.takeIf { ItemFlag.MOVED in item.changes },
            papers = if (full) papers(input, item) else emptyList(),
            discussion = discussion,
            subItems = subs.map { item(input, it, inConsent, emptyList()) },
            recused = item.itemId in input.visibility.recused,
            myDeclaration = mine(input, item, title),
            declarations = if (full) others(input, item) else emptyList(),
            links =
                item.standingKind
                    ?.takeIf { full }
                    ?.let { input.links(item.itemId, it) }
                    .orEmpty(),
        )
    }

    /** The viewer's own open declaration on [item], with its nature. */
    private fun mine(
        input: MemberAgendaInput,
        item: RevisionItem,
        title: String?,
    ): DeclarationResponse? =
        input.declarations[item.itemId]
            ?.firstOrNull { it.userId == input.viewerId }
            ?.let { DeclarationViews.of(it, item.number, title, input.names) }

    /** The others' open declarations on [item]: names and states. */
    private fun others(
        input: MemberAgendaInput,
        item: RevisionItem,
    ): List<DeclarationLineResponse> =
        input.declarations[item.itemId]
            .orEmpty()
            .filter { it.userId != input.viewerId }
            .map { DeclarationViews.line(it, input.names) }

    private fun papers(
        input: MemberAgendaInput,
        item: RevisionItem,
    ): List<MemberPaperResponse> =
        input.snapshot
            .body()
            .papers
            .filter { it.itemId == item.itemId && it.paperId in input.visibility.papers }
            .sortedBy { it.position }
            .map {
                MemberPaperResponse(
                    id = it.paperId,
                    title = it.title,
                    pages = it.pages,
                    late = it.late,
                    changes = it.changes.sorted(),
                    ready = it.paperId in input.ready,
                    opened = it.paperId in input.opened,
                )
            }

    /** What the viewer may read of the paper changes of [item] in this revision (removed papers included). */
    private fun paperChanges(
        input: MemberAgendaInput,
        item: RevisionItem,
    ): Set<PaperFlag> =
        input.snapshot.papers
            .filter { it.itemId == item.itemId && it.paperId in input.visibility.papers }
            .flatMap { it.changes }
            .toSet()

    /**
     * The flags of a line as far as the viewer sees it: a title line shows only what its line shows, and an item seen in
     * full shows a paper flag only when a paper they may read carries it (a restricted paper's replacement is not told).
     */
    private fun flags(
        changes: Set<ItemFlag>,
        access: LineAccess,
        papers: Set<PaperFlag>,
    ): List<ItemFlag> =
        when (access) {
            LineAccess.FULL -> changes - (PAPER_FLAGS.values.toSet() - papers.mapNotNull { PAPER_FLAGS[it] }.toSet())
            LineAccess.TITLE -> changes intersect TITLE_FLAGS
            LineAccess.HIDDEN -> changes intersect HIDDEN_FLAGS
        }.sorted()
}
