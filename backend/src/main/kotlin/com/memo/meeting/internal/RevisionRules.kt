package com.memo.meeting.internal

import com.memo.files.FileVersionInfo
import com.memo.forum.AgendaSectionKind
import com.memo.forum.StandingKind
import com.memo.forum.SubmissionPurpose
import com.memo.meeting.MeetingStatus
import java.time.Instant
import java.util.UUID

/** What changed on an item against the revision before (design E03 §1.3); restriction changes are never a flag. */
enum class ItemFlag { ADDED, REMOVED, MOVED, RETITLED, TIME_CHANGED, PAPER_ADDED, PAPER_REPLACED, PAPER_REMOVED, LATE, TABLED }

/** What changed on a paper against the revision before. */
enum class PaperFlag { ADDED, REPLACED, REMOVED, LATE, TABLED }

/** How members read an item or paper's lateness (design E03 §1.4): "Late paper" or "Tabled at the meeting". */
enum class PublishedLate { NONE, LATE, TABLED }

/** A section as published. */
data class RevisionSection(
    val sectionId: UUID,
    val position: Int,
    val title: String,
    val kind: AgendaSectionKind,
    val membersOnly: Boolean,
)

/**
 * An item as published (design E03 §1.3): its computed number and start, what the paper rule reads (restriction, hidden
 * title, level, the section's members-only flag, item attendees, its parent), and its flags. A [removed] row is an item
 * the revision took off, kept for "What changed" only.
 */
data class RevisionItem(
    val itemId: UUID,
    val sectionId: UUID,
    val parentId: UUID?,
    val position: Int,
    val number: String?,
    val kind: ItemKind,
    val standingKind: StandingKind?,
    val submissionId: UUID?,
    val memoId: UUID?,
    val title: String,
    val description: String?,
    val purpose: SubmissionPurpose?,
    val resolutionSought: String?,
    val minutes: Int?,
    val startsAt: Instant?,
    val presenters: List<Presenter>,
    val attendees: Set<UUID>,
    val restrictedTo: List<UUID>?,
    val titleHidden: Boolean,
    val membersOnly: Boolean,
    val classificationId: UUID,
    val late: PublishedLate,
    val removed: Boolean = false,
    val previousNumber: String? = null,
    val changes: Set<ItemFlag> = emptySet(),
) {
    /** The item without what the comparison adds: equal contents publish nothing new. */
    fun content(): RevisionItem = copy(removed = false, previousNumber = null, changes = emptySet())
}

/** A paper as published, pinned to the file version it carried (ADR-0061). */
data class RevisionPaper(
    val paperId: UUID,
    val itemId: UUID,
    val position: Int,
    val source: PaperSource,
    val fileId: UUID,
    val fileVersionId: UUID,
    val title: String,
    val restrictedTo: List<UUID>?,
    val classificationId: UUID,
    val late: PublishedLate,
    val pages: Int?,
    val removed: Boolean = false,
    val changes: Set<PaperFlag> = emptySet(),
) {
    fun content(): RevisionPaper = copy(removed = false, changes = emptySet())
}

/** One revision's copy of the agenda, in agenda order. */
data class Snapshot(
    val sections: List<RevisionSection>,
    val items: List<RevisionItem>,
    val papers: List<RevisionPaper>,
) {
    /** The rows in the body (not the struck lines of "What changed"). */
    fun body(): Snapshot = Snapshot(sections, items.filterNot { it.removed }, papers.filterNot { it.removed })

    /** Whether [other] carries the same agenda: nothing would be new in a revision. */
    fun sameAs(other: Snapshot): Boolean {
        val a = body()
        val b = other.body()
        return a.sections == b.sections && a.items.map { it.content() } == b.items.map { it.content() } &&
            a.papers.map { it.content() } == b.papers.map { it.content() }
    }
}

/** What a publication works from: the draft's rows, the meeting's times, the current file versions and the deadline. */
data class SnapshotInput(
    val rows: AgendaRows,
    val startsAt: Instant,
    val endsAt: Instant,
    val versions: Map<UUID, FileVersionInfo>,
    /** The paper deadline has passed: what is first published now is late (design E03 §1.4). */
    val afterDeadline: Boolean,
)

/**
 * The pure rules of publication (ADR-0060, design E03 §1.3): the copy of the draft a revision carries (items waiting for
 * the chair left out, numbers and times computed, papers pinned to their current version), and its change flags against
 * the revision before.
 */
