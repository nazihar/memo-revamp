package com.memo.forum.internal

import com.memo.common.authz.Clearances
import com.memo.common.authz.ForbiddenException
import com.memo.common.authz.Permission
import com.memo.common.authz.Policy
import com.memo.common.web.refuseIf
import com.memo.forum.ForumRole
import com.memo.org.OrgDirectory
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.UUID

/**
 * Who may see and change a forum (ADR-0056, design E01 §4). Deny by default: a forum is visible to its current and
 * upcoming members, to holders of `forums.manage` and `forum.read`, and only up to the actor's clearance; an invisible
 * forum answers 404. Members are managed by `forums.manage`, or by the forum's secretary within `SECRETARY_LIMIT`.
 */
@Component
class ForumAccess(
    private val policy: Policy,
    private val store: ForumStore,
    private val memberStore: ForumMemberStore,
    private val types: ForumTypeVersionStore,
    private val clearances: Clearances,
    private val org: OrgDirectory,
) {
    fun managesAll(): Boolean = policy.allows(Permission.FORUMS_MANAGE)

    fun readsAll(): Boolean = managesAll() || policy.allows(Permission.FORUM_READ)

    /** The classification rank of [forum]: its own level, else its type's default, else the lowest. */
    fun rank(forum: ForumRow): Int {
        val level = forum.classificationId ?: types.published(forum.typeId)?.settings?.defaultClassificationId
        return level?.let { clearances.level(it)?.rank } ?: 0
    }

    /** The actor's rows in [forum] that are in office or upcoming today. */
    fun ownRows(
        forum: ForumRow,
        today: LocalDate = org.today(),
    ): List<ForumMemberRow> {
        val user = actorOrNull() ?: return emptyList()
        val position = org.positionsOf(listOf(user), today)[user]?.id
        return memberStore.members(forum.id).filter { row ->
            (row.userId == user || (position != null && row.positionId == position)) &&
                row.state(today).let { it == MemberState.IN_OFFICE || it == MemberState.UPCOMING }
        }
    }

    fun visible(forum: ForumRow): Boolean = policy.clearanceRank() >= rank(forum) && (readsAll() || ownRows(forum).isNotEmpty())

    /** The forum, if the actor may see it; otherwise 404 whether or not it exists. */
    fun requireVisible(id: UUID): ForumRow = store.find(id)?.takeIf { visible(it) } ?: throw ForumNotFoundException()

    fun requireManage() {
        if (!managesAll()) throw ForbiddenException(Permission.FORUMS_MANAGE)
    }

    fun isSecretary(forum: ForumRow): Boolean = ownRows(forum).any { it.role == ForumRole.SECRETARY && it.inOffice(org.today()) }

    /** Whether the actor may manage members of [forum] at all (administrator, or its secretary). */
    fun managesMembers(forum: ForumRow): Boolean = managesAll() || isSecretary(forum)

    /**
     * Refuses a membership change the actor may not make: anyone but an administrator or the forum's secretary is
     * forbidden; a secretary never touches CHAIR or SECRETARY rows ([roles], before and after) or their own row ([row]).
     */
    fun requireMemberChange(
        forum: ForumRow,
        roles: Collection<ForumRole>,
        row: ForumMemberRow? = null,
        person: UUID? = null,
    ) {
        if (managesAll()) return
        if (!isSecretary(forum)) throw ForbiddenException(Permission.FORUMS_MANAGE)
        val actor = actorOrNull()
        val own = row?.let { ownRows(forum).any { mine -> mine.id == it.id } } == true || (person != null && person == actor)
        refuseIf(roles.any { it == ForumRole.CHAIR || it == ForumRole.SECRETARY } || own, "SECRETARY_LIMIT") {
            "Only an administrator can appoint or change a chair or a secretary, or change a secretary's own membership."
        }
    }
}
