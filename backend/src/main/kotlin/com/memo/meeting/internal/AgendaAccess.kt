package com.memo.meeting.internal

import com.memo.common.authz.Clearances
import com.memo.common.authz.ForbiddenException
import com.memo.common.authz.Permission
import com.memo.common.authz.Policy
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import com.memo.forum.ForumRole
import com.memo.forum.SubmissionPlacements
import com.memo.identity.UserStatus
import com.memo.identity.UserSummaries
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * A builder change in progress: the meeting, and its agenda's live rows read under the agenda's row lock. [recused] are
 * the items the actor is recused from (design E04 §1.7): asking for one of them, or for a paper of one, is 403 `RECUSED`,
 * so a recused officer changes nothing of the item, its papers, restriction, place or presence on the agenda.
 */
data class AgendaEdit(
    val meeting: MeetingRow,
    val rows: AgendaRows,
    val recused: Set<UUID> = emptySet(),
) {
    val agenda: AgendaRow get() = rows.agenda

    fun section(id: UUID): SectionRow = rows.sections.firstOrNull { it.id == id } ?: throw MeetingNotFoundException("section")

    fun item(id: UUID): ItemRow {
        val item = rows.items.firstOrNull { it.id == id } ?: throw MeetingNotFoundException("item")
        if (item.id in recused) throw RecusedException()
        return item
    }

    fun paper(id: UUID): PaperRow {
        val paper = rows.papers.firstOrNull { it.id == id } ?: throw MeetingNotFoundException("paper")
        if (paper.itemId in recused) throw RecusedException()
        return paper
    }

    /** The top-level items of [sectionId], in order. */
    fun topLevel(sectionId: UUID): List<ItemRow> =
        rows.items.filter { it.sectionId == sectionId && it.parentId == null }.sortedBy { it.position }

    fun children(itemId: UUID): List<ItemRow> = rows.items.filter { it.parentId == itemId }.sortedBy { it.position }
}

/**
 * Who reads and changes a meeting's draft agenda (design E03 §4): the forum's officers read it (the chair reads only);
 * its secretaries and `forums.manage` change it, each change under the agenda's row lock with its version as If-Match.
 * An invisible meeting is 404; one the actor sees but may not build is 403.
 */
