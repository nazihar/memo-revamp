package com.memo.meeting.internal

import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import com.memo.forum.AgendaLimits
import com.memo.meeting.MeetingStatus
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** What an agenda row is (design E03 §1.2). */
enum class ItemKind { SUBMISSION, STANDING, ITEM, BREAK }

/** Lateness of an item or paper (design E03 §1.4). */
enum class Lateness { NONE, LATE, AWAITING_CHAIR, CHAIR_AGREED, TABLED }

/** Why an item left the agenda. */
enum class RemovalReason { REMOVED, SUBMISSION_CHANGED, CHAIR_DECLINED, MEETING_CANCELLED }

/** Where a paper's file comes from (ADR-0061). */
enum class PaperSource { MEMO_DOCUMENT, MEMO_ATTACHMENT, MEMO_RECORD, UPLOAD }

/**
 * What the draft accepts now, following the meeting's status (design E03 §1.2, `FROZEN`): any change before the meeting
 * starts; only tabled items and papers while it is in progress; nothing once it is held or cancelled.
 */
enum class AgendaChanges {
    ANY,
    TABLED_ONLY,
    NONE,
    ;

    companion object {
        fun of(status: MeetingStatus): AgendaChanges =
            when (status) {
                MeetingStatus.DRAFT, MeetingStatus.SCHEDULED -> ANY
                MeetingStatus.IN_PROGRESS -> TABLED_ONLY
                else -> NONE
            }
    }
}

/** The computed times of a draft (design E03 §1.2): each top-level item's start, section totals, the overrun. */
data class AgendaTimes(
    val starts: Map<UUID, Instant>,
    val sectionMinutes: Map<UUID, Int>,
    val totalMinutes: Int,
    /** When the agenda as planned ends. */
    val endsAt: Instant,
    /** The planned end is after the meeting's end. */
    val overrun: Boolean,
)

/** The agenda's pure rules (ADR-0060, design E03 §1.2): changes by status, numbering, times, positions, levels, caps. */
object AgendaRules {
    const val MAX_PAPERS = 20
    const val MAX_PRESENTERS = 5
    const val MAX_ATTENDEES = 30
    const val MAX_RESTRICTED = 100
    const val MAX_DESCRIPTION = 2000
    const val MAX_NAME = 200

    /** Refuses a change the meeting's status no longer allows (409 `FROZEN`); [tabled] marks an addition. */
    fun requireChange(
        status: MeetingStatus,
        tabled: Boolean = false,
    ) {
        val changes = AgendaChanges.of(status)
        refuseIf(changes == AgendaChanges.NONE, "FROZEN") { "The meeting is ${status.name.lowercase()}: its agenda no longer changes." }
        refuseIf(changes == AgendaChanges.TABLED_ONLY && !tabled, "FROZEN") {
            "The meeting is in progress: only items and papers tabled at the meeting can be added."
        }
    }

    /** Whether the item counts in numbers and times: breaks have no number, items waiting for the chair neither. */
    private fun numbered(item: ItemRow): Boolean = item.kind != ItemKind.BREAK && item.late != Lateness.AWAITING_CHAIR

    /** Top-level items of [sections] in agenda order, each with its sub-items in order. */
    fun ordered(
        sections: List<SectionRow>,
        items: List<ItemRow>,
    ): List<Pair<ItemRow, List<ItemRow>>> {
        val children = items.filter { it.parentId != null }.groupBy { it.parentId }
        return sections.sortedBy { it.position }.flatMap { section ->
            items
                .filter { it.sectionId == section.id && it.parentId == null }
                .sortedBy { it.position }
                .map { it to children[it.id].orEmpty().sortedBy { c -> c.position } }
        }
    }

