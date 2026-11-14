package com.memo.memo.internal

import com.memo.common.authz.RoleHolders
import com.memo.org.OrgDirectory
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Who may raise memos of which type (ADR-0043, design E01 §3): a type's published version names units (with or without
 * the units below them) and roles; people in one of the units, or holding one of the roles where they sit, may raise it.
 * An empty scope lets everyone with `memo.create`. Inside a tenant transaction.
 */
@Component
class MemoTypeAccess(
    private val types: MemoTypes,
    private val directory: OrgDirectory,
    private val roles: RoleHolders,
) {
    /** The active types [user] may raise, with their published versions. */
    fun raisable(user: UUID): List<Pair<MemoType, MemoTypeVersionRow>> {
        val placed = placement(user)
        return types.active().mapNotNull { type ->
            types.published(type.id)?.takeIf { mayRaise(it.settings.raiseScope, user, placed) }?.let { type to it }
        }
    }

    /** The published version of [typeId], if [user] may raise memos of it; else not found (the type is not offered). */
    fun raisableVersion(
        typeId: UUID,
        user: UUID,
    ): MemoTypeVersionRow {
        val type = types.get(typeId)
        val version = types.published(typeId)?.takeIf { type.active && mayRaise(it.settings.raiseScope, user, placement(user)) }
        return version ?: throw MemoNotFoundException("memo type")
    }

    /** Whether [user] may raise a memo of [typeId] now (its published version's scope). */
    fun mayRaise(
        typeId: UUID,
        user: UUID,
    ): Boolean = types.published(typeId)?.let { mayRaise(it.settings.raiseScope, user, placement(user)) } == true

    private fun mayRaise(
        scope: RaiseScope,
        user: UUID,
        placed: Placement,
    ): Boolean {
        if (scope.everyone) return true
        val unit = placed.unit
        val inUnit = unit != null && scope.units.any { it.id == unit || (it.subtree && it.id in placed.above) }
        return inUnit || scope.roles.any { user in roles.holders(it, unit) }
    }

    /** Where a user sits: the unit of the position they hold today, and the units above it. */
    private data class Placement(
        val unit: UUID?,
        val above: List<UUID>,
    )

    private fun placement(user: UUID): Placement {
        val unit = directory.positionsOf(listOf(user))[user]?.unitId
        return Placement(unit, unit?.let { directory.ancestors(it) }.orEmpty())
    }
}
