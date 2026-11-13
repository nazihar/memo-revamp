package com.memo.workflow.internal

import com.memo.org.OrgDirectory
import com.memo.workflow.definition.ForwardWithin
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Where a step's holder may forward it (design E04 §4.7), by the positions held today: within the holder's unit (the
 * unit or a unit below it), or within the holder's legal entity. Someone without a position is outside every limit.
 * Inside a tenant transaction.
 */
@Component
class ForwardLimits(
    private val directory: OrgDirectory,
) {
    fun within(
        holder: UUID,
        to: UUID,
        limit: ForwardWithin,
    ): Boolean {
        val units = directory.positionsOf(listOf(holder, to)).mapValues { it.value.unitId }
        val from = units[holder]
        val target = units[to]
        return when {
            limit == ForwardWithin.ANYONE -> true
            from == null || target == null -> false
            limit == ForwardWithin.UNIT -> target == from || from in directory.ancestors(target)
            else -> directory.legalEntityOf(target) == directory.legalEntityOf(from)
        }
    }
}
