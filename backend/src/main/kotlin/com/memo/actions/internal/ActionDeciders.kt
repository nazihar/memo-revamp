package com.memo.actions.internal

import com.memo.forum.DueChange
import com.memo.forum.ForumDirectory
import com.memo.forum.ForumRole
import com.memo.meeting.Recusals
import org.springframework.stereotype.Component
import java.util.UUID

/** Who answers an owner's request for a new date (design E07 §1.7), for [ActionDates] and the abilities [ActionAccess] shows. */
@Component
class ActionDeciders(
    private val directory: ForumDirectory,
    private val recusals: Recusals,
) {
    /**
     * Who agrees to a new date on [row] under [mode]: the secretaries in office not recused (SECRETARY_AGREES), or the
     * chair in office not recused, else the vice-chair not recused (CHAIR_AGREES); nobody under OWNER_WITH_REASON.
     */
    fun of(
        row: ActionRow,
        mode: DueChange,
    ): List<UUID> {
        val away = recused(row)
        val seats = directory.membersOn(row.forumId)
        return when (mode) {
            DueChange.SECRETARY_AGREES -> {
                seats.filter { it.role == ForumRole.SECRETARY }.map { it.userId }.distinct() - away
            }

            DueChange.CHAIR_AGREES -> {
                val chair = seats.firstOrNull { it.role == ForumRole.CHAIR }?.userId?.takeIf { it !in away }
                listOfNotNull(chair ?: seats.firstOrNull { it.role == ForumRole.VICE_CHAIR }?.userId?.takeIf { it !in away })
            }

            DueChange.OWNER_WITH_REASON -> {
                emptyList()
            }
        }
    }

    /** The people recused from [row]'s item (none for an action without one). */
    fun recused(row: ActionRow): Set<UUID> = row.itemId?.let { recusals.recusedFrom(it) }.orEmpty()
}
