package com.memo.meeting.internal

import com.memo.audit.AuditLog
import com.memo.common.id.IdGenerator
import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import com.memo.forum.AgendaLimits
import com.memo.forum.AgendaPolicy
import com.memo.forum.AgendaSectionKind
import com.memo.forum.ForumDirectory
import com.memo.forum.ForumPolicies
import com.memo.forum.SubmissionPlacements
import com.memo.identity.UserSummaries
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * The writes every builder change shares (design E03 §1.2): rows laid out again after a change (dense positions per
 * parent), an item taken off the agenda (its sub-items take its place, its papers go with it, a submission returns to
 * the tray), and the audit of each change on the meeting.
 */
@Component
class AgendaWrites(
    private val sections: SectionStore,
    private val items: ItemStore,
    private val papers: PaperStore,
    private val placements: SubmissionPlacements,
    private val audit: AuditLog,
) {
    /** Writes the sections in [order] with dense positions, where a position changed. */
    fun sectionsInOrder(
        edit: AgendaEdit,
        order: List<UUID>,
    ) {
        order.forEachIndexed { i, id ->
            val row = edit.section(id)
            if (row.position != i) sections.update(row.copy(position = i))
        }
    }

    /**
     * Writes [after] (every live item of the agenda, laid out anew) where it differs from [before]. Callers give each
     * changed parent's rows dense positions with [layout].
     */
    fun items(
        before: List<ItemRow>,
        after: Collection<ItemRow>,
    ) {
        val was = before.associateBy { it.id }
        after.filter { it != was[it.id] }.forEach { items.update(it) }
    }

    /** [rows] with the rows sharing [parentId] (or, at the top, [sectionId]) at dense positions in [order]. */
    fun layout(
        rows: MutableMap<UUID, ItemRow>,
        sectionId: UUID,
        parentId: UUID?,
        order: List<UUID>,
    ) {
        order.forEachIndexed { i, id ->
            rows[id] = rows.getValue(id).copy(sectionId = sectionId, parentId = parentId, position = i)
        }
    }

    /** The ids of the rows under [parentId] (or at the top of [sectionId]) in [rows], in order, without [except]. */
    fun siblings(
        rows: Map<UUID, ItemRow>,
        sectionId: UUID,
        parentId: UUID?,
        except: UUID? = null,
    ): List<UUID> =
        rows.values
            .filter { it.id != except && it.parentId == parentId && (parentId != null || it.sectionId == sectionId) }
            .sortedWith(compareBy({ it.position }, { it.id }))
            .map { it.id }

    /** Inserts [row] among its siblings at [position] (the end when null), laying the siblings out again. */
    fun insert(
        edit: AgendaEdit,
        row: ItemRow,
        position: Int?,
    ) {
        val rows =
            edit.rows.items
                .associateBy { it.id }
                .toMutableMap()
        val order = AgendaRules.reorder(siblings(rows, row.sectionId, row.parentId) + row.id, row.id, position)
        val placed = row.copy(position = order.indexOf(row.id))
        items.insert(placed, actorOrNull())
        rows[row.id] = placed
        layout(rows, row.sectionId, row.parentId, order)
        items(edit.rows.items + placed, rows.values)
    }

    /**
     * Where a new item goes: under [parent] (in its section), else in [sectionId], else in the section new items land in,
     * else in the last section; 422 at `sectionId` when the agenda has no section.
     */
    fun target(
        edit: AgendaEdit,
        sectionId: UUID?,
        parent: ItemRow?,
    ): UUID {
        val section =
            parent?.sectionId
                ?: sectionId?.let { edit.section(it).id }
                ?: edit.rows.sections
                    .firstOrNull { it.receivesSubmissions }
                    ?.id
                ?: edit.rows.sections
                    .maxByOrNull { it.position }
                    ?.id
        requireNoProblems(listOfNotNull(FieldProblem("sectionId", "REQUIRED", "Add a section first.").takeIf { section == null }))
        return checkNotNull(section)
    }

    /**
     * Takes [item] off the agenda for [reason]: its sub-items take its place, its papers and attendees go with it, and a
     * submission returns to the tray (the forum clears its placement, audited `forum.submission.unplaced`).
     */
    fun removeItem(
        edit: AgendaEdit,
        item: ItemRow,
        reason: RemovalReason,
    ) {
        val by = actorOrNull()
        if (!items.remove(item.id, by, reason)) return
        val rows =
            edit.rows.items
                .associateBy { it.id }
                .toMutableMap()
        rows.remove(item.id)
        val children = siblings(rows, item.sectionId, item.id)
        val around = siblings(rows, item.sectionId, item.parentId)
        if (item.parentId == null && children.isNotEmpty()) {
            val at = around.indexOfFirst { rows.getValue(it).position > item.position }.let { if (it < 0) around.size else it }
            layout(rows, item.sectionId, null, around.subList(0, at) + children + around.subList(at, around.size))
        } else {
            layout(rows, item.sectionId, item.parentId, around)
        }
        items(edit.rows.items - item, rows.values)
        papers.remove(
            edit.rows.papers
                .filter { it.itemId == item.id }
                .map { it.id },
            by,
        )
        item.submissionId?.let { placements.unplace(it) }
        record(
            "meeting.agenda.item_removed",
            edit.meeting.id,
            before = audited(item),
            after = mapOf("reason" to reason.name, "subItemsPromoted" to children.size),
        )
    }

    /** Takes [paper] out of its item, the item's other papers keeping dense positions. */
    fun removePaper(
        edit: AgendaEdit,
        paper: PaperRow,
    ) {
        papers.remove(listOf(paper.id), actorOrNull())
        edit.rows.papers
            .filter { it.itemId == paper.itemId && it.id != paper.id }
            .sortedBy { it.position }
            .forEachIndexed { i, p -> if (p.position != i) papers.update(p.copy(position = i)) }
    }

    fun record(
        action: String,
        meetingId: UUID,
        before: Map<String, Any?>? = null,
        after: Map<String, Any?>? = null,
    ) {
        audit.record(action, MeetingLifecycle.OBJECT_TYPE, meetingId.toString(), before = before, after = after)
    }

    companion object {
        fun audited(item: ItemRow): Map<String, Any?> =
            mapOf(
                "itemId" to item.id.toString(),
                "sectionId" to item.sectionId.toString(),
                "parentId" to item.parentId?.toString(),
                "position" to item.position,
                "kind" to item.kind.name,
                "standingKind" to item.standingKind?.name,
                "submissionId" to item.submissionId?.toString(),
                "title" to item.title,
                "purpose" to item.purpose?.name,
                "minutes" to item.minutes,
                "presenters" to item.presenters.map { it.userId?.toString() ?: it.name },
                "restrictedTo" to item.restrictedTo?.map { it.toString() },
                "titleHidden" to item.titleHidden,
                "classificationId" to item.classificationId.toString(),
                "late" to item.late.name,
            )

        fun audited(section: SectionRow): Map<String, Any?> =
            mapOf(
                "sectionId" to section.id.toString(),
                "position" to section.position,
                "title" to section.title,
                "kind" to section.kind.name,
                "membersOnly" to section.membersOnly,
                "receivesSubmissions" to section.receivesSubmissions,
            )
    }
}

