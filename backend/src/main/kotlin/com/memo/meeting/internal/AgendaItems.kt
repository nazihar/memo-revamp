package com.memo.meeting.internal

import com.memo.common.id.IdGenerator
import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import com.memo.forum.AgendaLimits
import com.memo.forum.StandingKind
import com.memo.forum.SubmissionPurpose
import com.memo.meeting.MeetingStatus
import com.memo.memo.MemoPapers
import org.springframework.stereotype.Service
import java.util.UUID

/** A new item (design E03 §3): its kind and place; for a SUBMISSION, the submission from the tray. */
data class ItemInput(
    val kind: ItemKind,
    val sectionId: UUID? = null,
    val parentId: UUID? = null,
    val position: Int? = null,
    /** Required except for breaks ("Break") and submissions (the memo's subject). */
    val title: String? = null,
    /** STANDING items only. */
    val standingKind: StandingKind? = null,
    /** SUBMISSION items only. */
    val submissionId: UUID? = null,
    val purpose: SubmissionPurpose? = null,
    val minutes: Int? = null,
)

/** An item's editable fields, as a whole (the side sheet): what is not sent is cleared. */
data class ItemChange(
    val title: String?,
    val description: String? = null,
    val purpose: SubmissionPurpose? = null,
    val resolutionSought: String? = null,
    val minutes: Int? = null,
    val presenters: List<Presenter> = emptyList(),
    /** In attendance for this item only; user presenters are added. */
    val attendees: List<UUID> = emptyList(),
    /** "Only these people"; null: no restriction; never empty. */
    val restrictedTo: List<UUID>? = null,
    val titleHidden: Boolean = false,
    /** Null: keep the item's level. */
    val classificationId: UUID? = null,
)

/**
 * Items, sub-items and breaks of the draft (design E03 §1.2): added (tabled while the meeting is in progress), edited
 * with their presenters, attendees, restriction, hidden title and level, and removed. A SUBMISSION item is placed through
 * [AgendaPlacing].
 */
