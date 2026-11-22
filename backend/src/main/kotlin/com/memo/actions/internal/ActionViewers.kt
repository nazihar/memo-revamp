package com.memo.actions.internal

import com.memo.common.authz.Clearances
import com.memo.common.authz.Permission
import com.memo.common.authz.Policy
import com.memo.forum.ForumDirectory
import com.memo.forum.ForumRole
import com.memo.forum.ForumSeat
import com.memo.meeting.PaperViewer
import com.memo.org.OrgDirectory
import com.memo.workflow.WorkflowQueries
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * A person as the action rule sees them today (design E07 §1.14, `viewerToday`): their role in each committee now, their
 * `forums.manage` and `forum.read`, their clearance, the position they hold, and the actions whose open task they hold or
 * may claim. Read once per request.
 */
class ActionViewer(
    val userId: UUID,
    /** `forums.manage`: every committee's officer. */
    val manages: Boolean,
    /** `forum.read` (or `forums.manage`): reads unrestricted, unhidden items' actions. */
    val reader: Boolean,
    /** The classification levels the person is cleared for. */
    val cleared: Set<UUID>,
    /** The positions they hold today (a position owner's holder). */
    val positions: Set<UUID>,
    /** Their role in each committee today. */
    private val roles: Map<UUID, ForumRole>,
    /** Whether they chair a committee: the chair, or the vice-chair when there is no chair. */
    private val chairing: (UUID) -> Boolean,
    /** The actions whose open task they hold or may claim (a verification pool, ADR-0068). */
    val tasks: () -> Set<UUID>,
) {
    /** A secretary of [forumId] in office, or `forums.manage`: raises, edits, issues and discards drafts. */
    fun manages(forumId: UUID): Boolean = manages || roles[forumId] == ForumRole.SECRETARY

    /** [manages], or the chair in office (the vice-chair when there is none). */
    fun officer(forumId: UUID): Boolean = manages(forumId) || chairs(forumId)

    /** A secretary of [forumId] in office (not `forums.manage` alone): writes for an owner, decides date requests. */
    fun secretary(forumId: UUID): Boolean = roles[forumId] == ForumRole.SECRETARY

    /** The chair of [forumId] in office, or the vice-chair when there is no chair. */
    fun chairs(forumId: UUID): Boolean = chairing(forumId)

    /** A member of [forumId] in office today, of any role. */
    fun member(forumId: UUID): Boolean = roles[forumId] != null

    /** As the paper rule sees them on [forumId]'s items today: no guest or attendee rights, the meeting open. */
    fun paper(forumId: UUID): PaperViewer {
        val role = roles[forumId]
        return PaperViewer(
            userId = userId,
            officer = officer(forumId),
            cleared = cleared,
            member = role != null,
            membersOnly = role in MEMBERS_ONLY,
            guest = false,
            attendee = false,
            reader = reader,
            open = true,
            guestsRead = false,
        )
    }

    private companion object {
        /** The roles that read members-only sections (as the meeting audience has them). */
        val MEMBERS_ONLY = setOf(ForumRole.CHAIR, ForumRole.VICE_CHAIR, ForumRole.MEMBER, ForumRole.SECRETARY)
    }
}

/** Builds the [ActionViewer] of the actor, and the committee's officers as the slots and notices need them. */
@Component
class ActionViewers(
    private val policy: Policy,
    private val clearances: Clearances,
    private val directory: ForumDirectory,
    private val org: OrgDirectory,
    private val queries: WorkflowQueries,
) {
    /** The signed-in actor; 404 for nobody signed in (an action is never shown to the system). */
    fun actor(): ActionViewer {
        val user = actorOrNull() ?: throw ActionNotFoundException("action")
        val manages = policy.allows(Permission.FORUMS_MANAGE)
        val rank = policy.clearanceRank()
        val roles = directory.rolesOf(user)
        val chairs = mutableMapOf<UUID, Boolean>()
        val tasks by lazy { openTasks() }
        return ActionViewer(
            userId = user,
            manages = manages,
            reader = manages || policy.allows(Permission.FORUM_READ),
            cleared =
                clearances
                    .levels()
                    .filter { it.rank <= rank }
                    .map { it.id }
                    .toSet(),
            positions = org.positionsOf(listOf(user))[user]?.let { setOf(it.id) }.orEmpty(),
            roles = roles,
            chairing = { forum ->
                chairs.getOrPut(forum) {
                    when (roles[forum]) {
                        ForumRole.CHAIR -> true
                        ForumRole.VICE_CHAIR -> directory.membersOn(forum).none { it.role == ForumRole.CHAIR }
                        else -> false
                    }
                }
            },
            tasks = { tasks },
        )
    }

    /** [forumId]'s secretaries in office today. */
    fun secretaries(forumId: UUID): List<UUID> = seats(forumId).filter { it.role == ForumRole.SECRETARY }.map { it.userId }.distinct()

    /** [forumId]'s chair in office, else the vice-chair; null when there is neither. */
    fun chair(forumId: UUID): UUID? {
        val seats = seats(forumId)
        return (seats.firstOrNull { it.role == ForumRole.CHAIR } ?: seats.firstOrNull { it.role == ForumRole.VICE_CHAIR })?.userId
    }

    private fun seats(forumId: UUID): List<ForumSeat> = directory.membersOn(forumId)

    /** The actions whose open task the actor holds or may claim, from their task list (every page). */
    private fun openTasks(): Set<UUID> {
        val found = mutableSetOf<UUID>()
        var cursor: String? = null
        do {
            val page = queries.myTasks(cursor, PAGE)
            page.items.filter { it.subject.type == com.memo.forum.ActionsPolicy.SUBJECT }.forEach { found += it.subject.id }
            cursor = page.nextCursor
        } while (cursor != null && found.size < MAX_TASKS)
        return found
    }

    private companion object {
        const val PAGE = 100
        const val MAX_TASKS = 2000
    }
}
