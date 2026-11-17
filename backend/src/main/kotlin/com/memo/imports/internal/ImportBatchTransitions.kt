package com.memo.imports.internal

import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.references.IMPORTS_BATCH
import org.jooq.JSONB
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.HexFormat
import java.util.UUID

/**
 * The batch transitions of the dry run, the mappings and the import (design E06 §4.1), each a guarded update on the state
 * (and the version when given) that bumps the version; false when 0 rows changed. Inside a tenant transaction.
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

    /**
     * VALIDATED → IMPORTING at [version], recording who started it and their step-up (design E06 §5.3). False when the
     * batch moved on; a unique violation of `imports_batch_one_running` propagates (another batch is running).
     */
    fun start(
        id: UUID,
        version: Long,
        startedBy: UUID,
        stepUpAt: Instant?,
    ): Boolean {
        val now = now()
        return tenantDsl
            .current()
            .update(IMPORTS_BATCH)
            .set(IMPORTS_BATCH.STATE, BatchState.IMPORTING.name)
            .set(IMPORTS_BATCH.STARTED_BY, startedBy)
            .set(IMPORTS_BATCH.STARTED_AT, now)
            .set(IMPORTS_BATCH.STEP_UP_AT, stepUpAt?.atOffset(ZoneOffset.UTC))
            .set(IMPORTS_BATCH.VERSION, IMPORTS_BATCH.VERSION.plus(1))
            .set(IMPORTS_BATCH.UPDATED_AT, now)
            .where(IMPORTS_BATCH.ID.eq(id), IMPORTS_BATCH.STATE.eq(BatchState.VALIDATED.name), IMPORTS_BATCH.VERSION.eq(version))
            .execute() == 1
    }

    /** [from] → VERIFYING, with the time the import finished (the last row done, or finished after a stop). */
    fun finish(
        id: UUID,
        from: BatchState,
    ): Boolean {
        val now = now()
        return tenantDsl
            .current()
            .update(IMPORTS_BATCH)
            .set(IMPORTS_BATCH.STATE, BatchState.VERIFYING.name)
            .set(IMPORTS_BATCH.FINISHED_AT, now)
            .setNull(IMPORTS_BATCH.THROTTLED_AT)
            .set(IMPORTS_BATCH.VERSION, IMPORTS_BATCH.VERSION.plus(1))
            .set(IMPORTS_BATCH.UPDATED_AT, now)
            .where(IMPORTS_BATCH.ID.eq(id), IMPORTS_BATCH.STATE.eq(from.name))
            .execute() == 1
    }

    /**
     * Whether the job waits for renditions now: since now when [throttled] (the first time is kept), else not. Not a
     * transition, so the version stays (a screen's `If-Match` is not spoilt by the job).
     */
    fun throttle(
        id: UUID,
        throttled: Boolean,
    ) {
        tenantDsl
            .current()
            .update(IMPORTS_BATCH)
            .set(IMPORTS_BATCH.THROTTLED_AT, if (throttled) now() else null)
            .where(IMPORTS_BATCH.ID.eq(id))
            .and(if (throttled) IMPORTS_BATCH.THROTTLED_AT.isNull else IMPORTS_BATCH.THROTTLED_AT.isNotNull)
            .execute()
    }

    /** Whether a batch of the tenant other than [id] is importing, stopping or verifying (one running import per tenant). */
    fun anotherRunning(id: UUID): Boolean =
        tenantDsl.current().fetchExists(
            IMPORTS_BATCH,
            IMPORTS_BATCH.ID.ne(id).and(IMPORTS_BATCH.STATE.`in`(RUNNING.map { it.name })),
        )

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    companion object {
        val EDITABLE: List<BatchState> = BatchState.entries.filter { it.editable }

        /** The states `imports_batch_one_running` allows once per tenant. */
        val RUNNING: List<BatchState> = listOf(BatchState.IMPORTING, BatchState.STOPPING, BatchState.VERIFYING)
    }
}
