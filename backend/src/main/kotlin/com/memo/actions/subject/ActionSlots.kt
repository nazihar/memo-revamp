package com.memo.actions.subject

import com.memo.actions.internal.ActionLines
import com.memo.actions.internal.ActionRow
import com.memo.actions.internal.ActionStatus
import com.memo.actions.internal.OwnerKind
import com.memo.common.authz.AccessAdministration
import com.memo.common.authz.Permission
import com.memo.forum.ActionVerifier
import com.memo.forum.ForumDirectory
import com.memo.forum.ForumPolicies
import com.memo.forum.ForumRole
import com.memo.meeting.Recusals
import com.memo.org.OrgDirectory
import com.memo.workflow.SlotHolder
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Who each slot of an action names now (design E07 §1.8, §1.9), for [ActionItemSubject]:
 * - `owner`: the person, or the position (the engine resolves its holder and follows holder changes);
 * - `verifier`: by the committee type's `verifiedBy` today, its secretaries in office (SECRETARY) or its chair, else the
 *   vice-chair (CHAIR), each falling back on the other and then on `forums.manage`; never the owner, the holder, a
 *   contributor or anyone recused from the item (the engine's SOD-2 also leaves out whoever recommended);
 * - `chair`: the chair in office, else the vice-chair, never the owner;
 * - `secretaries`: the secretaries in office not recused from the item.
 */
@Component
class ActionSlots(
    private val lines: ActionLines,
    private val directory: ForumDirectory,
    private val policies: ForumPolicies,
    private val recusals: Recusals,
    private val org: OrgDirectory,
    private val access: AccessAdministration,
) {
    /** The holders of [slot] on [row]; nobody for a slot the subject does not declare. */
    fun of(
        row: ActionRow,
        slot: String,
    ): List<SlotHolder> =
        when (slot) {
            ActionItemSubject.OWNER -> listOfNotNull(owner(row))
            ActionItemSubject.VERIFIER -> verifiers(row).map { SlotHolder.Person(it) }
            ActionItemSubject.CHAIR -> listOfNotNull(chair(row.forumId)?.takeIf { it !in owners(row) }).map { SlotHolder.Person(it) }
            ActionItemSubject.SECRETARIES -> (secretaries(row.forumId) - recused(row)).map { SlotHolder.Person(it) }
            else -> emptyList()
        }

    /** Those recused from the item, but the owner: a person recused from the item may own an action on it (§1.3). */
    fun interested(row: ActionRow): Set<UUID> = recused(row) - owners(row)

    private fun owner(row: ActionRow): SlotHolder? =
        row.owner?.let {
            when (it.kind) {
                OwnerKind.PERSON -> SlotHolder.Person(checkNotNull(it.userId))
                OwnerKind.POSITION -> SlotHolder.Position(checkNotNull(it.positionId))
            }
        }

    private fun verifiers(row: ActionRow): List<UUID> {
        val verifiedBy = policies.current(row.forumId)?.actions?.verifiedBy ?: ActionVerifier.SECRETARY
        val excluded = owners(row) + lines.contributorsOf(row.id) + recused(row)
        val secretaries = secretaries(row.forumId) - excluded
        val chair = listOfNotNull(chair(row.forumId)) - excluded
        val first = if (verifiedBy == ActionVerifier.CHAIR) chair else secretaries
        val second = if (verifiedBy == ActionVerifier.CHAIR) secretaries else chair
        return first.ifEmpty { second }.ifEmpty { (access.tenantWideHolders(Permission.FORUMS_MANAGE) - excluded).sorted() }
    }

    /** The owner (the person, the position's holder today) and the holder of the owner's task. */
    private fun owners(row: ActionRow): Set<UUID> =
        setOfNotNull(
            row.owner?.userId,
            row.owner?.positionId?.let { org.holders(listOf(it))[it] },
            row.holderUserId.takeIf { row.status != ActionStatus.DRAFT },
        )

    private fun recused(row: ActionRow): Set<UUID> = row.itemId?.let { recusals.recusedFrom(it) }.orEmpty()

    private fun secretaries(forumId: UUID): List<UUID> =
        directory
            .membersOn(forumId)
            .filter { it.role == ForumRole.SECRETARY }
            .map { it.userId }
            .distinct()

    private fun chair(forumId: UUID): UUID? {
        val seats = directory.membersOn(forumId)
        return (seats.firstOrNull { it.role == ForumRole.CHAIR } ?: seats.firstOrNull { it.role == ForumRole.VICE_CHAIR })?.userId
    }
}
