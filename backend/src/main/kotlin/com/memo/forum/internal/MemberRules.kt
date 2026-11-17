package com.memo.forum.internal

import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import com.memo.forum.ForumRole
import com.memo.forum.ForumStatus
import com.memo.identity.UserStatus
import com.memo.identity.UserSummaries
import com.memo.org.OrgDirectory
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * What every membership change checks (ADR-0056, design E01 §1): the forum is locked and visible, the row belongs to it,
 * the values are well formed, and the invariants hold against every other row of the forum, ended ones included
 * (`ALREADY_MEMBER`, `ONE_CHAIR`). Inside the change's tenant transaction.
 */
@Component
class MemberRules(
    private val store: ForumStore,
    private val memberStore: ForumMemberStore,
    private val types: ForumTypeVersionStore,
    private val access: ForumAccess,
    private val users: UserSummaries,
    private val org: OrgDirectory,
) {
    /** The forum, locked for the change; 404 when the actor may not see it. */
    fun locked(id: UUID): ForumRow {
        access.requireVisible(id)
        return store.lock(id) ?: throw ForumNotFoundException()
    }

    /** The forum locked, refusing a dissolved one. */
    fun lockedActive(id: UUID): ForumRow =
        locked(id).also {
            refuseIf(it.status == ForumStatus.DISSOLVED, "FORUM_DISSOLVED") { "This committee is dissolved; members can only be ended." }
        }

    fun row(
        forum: ForumRow,
        memberId: UUID,
    ): ForumMemberRow = memberStore.member(memberId)?.takeIf { it.forumId == forum.id } ?: throw ForumNotFoundException("member")

    fun require(
        forum: ForumRow,
        values: MemberValues,
        except: UUID?,
    ) {
        requireNoProblems(problems(forum, values))
        requireNoOverlap(forum, values, except)
    }

    private fun problems(
        forum: ForumRow,
        values: MemberValues,
    ): List<FieldProblem> {
        val attributes =
            types
                .published(forum.typeId)
                ?.settings
                ?.memberAttributes
                ?.map { it.key }
                ?.toSet()
                .orEmpty()
        val people = users.of(listOfNotNull(values.userId, values.alternateUserId))

        fun usable(id: UUID?) = id == null || people[id]?.status?.let { it != UserStatus.DEACTIVATED } == true
        val position = values.positionId
        val ends = values.termEndsOn
        return listOfNotNull(
            FieldProblem("userId", "REQUIRED", "A person or a position.").takeIf { (values.userId == null) == (position == null) },
            FieldProblem("userId", "unknown", "No such active person.").takeUnless { usable(values.userId) },
            FieldProblem("positionId", "unknown", "No such position.")
                .takeIf { position != null && org.positions(listOf(position)).isEmpty() },
            FieldProblem("alternateUserId", "unknown", "No such active person.").takeUnless { usable(values.alternateUserId) },
            FieldProblem("alternateUserId", "SELF", "A member cannot be their own alternate.").takeIf {
                values.alternateUserId != null && values.alternateUserId == values.userId
            },
            FieldProblem("attributes", "UNKNOWN_ATTRIBUTE", "Not an attribute of this committee's type.").takeIf {
                !attributes.containsAll(values.attributes)
            },
            FieldProblem("termEndsOn", "BEFORE_START", "A term cannot end before it starts.")
                .takeIf { ends != null && ends.isBefore(values.startsOn) },
            FieldProblem("authority", "LENGTH", "At most $MAX_TEXT characters.").takeIf { (values.authority?.length ?: 0) > MAX_TEXT },
        )
    }

    private fun requireNoOverlap(
        forum: ForumRow,
        values: MemberValues,
        except: UUID?,
    ) {
        val others =
            memberStore.members(forum.id).filter {
                it.id != except && it.endReason != EndReason.WITHDRAWN && it.overlaps(values.startsOn, values.termEndsOn)
            }
        val same =
            others.any {
                (values.userId != null && it.userId == values.userId) || (values.positionId != null && it.positionId == values.positionId)
            }
        refuseIf(same, "ALREADY_MEMBER") { "This person or position already sits on the committee for part of that time." }
        refuseIf(values.role == ForumRole.CHAIR && others.any { it.role == ForumRole.CHAIR }, "ONE_CHAIR") {
            "The committee already has a chair for part of that time."
        }
    }

    companion object {
        /** Authority texts and end notes. */
        const val MAX_TEXT = 500
    }
}
