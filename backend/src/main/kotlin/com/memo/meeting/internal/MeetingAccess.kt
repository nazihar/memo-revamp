package com.memo.meeting.internal

import com.memo.common.authz.ClassificationLevelRef
import com.memo.common.authz.Clearances
import com.memo.common.authz.ForbiddenException
import com.memo.common.authz.Permission
import com.memo.common.authz.Policy
import com.memo.common.web.FieldProblem
import com.memo.forum.ForumDirectory
import com.memo.forum.ForumPolicies
import com.memo.forum.ForumRef
import com.memo.forum.ForumRole
import com.memo.meeting.MeetingStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

/** How the actor relates to a meeting (design E03 §4); null: the meeting is invisible to them (404). */
enum class MeetingRelation {
    /** A secretary or the chair of the forum in office today, or a holder of `forums.manage`. */
    OFFICER,
    MEMBER,
    GUEST,
    ATTENDEE,

    /** A holder of `forum.read`: sees scheduled meetings, without the place or the link. */
    READER,
}

/**
 * Who may see and run meetings (design E03 §4). Deny by default, and only up to the actor's clearance (officers too):
 *  - officers (a secretary or the chair in office today, the vice-chair when there is no chair, or `forums.manage`)
 *    see every meeting of the forum, drafts included;
 *  - the audience (members on the meeting's date, guests, item attendees) and `forum.read` see meetings past DRAFT;
 *  - secretaries and `forums.manage` create and run them.
 */
@Component
class MeetingAccess(
    private val policy: Policy,
    private val directory: ForumDirectory,
    private val clearances: Clearances,
    private val audience: AudienceStore,
) {
    fun managesAll(): Boolean = policy.allows(Permission.FORUMS_MANAGE)

    fun readsAll(): Boolean = managesAll() || policy.allows(Permission.FORUM_READ)

    /**
     * The actor's role in [forumId] today. Their roles are read once per transaction (design E03 §8): a list asks for
     * every row's forum.
     */
    fun role(forumId: UUID): ForumRole? =
        actorOrNull()?.let { user -> TransactionMemo.get("roles" to user) { directory.rolesOf(user) }[forumId] }

    /** A secretary of the forum in office, or `forums.manage`: may create and run its meetings. */
    fun manages(forumId: UUID): Boolean = managesAll() || role(forumId) == ForumRole.SECRETARY

    /** [manages], or the chair in office (the vice-chair when there is none). */
    fun officer(forumId: UUID): Boolean = manages(forumId) || chairs(forumId)

    /** Whether the actor's clearance reaches [meeting]'s classification. */
    fun cleared(meeting: MeetingRow): Boolean =
        policy.clearanceRank() >= (levels().firstOrNull { it.id == meeting.classificationId }?.rank ?: Int.MAX_VALUE)

    /** The tenant's classification levels, read once per transaction (every row of a list asks). */
    fun levels(): List<ClassificationLevelRef> = TransactionMemo.get("levels") { clearances.levels() }

    /** How the actor relates to [meeting]; null when they may not see it. */
    fun relation(meeting: MeetingRow): MeetingRelation? = relations(listOf(meeting))[meeting.id]

    /**
     * How the actor relates to each of [meetings] (absent: they may not see it), with their audience rows read in one
     * query (design E03 §8: the lists).
     */
    fun relations(meetings: Collection<MeetingRow>): Map<UUID, MeetingRelation> {
        val user = actorOrNull() ?: return emptyMap()
        val visible = meetings.filter { cleared(it) }
        val rows = audience.rowsOf(visible.map { it.id }, user)
        return visible
            .mapNotNull { meeting ->
                val sources = rows[meeting.id].orEmpty().map { it.source }.toSet()
                when {
                    officer(meeting.forumId) -> MeetingRelation.OFFICER
                    meeting.status == MeetingStatus.DRAFT -> null
                    AudienceSource.MEMBER in sources -> MeetingRelation.MEMBER
                    AudienceSource.GUEST in sources -> MeetingRelation.GUEST
                    AudienceSource.ATTENDEE in sources -> MeetingRelation.ATTENDEE
                    readsAll() -> MeetingRelation.READER
                    else -> null
                }?.let { meeting.id to it }
            }.toMap()
    }

    /** The chair of the forum in office today, or the vice-chair when there is no chair (design E03 §4). */
    fun chairs(forumId: UUID): Boolean =
        when (role(forumId)) {
            ForumRole.CHAIR -> true
            ForumRole.VICE_CHAIR -> directory.membersOn(forumId).none { it.role == ForumRole.CHAIR }
            else -> false
        }
}

