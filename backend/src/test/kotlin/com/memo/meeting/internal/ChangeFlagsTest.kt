package com.memo.meeting.internal

import com.memo.files.FileVersionInfo
import com.memo.forum.AgendaSectionKind
import com.memo.forum.SubmissionPurpose
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * What a revision carries and its change flags (test plan E03, T3; design E03 §1.3, §1.4): each flag from pairs of
 * revisions, a move flagged once (not the knock-on renumbering), restriction changes never shown but still published,
 * items waiting for the chair left out, papers pinned, lateness first published and then carried.
 */
class ChangeFlagsTest {
    private val meeting = UUID.randomUUID()
    private val opening = section(0, "Opening")
    private val business = section(1, "For decision")
    private val start: Instant = Instant.parse("2026-12-12T04:00:00Z")
    private val end: Instant = start.plusSeconds(3 * 3600)

    @Test
    fun `revision 1 has numbers, times and pinned papers, no flags, and leaves out what waits for the chair`() {
        val welcome = item(opening, 0, minutes = 10)
        val credit = item(business, 0, minutes = 30)
        val sub = item(business, 0, parent = credit)
        val waiting = item(business, 1, minutes = 20, late = Lateness.AWAITING_CHAIR)
        val underWaiting = item(business, 0, parent = waiting)
        val paper = paper(credit, 0)
        val latePaper = paper(credit, 1, late = Lateness.AWAITING_CHAIR)
        val versions = versions(paper, latePaper)

        val first = flagged(rows(listOf(welcome, credit, sub, waiting, underWaiting), listOf(paper, latePaper)), versions, null)

        assertThat(first.items.map { it.itemId to it.number }).containsExactly(welcome.id to "1", credit.id to "2", sub.id to "2.1")
        assertThat(first.items.map { it.startsAt }).containsExactly(start, start.plusSeconds(600), null)
        assertThat(first.items.map { it.position }).containsExactly(0, 1, 2)
        assertThat(first.items.single { it.itemId == sub.id }.parentId).isEqualTo(credit.id)
        assertThat(first.items.flatMap { it.changes }).isEmpty()
        assertThat(first.papers.map { it.paperId to it.fileVersionId }).containsExactly(paper.id to versions.getValue(paper.fileId).id)
        assertThat(first.papers.single().pages).isEqualTo(PAGES)
        assertThat(first.items.map { it.late }.toSet()).containsExactly(PublishedLate.NONE)
    }

    @Test
    fun `moving item 5 to position 3 flags it alone, with its old number`() {
        val items = (0 until 5).map { item(business, it, minutes = 10) }
        val first = flagged(rows(items), emptyMap(), null)
        val fifth = items[4]

        val moved = items.take(2) + fifth.copy(position = 2) + items[2].copy(position = 3) + items[3].copy(position = 4)
        val second = flagged(rows(moved), emptyMap(), first)

        val flags = second.items.filter { it.changes.isNotEmpty() }
        assertThat(flags.map { it.itemId to it.changes }).containsExactly(fifth.id to setOf(ItemFlag.MOVED))
        assertThat(flags.single().number to flags.single().previousNumber).isEqualTo("3" to "5")
        assertThat(
            second.items.single { it.itemId == items[2].id }.number to second.items.single { it.itemId == items[2].id }.previousNumber,
        ).isEqualTo("4" to "3")
    }

    @Test
    fun `a new item is added, a taken one removed with its old number, and an item moved to another section or under an item moves`() {
        val a = item(opening, 0, minutes = 5)
        val b = item(business, 0, minutes = 10)
        val c = item(business, 1, minutes = 10)
        val first = flagged(rows(listOf(a, b, c)), emptyMap(), null)

        val d = item(business, 2, minutes = 15)
        val second = flagged(rows(listOf(a.copy(sectionId = business.id, position = 0), b.copy(position = 1), d)), emptyMap(), first)

        assertThat(second.items.single { it.itemId == d.id }.changes).containsExactly(ItemFlag.ADDED)
        assertThat(second.items.single { it.itemId == a.id }.changes).containsExactly(ItemFlag.MOVED)
        val removed = second.items.single { it.itemId == c.id }
        assertThat(removed.removed).isTrue()
        assertThat(removed.number to removed.previousNumber).isEqualTo(null to "3")
        assertThat(removed.changes).containsExactly(ItemFlag.REMOVED)
        assertThat(second.body().items.map { it.itemId }).containsExactly(a.id, b.id, d.id)

        val third =
            flagged(
                rows(listOf(a.copy(sectionId = business.id, position = 0), b.copy(position = 1, parentId = a.id))),
                emptyMap(),
                second.body(),
            )
        assertThat(third.items.single { it.itemId == b.id }.changes).contains(ItemFlag.MOVED)
    }