@Component
class AgendaGuard(
    private val guard: MeetingGuard,
    private val access: MeetingAccess,
    private val agendas: AgendaStore,
    private val sections: SectionStore,
    private val items: ItemStore,
    private val papers: PaperStore,
    private val audience: AudienceStore,
    private val meetings: MeetingStore,
    private val placements: SubmissionPlacements,
    private val recusals: RecusalStore,
) {
    /** The meeting, if the actor may read its draft (an officer); 404 when they do not see it, 403 when they only see it. */
    fun requireReader(meetingId: UUID): MeetingRow {
        val (meeting, relation) = guard.requireVisible(meetingId)
        if (relation != MeetingRelation.OFFICER) throw ForbiddenException(Permission.FORUMS_MANAGE)
        return meeting
    }

    /** The live rows of [meetingId]'s agenda (read without a lock); null when it has not been started. */
    fun rows(meetingId: UUID): AgendaRows? = agendas.find(meetingId)?.let { rowsOf(it) }

    /**
     * Opens a change of [meetingId]'s agenda by its secretary: the agenda locked and at [version] (else 412), and the
     * meeting's status allowing it ([AgendaRules.requireChange]; [tabled] for additions while it is in progress).
     *
     * The lock order (design E03 §1.8, as built in T3): the rows of [submissions] the change may place or unplace are
     * locked first, then the agenda, as a triage takes the submission and then, through `ForumSubmissionChanged`, the
     * agenda. The meeting is read again under the agenda's lock, so a change waiting on a cancellation sees it.
     */
    fun edit(
        meetingId: UUID,
        version: Long,
        tabled: Boolean = false,
        submissions: Collection<UUID> = emptyList(),
    ): AgendaEdit {
        val managed = guard.requireManaged(meetingId)
        placements.lock(submissions)
        return locked(managed, version, tabled)
    }

    /**
     * Opens the chair's answer on [meetingId]'s agenda (agreement, a late paper): by the chair in office (the vice-chair
     * when there is none), or [onBehalf] by its secretary or `forums.manage`; 404 when the meeting is invisible, 403 when
     * the actor is neither. Locked and at [version], with [submissions] locked first as for [edit]. An answer on one late
     * item or paper ([forItem]) is open to the chair and the vice-chair here: the caller checks which of them decides for
     * that item (the vice-chair when the chair is recused from it, design E04 §1.7).
     */
    fun answer(
        meetingId: UUID,
        version: Long,
        onBehalf: Boolean,
        submissions: Collection<UUID> = emptyList(),
        forItem: Boolean = false,
    ): AgendaEdit {
        val (meeting, _) = guard.requireVisible(meetingId)
        val allowed =
            when {
                onBehalf -> access.manages(meeting.forumId)
                forItem -> access.role(meeting.forumId).let { it == ForumRole.CHAIR || it == ForumRole.VICE_CHAIR }
                else -> access.chairs(meeting.forumId)
            }
        if (!allowed) throw ForbiddenException(Permission.FORUMS_MANAGE)
        placements.lock(submissions)
        return locked(meeting, version, tabled = true)
    }

    private fun locked(
        meeting: MeetingRow,
        version: Long,
        tabled: Boolean,
    ): AgendaEdit {
        val agenda = agendas.lock(meeting.id)
        refuseIf(agenda == null, "NO_AGENDA") { "Start the agenda first." }
        val locked = checkNotNull(agenda)
        if (locked.version != version) throw ConcurrentModificationException("agenda ${meeting.id}", version)
        val current = meetings.find(meeting.id) ?: throw MeetingNotFoundException()
        AgendaRules.requireChange(current.status, tabled)
        val rows = rowsOf(locked)
        return AgendaEdit(current, rows, recused(rows))
    }

    /** The items of [rows] the actor is recused from, sub-items of such items included (design E04 §1.6). */
    fun recused(rows: AgendaRows): Set<UUID> = actorOrNull()?.let { recusals.recusedItems(rows.agenda.meetingId, it, rows.items) }.orEmpty()

    /** Opens a change made by the system (a submission left, the meeting was cancelled): locked, no version, no officer. */
    fun system(meeting: MeetingRow): AgendaEdit? = agendas.lock(meeting.id)?.let { AgendaEdit(meeting, rowsOf(it)) }

    /** Ends a change: the agenda's version moves on and the item attendees' audience rows follow the draft. */
    fun done(meetingId: UUID) {
        agendas.touch(meetingId)
        val attendees =
            items.attendees(meetingId).values.flatten().toSet().map {
                AudienceRow(meetingId, it, AudienceSource.ATTENDEE, membersOnly = false, until = null)
            }
        audience.replace(meetingId, AudienceSource.ATTENDEE, attendees)
    }

    /** Whether the actor may change the agenda of [meeting] (its secretary or `forums.manage`). */
    fun canEdit(meeting: MeetingRow): Boolean = access.manages(meeting.forumId)

    private fun rowsOf(agenda: AgendaRow): AgendaRows =
        AgendaRows(
            agenda = agenda,
            sections = sections.live(agenda.meetingId),
            items = items.live(agenda.meetingId),
            papers = papers.live(agenda.meetingId),
            attendees = items.attendees(agenda.meetingId),
        )
}

