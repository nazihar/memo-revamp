package com.memo.minutes.internal

import com.memo.meeting.DecidedItems
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * The meeting module's [DecidedItems] port (design E06 §2, E04 §1.1): an item is decided once a decision is recorded on
 * it, so its declarations change only by a secretary's correction until the minutes are approved.
 */
@Component
class StoredDecidedItems(
    private val store: DecisionStore,
) : DecidedItems {
    override fun isDecided(itemId: UUID): Boolean = store.decided(itemId)
}