    @Test
    fun `a retitle, a new duration and paper changes are flagged on the item and the paper`() {
        val credit = item(business, 0, minutes = 30)
        val kept = paper(credit, 0)
        val replaced = paper(credit, 1)
        val dropped = paper(credit, 2)
        val versions = versions(kept, replaced, dropped)
        val first = flagged(rows(listOf(credit), listOf(kept, replaced, dropped)), versions, null)

        val added = paper(credit, 2)
        val newVersions = versions + versions(added) + (replaced.fileId to version(replaced.fileId))
        val second =
            flagged(
                rows(listOf(credit.copy(title = "Credit facility, revised", minutes = 45)), listOf(kept, replaced, added)),
                newVersions,
                first,
            )

        assertThat(second.items.single().changes).containsExactlyInAnyOrder(
            ItemFlag.RETITLED,
            ItemFlag.TIME_CHANGED,
            ItemFlag.PAPER_ADDED,
            ItemFlag.PAPER_REPLACED,
            ItemFlag.PAPER_REMOVED,
        )
        assertThat(second.papers.associate { it.paperId to it.changes }).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                kept.id to emptySet(),
                replaced.id to setOf(PaperFlag.REPLACED),
                added.id to setOf(PaperFlag.ADDED),
                dropped.id to setOf(PaperFlag.REMOVED),
            ),
        )
        assertThat(second.papers.single { it.paperId == dropped.id }.removed).isTrue()
    }

    @Test
    fun `a restriction change is never a flag, yet it is something to publish, while the same agenda is not`() {
        val credit = item(business, 0, minutes = 30)
        val memo = paper(credit, 0)
        val versions = versions(memo)
        val first = flagged(rows(listOf(credit), listOf(memo)), versions, null)

        val same = snapshot(rows(listOf(credit), listOf(memo)), versions, first)
        assertThat(same.sameAs(first)).isTrue()

        val someone = UUID.randomUUID()
        val restricted = rows(listOf(credit.copy(restrictedTo = listOf(someone))), listOf(memo.copy(restrictedTo = listOf(someone))))
        val next = snapshot(restricted, versions, first)
        assertThat(next.sameAs(first)).isFalse()
        val second = RevisionRules.flagged(next, first)
        assertThat(second.items.single().changes).isEmpty()
        assertThat(second.papers.single().changes).isEmpty()
        assertThat(second.items.single().restrictedTo).containsExactly(someone)
    }

    @Test
    fun `lateness is read when first published and then carried, without flagging it again`() {
        val standing = item(opening, 0, minutes = 5)
        val credit = item(business, 0, minutes = 30)
        val agreed = item(business, 1, minutes = 10, late = Lateness.CHAIR_AGREED)
        val tabled = item(business, 2, late = Lateness.TABLED)
        val memo = paper(credit, 0)
        val versions = versions(memo)

        // First published after the deadline: an item with papers is late; one without (a standing item) never is.
        val first = flagged(rows(listOf(standing, credit), listOf(memo)), versions, null, afterDeadline = true)
        assertThat(first.items.map { it.late }).containsExactly(PublishedLate.NONE, PublishedLate.LATE)
        assertThat(first.papers.single().late).isEqualTo(PublishedLate.LATE)

        val second = flagged(rows(listOf(standing, credit, agreed, tabled), listOf(memo)), versions, first, afterDeadline = false)
        val byId = second.items.associateBy { it.itemId }
        assertThat(byId.getValue(credit.id).late to byId.getValue(credit.id).changes).isEqualTo(PublishedLate.LATE to emptySet<ItemFlag>())
        assertThat(byId.getValue(agreed.id).changes).containsExactlyInAnyOrder(ItemFlag.ADDED, ItemFlag.LATE)
        assertThat(byId.getValue(tabled.id).late).isEqualTo(PublishedLate.TABLED)
        assertThat(byId.getValue(tabled.id).changes).containsExactlyInAnyOrder(ItemFlag.ADDED, ItemFlag.TABLED)
    }

    @Test
    fun `the longest run kept in order decides what moved`() {
        assertThat(RevisionRules.longestIncreasing(listOf(0, 1, 4, 2, 3))).containsExactlyInAnyOrder(0, 1, 3, 4)
        assertThat(RevisionRules.longestIncreasing(listOf(1, 2, 0, 3))).containsExactlyInAnyOrder(0, 1, 3)
        assertThat(RevisionRules.longestIncreasing(emptyList())).isEmpty()
        assertThat(RevisionRules.longestIncreasing(listOf(3, 2, 1))).hasSize(1)
    }

    private fun snapshot(
        rows: AgendaRows,
        versions: Map<UUID, FileVersionInfo>,
        previous: Snapshot?,
        afterDeadline: Boolean = false,
    ) = RevisionRules.snapshot(SnapshotInput(rows, start, end, versions, afterDeadline), previous?.body())

    private fun flagged(
        rows: AgendaRows,
        versions: Map<UUID, FileVersionInfo>,
        previous: Snapshot?,
        afterDeadline: Boolean = false,
    ) = RevisionRules.flagged(snapshot(rows, versions, previous, afterDeadline), previous?.body())

    private fun rows(
        items: List<ItemRow>,
        papers: List<PaperRow> = emptyList(),
    ) = AgendaRows(
        AgendaRow(meeting, AgendaOrigin.TEMPLATE, null, 0, false, 0),
        listOf(opening, business),
        items,
        papers,
        emptyMap(),
    )

    private fun versions(vararg papers: PaperRow): Map<UUID, FileVersionInfo> = papers.associate { it.fileId to version(it.fileId) }

    private fun version(fileId: UUID) =
        FileVersionInfo(
            id = UUID.randomUUID(),
            fileId = fileId,
            number = 1,
            name = "paper.pdf",
            mediaType = "application/pdf",
            sizeBytes = 1024,
            sha256 = "00",
            pdfPages = PAGES,
            uploadedBy = null,
            uploadedAt = start,
            retainUntil = null,
        )

    private fun section(
        position: Int,
        title: String,
    ) = SectionRow(
        UUID.randomUUID(),
        meeting,
        position,
        title,
        AgendaSectionKind.STANDARD,
        membersOnly = false,
        receivesSubmissions = false,
    )

    private fun item(
        section: SectionRow,
        position: Int,
        minutes: Int? = null,
        parent: ItemRow? = null,
        late: Lateness = Lateness.NONE,
    ) = ItemRow(
        id = UUID.randomUUID(),
        meetingId = meeting,
        sectionId = section.id,
        parentId = parent?.id,
        position = position,
        kind = ItemKind.ITEM,
        standingKind = null,
        submissionId = null,
        memoId = null,
        title = "Item $position",
        description = null,
        purpose = SubmissionPurpose.DECISION,
        resolutionSought = null,
        minutes = minutes,
        presenters = emptyList(),
        restrictedTo = null,
        titleHidden = false,
        classificationId = LEVEL,
        late = late,
        addedAfterRevision = 0,
    )

    private fun paper(
        item: ItemRow,
        position: Int,
        late: Lateness = Lateness.NONE,
    ) = PaperRow(
        id = UUID.randomUUID(),
        meetingId = meeting,
        itemId = item.id,
        position = position,
        source = PaperSource.UPLOAD,
        fileId = UUID.randomUUID(),
        title = "Paper $position",
        restrictedTo = null,
        classificationId = LEVEL,
        late = late,
    )

    private companion object {
        const val PAGES = 12
        val LEVEL: UUID = UUID.randomUUID()
    }
}