@Service
class AgendaItems(
    private val guard: AgendaGuard,
    private val writes: AgendaWrites,
    private val placing: AgendaPlacing,
    private val items: ItemStore,
    private val papers: PaperStore,
    private val people: AgendaPeople,
    private val memoPapers: MemoPapers,
    private val ids: IdGenerator,
) {
    fun add(
        meetingId: UUID,
        version: Long,
        input: ItemInput,
    ): UUID {
        val edit = guard.edit(meetingId, version, tabled = true, submissions = listOfNotNull(input.submissionId))
        val id = if (input.kind == ItemKind.SUBMISSION) placing.place(edit, input) else addOwn(edit, input)
        guard.done(meetingId)
        return id
    }

    fun update(
        meetingId: UUID,
        itemId: UUID,
        version: Long,
        change: ItemChange,
    ) {
        val edit = guard.edit(meetingId, version)
        val before = edit.item(itemId)
        val floor = before.memoId?.let { memoPapers.pack(it) }?.let { people.higher(edit.meeting.classificationId, it.classificationId) }
        val level = change.classificationId ?: before.classificationId
        val (title, titleProblem) = AgendaRules.title("title", change.title, AgendaLimits.MAX_ITEM_TITLE)
        val (description, descriptionProblem) = AgendaRules.optional("description", change.description, AgendaRules.MAX_DESCRIPTION)
        val (resolution, resolutionProblem) = AgendaRules.optional("resolutionSought", change.resolutionSought, AgendaRules.MAX_DESCRIPTION)
        val (restricted, restrictionProblems) = people.restriction("restrictedTo", change.restrictedTo, meetingId, level)
        val presenters = change.presenters.map { it.copy(name = it.name?.trim()) }
        requireNoProblems(
            listOfNotNull(
                titleProblem,
                descriptionProblem,
                resolutionProblem,
                minutesProblem(change.minutes, before.parentId != null),
                people.level("classificationId", level, floor ?: edit.meeting.classificationId),
            ) + restrictionProblems + people.people(change.attendees, presenters, level),
        )
        val after =
            before.copy(
                title = title,
                description = description,
                purpose = if (before.kind == ItemKind.BREAK) null else change.purpose ?: before.purpose ?: SubmissionPurpose.INFORMATION,
                resolutionSought = resolution,
                minutes = change.minutes,
                presenters = presenters,
                restrictedTo = restricted,
                titleHidden = change.titleHidden,
                classificationId = level,
            )
        items.update(after)
        val attendees = change.attendees.toSet() + presenters.mapNotNull { it.userId }
        items.setAttendees(itemId, attendees)
        // A paper is never below its item: raising the item raises them.
        val rank = people.rank(level) ?: 0
        edit.rows.papers
            .filter { it.itemId == itemId && (people.rank(it.classificationId) ?: 0) < rank }
            .forEach { papers.update(it.copy(classificationId = level)) }
        writes.record(
            "meeting.agenda.item_updated",
            meetingId,
            AgendaWrites.audited(before) + (
                "attendees" to
                    edit.rows.attendees[itemId]
                        .orEmpty()
                        .map { it.toString() }
                        .sorted()
            ),
            AgendaWrites.audited(after) + ("attendees" to attendees.map { it.toString() }.sorted()),
        )
        guard.done(meetingId)
    }

    /** Takes an item off the draft: its sub-items take its place; a submission goes back to the tray, still accepted. */
    fun remove(
        meetingId: UUID,
        itemId: UUID,
        version: Long,
    ) {
        // The submission of a SUBMISSION item is unplaced: its row is locked before the agenda (design E03 §1.8).
        val edit = guard.edit(meetingId, version, submissions = listOfNotNull(items.find(itemId)?.submissionId))
        writes.removeItem(edit, edit.item(itemId), RemovalReason.REMOVED)
        guard.done(meetingId)
    }

    private fun addOwn(
        edit: AgendaEdit,
        input: ItemInput,
    ): UUID {
        val parent = input.parentId?.let(edit::item)
        val sectionId = writes.target(edit, input.sectionId, parent)
        val breakTitle = BREAK_TITLE.takeIf { input.kind == ItemKind.BREAK && input.title.isNullOrBlank() }
        val (title, titleProblem) = AgendaRules.title("title", breakTitle ?: input.title, AgendaLimits.MAX_ITEM_TITLE)
        val standing = input.standingKind
        requireNoProblems(
            listOfNotNull(
                titleProblem,
                FieldProblem("standingKind", "REQUIRED", "Say which standing item.").takeIf {
                    input.kind == ItemKind.STANDING &&
                        standing == null
                },
                FieldProblem("standingKind", "FORMAT", "Only a standing item has a standing kind.")
                    .takeIf { input.kind != ItemKind.STANDING && standing != null },
                FieldProblem("standingKind", "duplicate", "This standing item is already on the agenda.")
                    .takeIf { standing != null && edit.rows.items.any { it.standingKind == standing } },
                minutesProblem(input.minutes, parent != null),
                AgendaRules.capProblem(edit.rows.sections.size, edit.rows.items.size + 1),
            ),
        )
        val row =
            ItemRow(
                id = ids.next(),
                meetingId = edit.meeting.id,
                sectionId = sectionId,
                parentId = parent?.id,
                position = 0,
                kind = input.kind,
                standingKind = standing.takeIf { input.kind == ItemKind.STANDING },
                submissionId = null,
                memoId = null,
                title = title,
                description = null,
                purpose = if (input.kind == ItemKind.BREAK) null else input.purpose ?: SubmissionPurpose.INFORMATION,
                resolutionSought = null,
                minutes = input.minutes,
                presenters = emptyList(),
                restrictedTo = null,
                titleHidden = false,
                classificationId = edit.meeting.classificationId,
                late = if (edit.meeting.status == MeetingStatus.IN_PROGRESS) Lateness.TABLED else Lateness.NONE,
                addedAfterRevision = edit.agenda.currentRevision,
            )
        AgendaRules.requireOneLevel(row, parent, hasChildren = false)
        writes.insert(edit, row, input.position)
        writes.record("meeting.agenda.item_added", edit.meeting.id, after = AgendaWrites.audited(row))
        return row.id
    }

    private fun minutesProblem(
        minutes: Int?,
        subItem: Boolean,
    ): FieldProblem? =
        when {
            minutes == null -> null
            subItem -> FieldProblem("minutes", "ONE_LEVEL", "Sub-items take the time of their item.")
            minutes !in 0..AgendaLimits.MAX_MINUTES -> FieldProblem("minutes", "RANGE", "0 to ${AgendaLimits.MAX_MINUTES} minutes.")
            else -> null
        }

    private companion object {
        const val BREAK_TITLE = "Break"
    }
}