/** What a copy of an earlier meeting left behind (design E03 §1.7): restrictions naming people not on this meeting, items. */
data class CopyReport(
    /** People dropped from restrictions: not in this meeting's audience, or not cleared for the item. */
    val dropped: Set<UUID>,
    /** Titles of items left out because their restriction would have named nobody. */
    val skipped: List<String>,
)

/**
 * Starting a meeting's agenda (design E03 §1.7): from its pinned type version's standard agenda, or from an earlier
 * meeting of a committee the actor is an officer of. A copy keeps the structure, times, presenters, attendees and
 * restrictions narrowed to the new audience; never submissions, papers, lateness, agreement or requests.
 */
@Service
class AgendaStart(
    private val guard: MeetingGuard,
    private val access: MeetingAccess,
    private val meetings: MeetingStore,
    private val agendaGuard: AgendaGuard,
    private val agendas: AgendaStore,
    private val sections: SectionStore,
    private val items: ItemStore,
    private val people: AgendaPeople,
    private val directory: ForumDirectory,
    private val policies: ForumPolicies,
    private val ids: IdGenerator,
    private val writes: AgendaWrites,
) {
    fun start(
        meetingId: UUID,
        from: AgendaOrigin,
        sourceId: UUID?,
    ): CopyReport? {
        val meeting = guard.requireManaged(meetingId)
        AgendaRules.requireChange(meeting.status)
        val source = if (from == AgendaOrigin.MEETING) source(meeting, sourceId) else null
        refuseIf(!agendas.insert(meeting.id, from, source?.agenda?.meetingId, actorOrNull()), "AGENDA_STARTED") {
            "This meeting's agenda has already been started."
        }
        checkNotNull(agendas.lock(meeting.id))
        val report = if (source == null) fromTemplate(meeting) else copy(meeting, source)
        writes.record(
            "meeting.agenda.started",
            meeting.id,
            after =
                mapOf(
                    "from" to from.name,
                    "sourceMeetingId" to source?.agenda?.meetingId?.toString(),
                    "sections" to sections.live(meeting.id).size,
                    "items" to items.live(meeting.id).size,
                    "droppedRestrictions" to report?.dropped?.map { it.toString() },
                    "skipped" to report?.skipped,
                ),
        )
        agendaGuard.done(meeting.id)
        return report
    }

    /** The standard agenda [meeting] starts from: its pinned type version's (the defaults when it has none). */
    fun template(meeting: MeetingRow): AgendaPolicy {
        val forum = checkNotNull(directory.forum(meeting.forumId)) { "forum ${meeting.forumId}" }
        return policies.version(forum.typeId, meeting.typeVersion)?.agenda ?: AgendaPolicy()
    }

    private fun source(
        meeting: MeetingRow,
        sourceId: UUID?,
    ): AgendaRows {
        val source = sourceId?.let { meetings.find(it) }?.takeIf { access.relation(it) == MeetingRelation.OFFICER }
        val rows = source?.let { agendaGuard.rows(it.id) }
        requireNoProblems(
            listOfNotNull(
                when {
                    source == null -> FieldProblem("meetingId", "unknown", "No such meeting of a committee you run.")
                    source.id == meeting.id -> FieldProblem("meetingId", "SAME_MEETING", "Choose an earlier meeting.")
                    rows == null -> FieldProblem("meetingId", "NO_AGENDA", "That meeting has no agenda to copy.")
                    else -> null
                },
            ),
        )
        return checkNotNull(rows)
    }

    private fun fromTemplate(meeting: MeetingRow): CopyReport? {
        template(meeting).sections.forEachIndexed { i, section ->
            val id = ids.next()
            sections.insert(
                SectionRow(id, meeting.id, i, section.title, section.kind, section.membersOnly, section.receivesSubmissions),
            )
            section.items.forEachIndexed { j, item ->
                val standing = item.standingKind
                items.insert(
                    ItemRow(
                        id = ids.next(),
                        meetingId = meeting.id,
                        sectionId = id,
                        parentId = null,
                        position = j,
                        kind = if (standing != null) ItemKind.STANDING else ItemKind.ITEM,
                        standingKind = standing,
                        submissionId = null,
                        memoId = null,
                        title = item.title,
                        description = null,
                        purpose = item.purpose,
                        resolutionSought = null,
                        minutes = item.minutes,
                        presenters = emptyList(),
                        restrictedTo = null,
                        titleHidden = false,
                        classificationId = meeting.classificationId,
                        late = Lateness.NONE,
                        addedAfterRevision = 0,
                    ),
                    actorOrNull(),
                )
            }
        }
        return null
    }

    private fun copy(
        meeting: MeetingRow,
        source: AgendaRows,
    ): CopyReport {
        val audience = people.audienceOf(meeting.id)
        val dropped = mutableSetOf<UUID>()
        val skipped = mutableListOf<String>()
        source.sections.sortedBy { it.position }.forEachIndexed { i, section ->
            val sectionId = ids.next()
            sections.insert(section.copy(id = sectionId, meetingId = meeting.id, position = i))
            var position = 0
            AgendaRules.ordered(listOf(section), source.items).filter { it.first.kind != ItemKind.SUBMISSION }.forEach { (item, subs) ->
                val copied = copied(meeting, item, audience, source.attendees[item.id].orEmpty(), dropped)
                if (copied == null) {
                    skipped += item.title
                    return@forEach
                }
                val parent = insert(copied.first.copy(sectionId = sectionId, position = position++), copied.second)
                var sub = 0
                subs.filter { it.kind != ItemKind.SUBMISSION }.forEach { child ->
                    val c = copied(meeting, child, audience, source.attendees[child.id].orEmpty(), dropped)
                    if (c == null) skipped += child.title
                    c?.let { insert(it.first.copy(sectionId = sectionId, parentId = parent, position = sub++), it.second) }
                }
            }
        }
        return CopyReport(dropped, skipped)
    }

    /**
     * [item] for [meeting]: never below its level, its restriction narrowed to [audience] and to people cleared, its
     * attendees and user presenters to active people cleared; null when the restriction would name nobody.
     */
    private fun copied(
        meeting: MeetingRow,
        item: ItemRow,
        audience: Set<UUID>,
        attendees: Set<UUID>,
        dropped: MutableSet<UUID>,
    ): Pair<ItemRow, Set<UUID>>? {
        val level = people.higher(item.classificationId, meeting.classificationId)
        val named = item.restrictedTo
        val restricted = named?.let { ids -> (ids.filter { it in audience } - people.uncleared(ids, level)).distinct() }
        if (named != null && restricted != null) dropped += named - restricted.toSet()
        if (restricted != null && restricted.isEmpty()) return null
        val users = attendees + item.presenters.mapNotNull { it.userId }
        val kept = people.active(users) - people.uncleared(users, level)
        val presenters = item.presenters.filter { it.userId == null || it.userId in kept }
        val row =
            item.copy(
                id = ids.next(),
                meetingId = meeting.id,
                parentId = null,
                restrictedTo = restricted,
                presenters = presenters,
                classificationId = level,
                late = Lateness.NONE,
                addedAfterRevision = 0,
            )
        return row to (attendees.filter { it in kept }.toSet() + presenters.mapNotNull { it.userId })
    }

    private fun insert(
        row: ItemRow,
        attendees: Set<UUID>,
    ): UUID {
        items.insert(row, actorOrNull())
        if (attendees.isNotEmpty()) items.setAttendees(row.id, attendees)
        return row.id
    }
}