    /**
     * Numbers as published (design E03 §1.2): 1, 2, 3 … through the whole agenda, breaks and items waiting for the chair
     * skipped; sub-items `n.1`, `n.2`. Sections are unnumbered.
     */
    fun numbers(
        sections: List<SectionRow>,
        items: List<ItemRow>,
    ): Map<UUID, String> {
        val numbers = mutableMapOf<UUID, String>()
        ordered(sections, items).filter { numbered(it.first) }.forEachIndexed { i, (item, subs) ->
            val n = i + 1
            numbers[item.id] = "$n"
            subs.filter { it.late != Lateness.AWAITING_CHAIR }.forEachIndexed { j, sub -> numbers[sub.id] = "$n.${j + 1}" }
        }
        return numbers
    }

    /** Starts and totals from the meeting's [start] (design E03 §1.2): sub-items and items waiting for the chair take no time. */
    fun times(
        start: Instant,
        end: Instant,
        sections: List<SectionRow>,
        items: List<ItemRow>,
    ): AgendaTimes {
        val starts = mutableMapOf<UUID, Instant>()
        val perSection = sections.associate { it.id to 0 }.toMutableMap()
        var at = start
        ordered(sections, items).map { it.first }.filter { it.late != Lateness.AWAITING_CHAIR }.forEach { item ->
            val minutes = item.minutes ?: 0
            starts[item.id] = at
            at = at.plus(Duration.ofMinutes(minutes.toLong()))
            perSection[item.sectionId] = perSection.getValue(item.sectionId) + minutes
        }
        val total = perSection.values.sum()
        return AgendaTimes(starts, perSection, total, at, at.isAfter(end))
    }

    /** [ids] with [id] taken out and put at [to] (clamped to the list): the new order of one parent's rows. */
    fun reorder(
        ids: List<UUID>,
        id: UUID,
        to: Int?,
    ): List<UUID> {
        val rest = ids - id
        val at = (to ?: rest.size).coerceIn(0, rest.size)
        return rest.subList(0, at) + id + rest.subList(at, rest.size)
    }

    /**
     * `ONE_LEVEL` (design E03 §1.2): [item] may go under [parent] only if the parent is a top-level item that is not a
     * break, the item is not a break, and the item has no sub-items of its own.
     */
    fun requireOneLevel(
        item: ItemRow,
        parent: ItemRow?,
        hasChildren: Boolean,
    ) {
        if (parent == null) return
        refuseIf(
            item.kind == ItemKind.BREAK || parent.kind == ItemKind.BREAK,
            "ONE_LEVEL",
        ) { "A break has no sub-items and is no sub-item." }
        refuseIf(parent.parentId != null || hasChildren || parent.id == item.id, "ONE_LEVEL") {
            "Items have one level of sub-items: a sub-item has no sub-items of its own."
        }
    }

    /** The caps of design E03 §1.2 (422 `TOO_MANY`): a section or item more than the agenda holds. */
    fun capProblem(
        sections: Int,
        items: Int,
    ): FieldProblem? =
        when {
            sections > AgendaLimits.MAX_SECTIONS -> {
                FieldProblem("sectionId", "TOO_MANY", "An agenda has at most ${AgendaLimits.MAX_SECTIONS} sections.")
            }

            items > AgendaLimits.MAX_ITEMS -> {
                FieldProblem("kind", "TOO_MANY", "An agenda has at most ${AgendaLimits.MAX_ITEMS} items and sub-items.")
            }

            else -> {
                null
            }
        }

    /** A required text of 1 to [max] characters, trimmed. */
    fun title(
        field: String,
        text: String?,
        max: Int,
    ): Pair<String, FieldProblem?> {
        val clean = text?.trim().orEmpty()
        val problem = FieldProblem(field, "LENGTH", "1 to $max characters.").takeIf { clean.isEmpty() || clean.length > max }
        return clean to problem
    }

    /** An optional text of at most [max] characters, trimmed; blank is none. */
    fun optional(
        field: String,
        text: String?,
        max: Int,
    ): Pair<String?, FieldProblem?> {
        val clean = text?.trim()?.ifEmpty { null }
        return clean to FieldProblem(field, "LENGTH", "At most $max characters.").takeIf { (clean?.length ?: 0) > max }
    }
}