/** One move (design E03 §3): an item ([itemId]) to a section, under an item or to a position; or a section ([sectionId]). */
data class MoveInput(
    val itemId: UUID? = null,
    val sectionId: UUID? = null,
    /** The item's new section (top level); null: its section, or its parent's. */
    val toSectionId: UUID? = null,
    /** The item becomes a sub-item of this item. */
    val toParentId: UUID? = null,
    /** Among its new siblings, from 0; past the end: last. */
    val toPosition: Int,
)

/**
 * Moves as commands (design E03 §1.2, WCAG 2.2 SC 2.5.7): an item to another position, section or parent (`ONE_LEVEL`
 * kept), or a section to another position. Positions stay dense; a top-level item takes its sub-items along.
 */
@Service
class AgendaMoves(
    private val guard: AgendaGuard,
    private val writes: AgendaWrites,
) {
    fun move(
        meetingId: UUID,
        version: Long,
        input: MoveInput,
    ) {
        val edit = guard.edit(meetingId, version)
        val itemId = input.itemId
        val sectionId = input.sectionId
        requireNoProblems(
            listOfNotNull(
                FieldProblem("itemId", "REQUIRED", "Move an item or a section.").takeIf { (itemId == null) == (sectionId == null) },
            ),
        )
        if (sectionId != null) moveSection(edit, sectionId, input.toPosition) else moveItem(edit, checkNotNull(itemId), input)
        guard.done(meetingId)
    }

    private fun moveSection(
        edit: AgendaEdit,
        sectionId: UUID,
        to: Int,
    ) {
        val section = edit.section(sectionId)
        val order =
            AgendaRules.reorder(
                edit.rows.sections
                    .sortedBy { it.position }
                    .map { it.id },
                section.id,
                to,
            )
        writes.sectionsInOrder(edit, order)
        writes.record(
            "meeting.agenda.section_moved",
            edit.meeting.id,
            mapOf("sectionId" to section.id.toString(), "position" to section.position),
            mapOf("sectionId" to section.id.toString(), "position" to order.indexOf(section.id)),
        )
    }

    private fun moveItem(
        edit: AgendaEdit,
        itemId: UUID,
        input: MoveInput,
    ) {
        val item = edit.item(itemId)
        val parent = input.toParentId?.let(edit::item)
        AgendaRules.requireOneLevel(item, parent, hasChildren = edit.children(item.id).isNotEmpty())
        refuseIf(parent != null && input.toSectionId != null && input.toSectionId != parent.sectionId, "ONE_LEVEL") {
            "A sub-item sits in its item's section."
        }
        val section = parent?.sectionId ?: input.toSectionId?.let { edit.section(it).id } ?: item.sectionId
        val rows =
            edit.rows.items
                .associateBy { it.id }
                .toMutableMap()
        writes.layout(rows, item.sectionId, item.parentId, writes.siblings(rows, item.sectionId, item.parentId, except = item.id))
        rows[item.id] = item.copy(sectionId = section, parentId = parent?.id, minutes = if (parent != null) null else item.minutes)
        val order = AgendaRules.reorder(writes.siblings(rows, section, parent?.id, except = item.id) + item.id, item.id, input.toPosition)
        writes.layout(rows, section, parent?.id, order)
        if (parent == null) edit.children(item.id).forEach { rows[it.id] = rows.getValue(it.id).copy(sectionId = section) }
        writes.items(edit.rows.items, rows.values)
        val moved = rows.getValue(item.id)
        writes.record(
            "meeting.agenda.item_moved",
            edit.meeting.id,
            place(item),
            place(moved),
        )
    }

    private fun place(item: ItemRow): Map<String, Any?> =
        mapOf(
            "itemId" to item.id.toString(),
            "sectionId" to item.sectionId.toString(),
            "parentId" to item.parentId?.toString(),
            "position" to item.position,
            "minutes" to item.minutes,
        )
}
