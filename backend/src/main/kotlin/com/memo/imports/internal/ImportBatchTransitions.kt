package com.memo.imports.internal

import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.references.IMPORTS_BATCH
import org.jooq.JSONB
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.HexFormat
import java.util.UUID

/**
 * The batch transitions of the dry run and the mappings (design E06 §4.1), each a guarded update on the state (and the
 * version when given) that bumps the version; false when 0 rows changed. Inside a tenant transaction.
 */
@Repository
class ImportBatchTransitions(
    private val tenantDsl: TenantDsl,
    private val clock: Clock,
) {
    /** [from] → [to], guarded on the state and on [version] when given. */
    fun move(
        id: UUID,
        from: Collection<BatchState>,
        to: BatchState,
        version: Long? = null,
    ): Boolean =
        tenantDsl
            .current()
            .update(IMPORTS_BATCH)
            .set(IMPORTS_BATCH.STATE, to.name)
            .set(IMPORTS_BATCH.VERSION, IMPORTS_BATCH.VERSION.plus(1))
            .set(IMPORTS_BATCH.UPDATED_AT, now())
            .where(IMPORTS_BATCH.ID.eq(id), IMPORTS_BATCH.STATE.`in`(from.map { it.name }))
            .and(
                version?.let { IMPORTS_BATCH.VERSION.eq(it) } ?: org.jooq.impl.DSL
                    .noCondition(),
            ).execute() == 1

    /** DRY_RUN → the [outcome]'s state, with its hashes, totals and highest rank. */
    fun finishDryRun(
        id: UUID,
        outcome: DryRunOutcome,
    ): Boolean {
        val now = now()
        return tenantDsl
            .current()
            .update(IMPORTS_BATCH)
            .set(IMPORTS_BATCH.STATE, outcome.state.name)
            .set(IMPORTS_BATCH.DRY_RUN_HASH, HexFormat.of().parseHex(outcome.dryRunHash))
            .set(IMPORTS_BATCH.INPUT_HASH, HexFormat.of().parseHex(outcome.inputHash))
            .set(IMPORTS_BATCH.DRY_RUN_AT, now)
            .set(IMPORTS_BATCH.TOTALS, JSONB.valueOf(outcome.totals))
            .set(IMPORTS_BATCH.MAX_RANK, outcome.maxRank)
            .set(IMPORTS_BATCH.VERSION, IMPORTS_BATCH.VERSION.plus(1))
            .set(IMPORTS_BATCH.UPDATED_AT, now)
            .where(IMPORTS_BATCH.ID.eq(id), IMPORTS_BATCH.STATE.eq(BatchState.DRY_RUN.name))
            .execute() == 1
    }

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    companion object {
        val EDITABLE: List<BatchState> = BatchState.entries.filter { it.editable }
    }
}
