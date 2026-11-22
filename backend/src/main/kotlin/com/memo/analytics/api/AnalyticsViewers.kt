package com.memo.analytics.api

import com.memo.common.authz.Permission
import com.memo.common.authz.Policy
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.forum.ForumDirectory
import com.memo.forum.ForumRef
import com.memo.forum.ForumRole
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Who a viewer is to analytics (design E08 §2.3), from live state in the request's transaction: officers (a secretary,
 * the chair or a vice-chair in office today) and oversight (`forums.manage`, `forum.read`: every committee) see every
 * measure; members in office (a MEMBER seat today) see their committees' totals and their own attendance row. Former
 * members, guests, item attendees, observers, standing invitees and action owners outside the committee have none.
 */
data class AnalyticsViewer(
    val userId: UUID,
    /** `forums.manage` or `forum.read`: every committee in full. */
    val oversight: Boolean,
    val officerForums: Set<UUID>,
    val memberForums: Set<UUID>,
    val clearanceRank: Int,
    /** The committees in scope, by name. */
    val committees: List<ForumRef>,
) {
    fun access(forumId: UUID): AnalyticsAccess? =
        when {
            committees.none { it.id == forumId } -> null
            oversight || forumId in officerForums -> AnalyticsAccess.FULL
            else -> AnalyticsAccess.MEMBER
        }
}

@Component
class AnalyticsViewers(
    private val policy: Policy,
    private val directory: ForumDirectory,
) {
    /** The signed-in actor; null for nobody signed in, or when analytics are not theirs (no committee in scope). */
    fun actor(): AnalyticsViewer? {
        val user = (ActorContext.current().actor as? Actor.User)?.userId ?: return null
        val oversight = policy.allows(Permission.FORUMS_MANAGE) || policy.allows(Permission.FORUM_READ)
        val roles = directory.rolesOf(user)
        val officers = roles.filterValues { it in OFFICERS }.keys
        val members = roles.filterValues { it == ForumRole.MEMBER }.keys
        val committees =
            if (oversight) directory.all() else directory.forums(officers + members).values.sortedBy { it.name }
        if (committees.isEmpty() && !oversight) return null
        return AnalyticsViewer(user, oversight, officers, members, policy.clearanceRank(), committees)
    }

    private companion object {
        val OFFICERS = setOf(ForumRole.SECRETARY, ForumRole.CHAIR, ForumRole.VICE_CHAIR)
    }
}
