package com.memo.search.internal

import com.memo.common.authz.OrgTree
import com.memo.common.authz.Permission
import com.memo.common.authz.Policy
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Who is searching, as the search functions apply the E03 read rule (ADR-0050, ADR-0054): the reader (for participant
 * rows), their clearance, and the units whose memos they may read through `memo.read.unit`, either all of them or the
 * subtrees of their scoped grants.
 */
data class ReaderScope(
    val reader: UUID,
    val clearanceRank: Int,
    val allUnits: Boolean,
    val units: Set<UUID>,
)

/** The [ReaderScope] of the current actor, from the same grants [Policy.condition] uses. */
@Component
class ReaderScopes(
    private val policy: Policy,
    private val orgTree: ObjectProvider<OrgTree>,
) {
    fun of(reader: UUID): ReaderScope {
        val grants = policy.grants().filter { it.permission == Permission.MEMO_READ_UNIT }
        val all = grants.any { it.scopeUnit == null }
        val tree = orgTree.ifAvailable
        val units =
            if (all || tree == null) emptySet() else grants.mapNotNull { it.scopeUnit }.flatMap { tree.subtree(it) }.toSet()
        return ReaderScope(reader, policy.clearanceRank(), all, units)
    }
}