object RevisionRules {
    /** The draft as revision n would carry it; [previous] is revision n−1's body (lateness carries over), null for n = 1. */
    fun snapshot(
        input: SnapshotInput,
        previous: Snapshot?,
    ): Snapshot {
        val rows = input.rows
        val waiting =
            rows.items
                .filter { it.late == Lateness.AWAITING_CHAIR }
                .map { it.id }
                .toSet()
        val items = rows.items.filter { it.id !in waiting && it.parentId !in waiting }
        val numbers = AgendaRules.numbers(rows.sections, items)
        val times = AgendaRules.times(input.startsAt, input.endsAt, rows.sections, items)
        val sections = rows.sections.sortedBy { it.position }
        val membersOnly = sections.associate { it.id to it.membersOnly }
        val ids = items.map { it.id }.toSet()
        val papers =
            rows.papers
                .filter { it.itemId in ids && it.late != Lateness.AWAITING_CHAIR && input.versions[it.fileId] != null }
                .sortedWith(compareBy({ it.itemId }, { it.position }))
        val withPapers = papers.map { it.itemId }.toSet()
        val before = previous?.items?.associateBy { it.itemId }.orEmpty()
        val beforePapers = previous?.papers?.associateBy { it.paperId }.orEmpty()
        val ordered = AgendaRules.ordered(sections, items).flatMap { (item, subs) -> listOf(item) + subs }
        return Snapshot(
            sections = sections.map { RevisionSection(it.id, it.position, it.title, it.kind, it.membersOnly) },
            items =
                ordered.mapIndexed { i, item ->
                    val late = before[item.id]?.late ?: firstLate(item.late, input.afterDeadline && item.id in withPapers)
                    item(item, i, numbers[item.id], times.starts[item.id], rows.attendees[item.id].orEmpty(), membersOnly, late)
                },
            papers =
                papers.map { paper ->
                    val version = checkNotNull(input.versions[paper.fileId])
                    RevisionPaper(
                        paperId = paper.id,
                        itemId = paper.itemId,
                        position = paper.position,
                        source = paper.source,
                        fileId = paper.fileId,
                        fileVersionId = version.id,
                        title = paper.title,
                        restrictedTo = paper.restrictedTo?.sorted(),
                        classificationId = paper.classificationId,
                        late = beforePapers[paper.id]?.late ?: firstLate(paper.late, input.afterDeadline),
                        pages = version.pdfPages,
                    )
                },
        )
    }

    /**
     * [next] with its change flags against [previous] (design E03 §1.3), and the items and papers it took off as removed
     * rows. Revision 1 ([previous] null) has no flags.
     */
    fun flagged(
        next: Snapshot,
        previous: Snapshot?,
    ): Snapshot {
        if (previous == null) return next
        val before = previous.items.associateBy { it.itemId }
        val beforePapers = previous.papers.associateBy { it.paperId }
        val nextPaperIds = next.papers.map { it.paperId }.toSet()
        val papers =
            next.papers.map { it.copy(changes = paperFlags(it, beforePapers[it.paperId])) } +
                previous.papers.filter { it.paperId !in nextPaperIds }.map { it.copy(removed = true, changes = setOf(PaperFlag.REMOVED)) }
        val paperChanges = papers.groupBy({ it.itemId }, { it.changes })
        val moved = moved(previous.items, next.items)
        val nextIds = next.items.map { it.itemId }.toSet()
        val items =
            next.items.map { item ->
                val was = before[item.itemId]
                val flags = itemFlags(item, was, item.itemId in moved, paperChanges[item.itemId].orEmpty().flatten().toSet())
                item.copy(previousNumber = was?.number, changes = flags)
            } +
                previous.items.filter { it.itemId !in nextIds }.map {
                    it.copy(number = null, removed = true, previousNumber = it.number, changes = setOf(ItemFlag.REMOVED))
                }
        return Snapshot(next.sections, items, papers)
    }

    /**
     * The items of [next] that moved (design E03 §1.3): those outside the longest run kept in the same order as in
     * [previous], and those now in another section or under another item. A knock-on renumbering is not a move.
     */
    fun moved(
        previous: List<RevisionItem>,
        next: List<RevisionItem>,
    ): Set<UUID> {
        val before = previous.associateBy { it.itemId }
        val common = next.filter { it.itemId in before }
        val kept = longestIncreasing(common.map { before.getValue(it.itemId).position })
        return common
            .filterIndexed { i, item ->
                val was = before.getValue(item.itemId)
                i !in kept || was.sectionId != item.sectionId || was.parentId != item.parentId
            }.map { it.itemId }
            .toSet()
    }

