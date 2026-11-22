package com.memo.actions.internal

import com.memo.common.authz.Clearances
import com.memo.common.web.FieldProblem
import com.memo.identity.UserStatus
import com.memo.identity.UserSummaries
import com.memo.meeting.Recusals
import com.memo.org.OrgDirectory
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * The people an action names (design E07 §1.1, §1.3): its owner, a person or a position, and up to five contributors. The
 * owner (the person, or the position's holder today) is an active user cleared for the action's level
 * (`OWNER_NOT_CLEARED`), member of the committee or not; a person recused from the item may own the action (the
 * secretary is warned). A contributor is active and cleared (`NOT_CLEARED`), not recused from the item (`RECUSED_NAMED`),
 * and not the owner (`SAME_PERSON`).
 */
@Component
class ActionPeople(
    private val users: UserSummaries,
    private val clearances: Clearances,
    private val org: OrgDirectory,
    private val recusals: Recusals,
) {
    /** The problems of naming [owner] and [contributors] on an action at [classificationId] from [itemId]. */
    fun problems(
        owner: OwnerInput?,
        contributors: List<UUID>,
        classificationId: UUID,
        itemId: UUID?,
    ): List<FieldProblem> {
        val rank = clearances.level(classificationId)?.rank ?: return emptyList()
        val holder = owner?.positionId?.takeIf { owner.kind == OwnerKind.POSITION }?.let { org.holders(listOf(it))[it] }
        val ownerUser = owner?.userId?.takeIf { owner.kind == OwnerKind.PERSON }
        val named = (listOfNotNull(ownerUser, holder) + contributors).toSet()
        val people = Named(users.of(named).mapValues { it.value.status }, clearances.rankOf(named), rank)
        val recused = itemId?.takeIf { contributors.isNotEmpty() }?.let { recusals.recusedFrom(it) }.orEmpty()
        return listOfNotNull(ownerProblem(owner, ownerUser, holder, people)) +
            contributors.mapIndexedNotNull { i, user ->
                val at = "contributors/$i"
                when {
                    !people.known(user) -> FieldProblem(at, "unknown", "No such person.")
                    !people.fit(user) -> FieldProblem(at, NOT_CLEARED, "This person is not active or not cleared for the action's level.")
                    user in recused -> FieldProblem(at, "RECUSED_NAMED", "This person declared an interest in the item.")
                    user == holder -> FieldProblem(at, "SAME_PERSON", "This person holds the owner's position.")
                    else -> null
                }
            }
    }

    private fun ownerProblem(
        owner: OwnerInput?,
        ownerUser: UUID?,
        holder: UUID?,
        people: Named,
    ): FieldProblem? {
        val position = owner?.positionId?.takeIf { owner.kind == OwnerKind.POSITION }
        return when {
            ownerUser != null && !people.known(ownerUser) -> {
                FieldProblem("owner/userId", "unknown", "No such person.")
            }

            ownerUser != null && !people.fit(ownerUser) -> {
                FieldProblem("owner/userId", OWNER_NOT_CLEARED, "This person is not active or not cleared for the action's level.")
            }

            position != null && org.positions(listOf(position)).isEmpty() -> {
                FieldProblem("owner/positionId", "unknown", "No such position.")
            }

            holder != null && !people.fit(holder) -> {
                FieldProblem(
                    "owner/positionId",
                    OWNER_NOT_CLEARED,
                    "The position's holder is not active or not cleared for the action's level.",
                )
            }

            else -> {
                null
            }
        }
    }

    /** The people named: their account state and clearance against the action's level [rank]. */
    private class Named(
        private val statuses: Map<UUID, UserStatus>,
        private val ranks: Map<UUID, Int>,
        private val rank: Int,
    ) {
        fun known(user: UUID) = user in statuses

        fun fit(user: UUID) = statuses[user] == UserStatus.ACTIVE && (ranks[user] ?: 0) >= rank
    }

    companion object {
        const val OWNER_NOT_CLEARED = "OWNER_NOT_CLEARED"
        const val NOT_CLEARED = "NOT_CLEARED"
    }
}
