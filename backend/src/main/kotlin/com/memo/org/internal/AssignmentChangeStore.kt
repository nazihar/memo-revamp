package com.memo.org.internal

import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.references.ORG_ASSIGNMENT
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.util.UUID

/** When assignments start and end, for the holder changes that take effect on a date. Inside a tenant transaction. */
@Repository
class AssignmentChangeStore(
    private val tenantDsl: TenantDsl,
) {
    /**
     * Positions with an active assignment that starts or ends on a day after [after] up to [through]: the positions whose
     * holder on one of those days differs from the day before's (a period is half-open, so it ends on its `to`).
     */
    fun changingBetween(
        after: LocalDate,
        through: LocalDate,
    ): Set<UUID> {
        val a = ORG_ASSIGNMENT
        val starts = a.VALID_FROM.gt(after).and(a.VALID_FROM.le(through))
        val ends = a.VALID_TO.gt(after).and(a.VALID_TO.le(through))
        return tenantDsl
            .current()
            .selectDistinct(a.POSITION_ID)
            .from(a)
            .where(a.STATUS.eq(RecordStatus.ACTIVE.name), starts.or(ends))
            .fetch(a.POSITION_ID)
            .filterNotNull()
            .toSet()
    }
}
