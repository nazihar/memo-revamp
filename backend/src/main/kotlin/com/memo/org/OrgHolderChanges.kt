package com.memo.org

import com.memo.org.internal.AssignmentChangeStore
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.util.UUID

/**
 * Holder changes that take effect on a date, for other modules (the workflow's daily holder run, ADR-0068 amendment of
 * 2026-11-28). A change made with immediate effect is announced by [OrgChanged]; one recorded for a later day announces
 * nothing on that day, so modules that follow holders ask here. Inside a tenant transaction.
 */
@Service
class OrgHolderChanges(
    private val store: AssignmentChangeStore,
) {
    /**
     * The positions whose holder changes on some day after [after] up to [through], by the assignments' recorded dates (a
     * term that starts or ends then).
     */
    fun between(
        after: LocalDate,
        through: LocalDate,
    ): Set<UUID> = if (through.isAfter(after)) store.changingBetween(after, through) else emptySet()
}
