package com.memo.actions.internal

import com.memo.identity.UserSummaries
import com.memo.minutes.MinutesActionLine
import com.memo.minutes.MinutesItemActions
import com.memo.org.OrgDirectory
import org.springframework.stereotype.Component
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * The actions agreed at a meeting, for its minutes (design E07 §11, E06's `MinutesItemActions` port): every issued action
 * raised from the meeting and not cancelled, in reference order, with its title, its owner (the position's title, or the
 * person's name) and its due date. Drafts are not agreed actions yet, so the minutes do not list them. The minutes module
 * decides who reads each item's lines, by the item's rule.
 */
@Component
class ActionMinutes(
    private val store: ActionStore,
    private val users: UserSummaries,
    private val org: OrgDirectory,
) : MinutesItemActions {
    override fun of(meetingId: UUID): List<MinutesActionLine> {
        val rows =
            store
                .ofMeeting(meetingId)
                .filter { it.reference != null && it.status != ActionStatus.CANCELLED }
                .sortedWith(compareBy({ it.numberYear }, { it.sequence }))
        if (rows.isEmpty()) return emptyList()
        val names = users.names(rows.mapNotNull { it.owner?.userId }.toSet())
        val positions = rows.mapNotNull { it.owner?.positionId }.toSet()
        val titles = if (positions.isEmpty()) emptyMap() else org.positions(positions).mapValues { it.value.title }
        return rows.map { row ->
            MinutesActionLine(
                itemId = row.itemId,
                reference = checkNotNull(row.reference),
                action = row.title,
                owner = row.owner?.let { names[it.userId] ?: titles[it.positionId] }.orEmpty(),
                due = row.dueDate?.format(DUE).orEmpty(),
            )
        }
    }

    private companion object {
        val DUE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)
    }
}