    /**
     * Why a publication is refused now (design E03 §1.3, 409), in the order they are checked; null when it may go ahead.
     * A meeting held or cancelled is frozen, a draft meeting is not scheduled; the first revision may need the chair's
     * agreement; a paper without a readable PDF refuses it (a pending one does not); and so does a revision with nothing new.
     */
    fun refusal(
        status: MeetingStatus,
        chairAgreementMissing: Boolean,
        unreadable: Boolean,
        nothingNew: Boolean,
    ): String? =
        when {
            AgendaChanges.of(status) == AgendaChanges.NONE -> "FROZEN"
            status == MeetingStatus.DRAFT -> "MEETING_NOT_SCHEDULED"
            chairAgreementMissing -> "CHAIR_AGREEMENT_REQUIRED"
            unreadable -> "PAPER_NOT_READABLE"
            nothingNew -> "NOTHING_TO_PUBLISH"
            else -> null
        }

    /** The indexes of one longest strictly increasing subsequence of [values]. */
    fun longestIncreasing(values: List<Int>): Set<Int> {
        if (values.isEmpty()) return emptySet()
        val length = IntArray(values.size) { 1 }
        val previous = IntArray(values.size) { -1 }
        for (i in values.indices) {
            for (j in 0 until i) {
                if (values[j] < values[i] && length[j] + 1 > length[i]) {
                    length[i] = length[j] + 1
                    previous[i] = j
                }
            }
        }
        val kept = mutableSetOf<Int>()
        var at = length.indices.maxBy { length[it] }
        while (at >= 0) {
            kept += at
            at = previous[at]
        }
        return kept
    }

    private fun itemFlags(
        item: RevisionItem,
        was: RevisionItem?,
        moved: Boolean,
        papers: Set<PaperFlag>,
    ): Set<ItemFlag> =
        buildSet {
            if (was == null) {
                add(ItemFlag.ADDED)
            } else {
                if (item.title != was.title) add(ItemFlag.RETITLED)
                if (item.minutes != was.minutes) add(ItemFlag.TIME_CHANGED)
                if (moved) add(ItemFlag.MOVED)
                if (PaperFlag.ADDED in papers) add(ItemFlag.PAPER_ADDED)
                if (PaperFlag.REPLACED in papers) add(ItemFlag.PAPER_REPLACED)
                if (PaperFlag.REMOVED in papers) add(ItemFlag.PAPER_REMOVED)
            }
            if (item.late == PublishedLate.LATE && was?.late != PublishedLate.LATE) add(ItemFlag.LATE)
            if (item.late == PublishedLate.TABLED && was?.late != PublishedLate.TABLED) add(ItemFlag.TABLED)
        }

    private fun paperFlags(
        paper: RevisionPaper,
        was: RevisionPaper?,
    ): Set<PaperFlag> =
        buildSet {
            if (was == null) add(PaperFlag.ADDED)
            if (was != null && was.fileVersionId != paper.fileVersionId) add(PaperFlag.REPLACED)
            if (paper.late == PublishedLate.LATE && was?.late != PublishedLate.LATE) add(PaperFlag.LATE)
            if (paper.late == PublishedLate.TABLED && was?.late != PublishedLate.TABLED) add(PaperFlag.TABLED)
        }

    /**
     * How something first published is read (design E03 §1.4): tabled at the meeting; late when placed late (and, under
     * CHAIR_APPROVAL, agreed); otherwise late only when first published after the deadline and carrying papers.
     */
    private fun firstLate(
        late: Lateness,
        pastDeadline: Boolean,
    ): PublishedLate =
        when {
            late == Lateness.TABLED -> PublishedLate.TABLED
            late == Lateness.LATE || late == Lateness.CHAIR_AGREED || pastDeadline -> PublishedLate.LATE
            else -> PublishedLate.NONE
        }

    private fun item(
        item: ItemRow,
        position: Int,
        number: String?,
        startsAt: Instant?,
        attendees: Set<UUID>,
        membersOnly: Map<UUID, Boolean>,
        late: PublishedLate,
    ) = RevisionItem(
        itemId = item.id,
        sectionId = item.sectionId,
        parentId = item.parentId,
        position = position,
        number = number,
        kind = item.kind,
        standingKind = item.standingKind,
        submissionId = item.submissionId,
        memoId = item.memoId,
        title = item.title,
        description = item.description,
        purpose = item.purpose,
        resolutionSought = item.resolutionSought,
        minutes = item.minutes,
        startsAt = startsAt,
        presenters = item.presenters,
        attendees = attendees,
        restrictedTo = item.restrictedTo?.sorted(),
        titleHidden = item.titleHidden,
        membersOnly = membersOnly[item.sectionId] == true,
        classificationId = item.classificationId,
        late = late,
    )
}