/** The 404 and 403 of the meeting endpoints (design E03 §4): invisible is 404, visible but not allowed is 403. */
@Component
class MeetingGuard(
    private val access: MeetingAccess,
    private val policy: Policy,
    private val store: MeetingStore,
    private val directory: ForumDirectory,
    private val policies: ForumPolicies,
    private val clearances: Clearances,
) {
    /** The forum, if the actor sees it (`forum.read`, `forums.manage`, or a role in it today); 404 otherwise. */
    fun requireForum(forumId: UUID): ForumRef =
        directory.forum(forumId)?.takeIf { access.readsAll() || access.role(it.id) != null } ?: throw MeetingNotFoundException("forum")

    /** The forum, if the actor may create meetings in it: 404 when they do not see it, 403 when they only see it. */
    fun requireManagedForum(forumId: UUID): ForumRef {
        val forum = requireForum(forumId)
        if (!access.manages(forum.id)) throw ForbiddenException(Permission.FORUMS_MANAGE)
        return forum
    }

    /** The meeting with the actor's relation to it, if they may see it; 404 otherwise. */
    fun requireVisible(id: UUID): Pair<MeetingRow, MeetingRelation> {
        val meeting = store.find(id) ?: throw MeetingNotFoundException()
        val relation = access.relation(meeting) ?: throw MeetingNotFoundException()
        return meeting to relation
    }

    /** The meeting, if the actor may run it: 404 when they do not see it, 403 when they only see it. */
    fun requireManaged(id: UUID): MeetingRow {
        val (meeting, _) = requireVisible(id)
        if (!access.manages(meeting.forumId)) throw ForbiddenException(Permission.FORUMS_MANAGE)
        return meeting
    }

    /** A meeting's [level]: never below the forum's, and never above the actor's own clearance (they would lose sight of it). */
    fun classification(
        level: UUID,
        forum: ForumRef,
        typeVersion: Int,
    ): FieldProblem? {
        val rank = clearances.level(level)?.rank
        val floor = clearances.level(forumLevel(forum, typeVersion))?.rank ?: 0
        return when {
            rank == null -> FieldProblem("classificationId", "unknown", "No such classification level.")
            rank < floor -> FieldProblem("classificationId", "CLASSIFICATION", "Not below the committee's classification.")
            rank > policy.clearanceRank() -> FieldProblem("classificationId", "CLASSIFICATION", "Above your own clearance.")
            else -> null
        }
    }

    /**
     * The forum's own level, else the default of its type version [typeVersion], else the tenant's lowest: a meeting's
     * classification is never below it (design E03 §1.1).
     */
    fun forumLevel(
        forum: ForumRef,
        typeVersion: Int,
    ): UUID = forum.classificationId ?: policies.version(forum.typeId, typeVersion)?.defaultClassificationId ?: clearances.lowest().id
}

/**
 * Values computed once per transaction and forgotten when it completes (outside a transaction, computed every time): the
 * facts about the actor that every row of a list asks for again (design E03 §8).
 */
internal object TransactionMemo {
    private val key = Any()

    fun <T : Any> get(
        name: Any,
        compute: () -> T,
    ): T {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return compute()
        @Suppress("UNCHECKED_CAST")
        val values =
            TransactionSynchronizationManager.getResource(key) as? MutableMap<Any, Any>
                ?: mutableMapOf<Any, Any>().also { values ->
                    TransactionSynchronizationManager.bindResource(key, values)
                    TransactionSynchronizationManager.registerSynchronization(
                        object : TransactionSynchronization {
                            override fun afterCompletion(status: Int) {
                                TransactionSynchronizationManager.unbindResourceIfPossible(key)
                            }
                        },
                    )
                }
        @Suppress("UNCHECKED_CAST")
        return values.getOrPut(name, compute) as T
    }
}