/** People and levels on an agenda (design E03 §1.2: `RESTRICTION_AUDIENCE`, `CLASSIFICATION`, caps). */
@Component
class AgendaPeople(
    private val clearances: Clearances,
    private val policy: Policy,
    private val audience: AudienceStore,
    private val users: UserSummaries,
) {
    fun rank(level: UUID): Int? = clearances.level(level)?.rank

    /**
     * An item's or paper's [level] at [field]: known, never below [floor] (the meeting's, the item's or the memo's), and
     * never above the actor's own clearance.
     */
    fun level(
        field: String,
        level: UUID,
        floor: UUID,
    ): FieldProblem? {
        val rank = rank(level)
        return when {
            rank == null -> FieldProblem(field, "unknown", "No such classification level.")
            rank < (rank(floor) ?: 0) -> FieldProblem(field, "CLASSIFICATION", "Not below the level of the meeting, the item or the paper.")
            rank > policy.clearanceRank() -> FieldProblem(field, "CLASSIFICATION", "Above your own clearance.")
            else -> null
        }
    }

    /** The higher of two levels. */
    fun higher(
        a: UUID,
        b: UUID,
    ): UUID = if ((rank(b) ?: 0) > (rank(a) ?: 0)) b else a

    /** The users among [ids] not cleared for [level]. */
    fun uncleared(
        ids: Collection<UUID>,
        level: UUID,
    ): Set<UUID> {
        val floor = rank(level) ?: 0
        val ranks = clearances.rankOf(ids)
        return ids.filter { (ranks[it] ?: 0) < floor }.toSet()
    }

    /** The people in [meetingId]'s audience (members, guests and item attendees). */
    fun audienceOf(meetingId: UUID): Set<UUID> = audience.audience(meetingId).map { it.userId }.toSet()

    /** "Only these people" at [field]: null is no restriction; otherwise 1–100 people in the audience, cleared for [level]. */
    fun restriction(
        field: String,
        restrictedTo: List<UUID>?,
        meetingId: UUID,
        level: UUID,
    ): Pair<List<UUID>?, List<FieldProblem>> {
        val ids = restrictedTo?.distinct() ?: return null to emptyList()
        val outside = ids - audienceOf(meetingId)
        val problems =
            listOfNotNull(
                FieldProblem(field, "REQUIRED", "Name at least one person, or leave the item open.").takeIf { ids.isEmpty() },
                FieldProblem(field, "TOO_MANY", "At most ${AgendaRules.MAX_RESTRICTED} people.").takeIf {
                    ids.size >
                        AgendaRules.MAX_RESTRICTED
                },
                FieldProblem(field, "RESTRICTION_AUDIENCE", "Only people the meeting is for.").takeIf { outside.isNotEmpty() },
                FieldProblem(field, "CLASSIFICATION", "Someone named is not cleared for this level.")
                    .takeIf { uncleared(ids, level).isNotEmpty() },
            )
        return ids to problems
    }

    /** Active users among [ids]. */
    fun active(ids: Collection<UUID>): Set<UUID> =
        users
            .of(ids)
            .values
            .filter { it.status == UserStatus.ACTIVE }
            .map { it.id }
            .toSet()

    /** Item attendees and presenters at [level] (design E03 §1.2): active users cleared for it, within the caps. */
    fun people(
        attendees: List<UUID>,
        presenters: List<Presenter>,
        level: UUID,
    ): List<FieldProblem> {
        val users = (attendees + presenters.mapNotNull { it.userId }).distinct()
        val inactive = users - active(users)
        return listOfNotNull(
            FieldProblem("attendees", "TOO_MANY", "At most ${AgendaRules.MAX_ATTENDEES} attendees.")
                .takeIf { attendees.distinct().size > AgendaRules.MAX_ATTENDEES },
            FieldProblem("presenters", "TOO_MANY", "At most ${AgendaRules.MAX_PRESENTERS} presenters.")
                .takeIf { presenters.size > AgendaRules.MAX_PRESENTERS },
            FieldProblem("presenters", "FORMAT", "Each presenter is a user or a name of 1 to ${AgendaRules.MAX_NAME} characters.")
                .takeIf { presenters.any { !it.valid() } },
            FieldProblem("attendees", "unknown", "No such active user.").takeIf { inactive.isNotEmpty() },
            FieldProblem("attendees", "CLASSIFICATION", "Someone named is not cleared for this level.")
                .takeIf { uncleared(users, level).isNotEmpty() },
        )
    }

    private fun Presenter.valid(): Boolean {
        val text = name
        val named = text != null && text.isNotBlank() && text.length <= AgendaRules.MAX_NAME
        return if (userId == null) named else text == null
    }
}
