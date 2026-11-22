package com.memo.minutes

import java.util.UUID

/**
 * An action as the minutes print it (design E07 §11): its reference, what is to be done, who owns it and when it is due,
 * pre-formatted text in the tenant's language. Business content: the minutes' readers read it with the item.
 */
data class MinutesActionLine(
    /** The item it was agreed on; null for an action of the meeting as a whole. */
    val itemId: UUID?,
    val reference: String,
    val action: String,
    val owner: String,
    val due: String,
)

/**
 * The actions agreed at a meeting, for its minutes (design E07 §11): the `item.actions` row loop of each item and the
 * meeting's `actions` loop. The actions module implements it (the minutes module must not depend on actions); without an
 * implementation the minutes list none.
 */
fun interface MinutesItemActions {
    /** The actions of [meetingId], in the order the minutes list them. Inside a tenant transaction. */
    fun of(meetingId: UUID): List<MinutesActionLine>

    companion object {
        /** No actions: the minutes before E07. */
        val NONE = MinutesItemActions { emptyList() }
    }
}