/** What a secretary sends for a section. */
data class SectionInput(
    val title: String?,
    val kind: AgendaSectionKind = AgendaSectionKind.STANDARD,
    val membersOnly: Boolean = false,
    val receivesSubmissions: Boolean = false,
)

/**
 * Sections of the draft (design E03 §1.2): added, renamed, made a consent or members-only section, chosen as the one new
 * items land in (at most one: choosing another moves the mark), and removed when empty.
 */
@Service
class AgendaSections(
    private val guard: AgendaGuard,
    private val sections: SectionStore,
    private val ids: IdGenerator,
    private val writes: AgendaWrites,
    private val users: UserSummaries,
) {
    fun add(
        meetingId: UUID,
        version: Long,
        input: SectionInput,
        position: Int?,
    ): UUID {
        val edit = guard.edit(meetingId, version)
        val (title, problem) = AgendaRules.title("title", input.title, AgendaLimits.MAX_SECTION_TITLE)
        requireNoProblems(listOfNotNull(problem, AgendaRules.capProblem(edit.rows.sections.size + 1, 0)))
        if (input.receivesSubmissions) unmarkReceiving(edit, null)
        val row =
            SectionRow(ids.next(), meetingId, edit.rows.sections.size, title, input.kind, input.membersOnly, input.receivesSubmissions)
        sections.insert(row)
        writes.sectionsInOrder(
            edit.copy(rows = edit.rows.copy(sections = edit.rows.sections + row)),
            AgendaRules.reorder(edit.rows.sections.map { it.id } + row.id, row.id, position),
        )
        writes.record("meeting.agenda.section_added", meetingId, after = AgendaWrites.audited(row))
        guard.done(meetingId)
        return row.id
    }

    fun update(
        meetingId: UUID,
        sectionId: UUID,
        version: Long,
        input: SectionInput,
    ) {
        val edit = guard.edit(meetingId, version)
        val before = edit.section(sectionId)
        val (title, problem) = AgendaRules.title("title", input.title, AgendaLimits.MAX_SECTION_TITLE)
        requireNoProblems(listOfNotNull(problem))
        if (input.receivesSubmissions) unmarkReceiving(edit, sectionId)
        val after =
            before.copy(title = title, kind = input.kind, membersOnly = input.membersOnly, receivesSubmissions = input.receivesSubmissions)
        sections.update(after)
        writes.record("meeting.agenda.section_updated", meetingId, AgendaWrites.audited(before), AgendaWrites.audited(after))
        guard.done(meetingId)
    }

    /** Removes an empty section (`SECTION_NOT_EMPTY` otherwise): move or remove its items first. */
    fun remove(
        meetingId: UUID,
        sectionId: UUID,
        version: Long,
    ) {
        val edit = guard.edit(meetingId, version)
        val section = edit.section(sectionId)
        refuseIf(edit.rows.items.any { it.sectionId == section.id }, "SECTION_NOT_EMPTY") {
            "Move or remove the section's items first."
        }
        sections.remove(section.id, actorOrNull())
        writes.sectionsInOrder(edit, edit.rows.sections.map { it.id } - section.id)
        writes.record("meeting.agenda.section_removed", meetingId, before = AgendaWrites.audited(section))
        guard.done(meetingId)
    }

    /** Names for the people in a copy report. */
    fun names(ids: Collection<UUID>): Map<UUID, String> = users.names(ids)

    private fun unmarkReceiving(
        edit: AgendaEdit,
        except: UUID?,
    ) {
        edit.rows.sections.filter { it.receivesSubmissions && it.id != except }.forEach {
            sections.update(
                it.copy(receivesSubmissions = false),
            )
        }
    }
}
