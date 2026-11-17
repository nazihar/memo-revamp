package com.memo.imports.internal.close

import com.memo.common.tenant.TenantDsl
import com.memo.imports.internal.ImportBatchTransitions
import com.memo.imports.internal.dryrun.RowResult
import com.memo.jooq.tables.references.IMPORTS_BATCH
import com.memo.jooq.tables.references.IMPORTS_FILE
import com.memo.jooq.tables.references.IMPORTS_ROW
import com.memo.jooq.tables.references.IMPORTS_STAGED
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** What the sweep reads (design E06 §15). Inside a tenant transaction. */
@Repository
class SweepReads(
    private val tenantDsl: TenantDsl,
) {
    /** The batches with something left to do: every batch but those CLOSED or ABANDONED whose staged objects are purged. */
    fun sweepable(): List<UUID> =
        tenantDsl
            .current()
            .select(IMPORTS_BATCH.ID)
            .from(IMPORTS_BATCH)
            .where(
                IMPORTS_BATCH.STATE
                    .notIn(ImportBatchTransitions.ENDED.map { it.name })
                    .or(IMPORTS_BATCH.PURGED_AT.isNull),
            ).orderBy(IMPORTS_BATCH.CREATED_AT)
            .fetch { it.value1()!! }

    /** The last change to an editable batch: its settings or state, a staged upload, or a registered path. */
    fun lastActivity(batchId: UUID): Instant {
        val dsl = tenantDsl.current()
        val batch = dsl.select(IMPORTS_BATCH.UPDATED_AT).from(IMPORTS_BATCH).where(IMPORTS_BATCH.ID.eq(batchId))
        val staged = dsl.select(DSL.max(IMPORTS_STAGED.STAGED_AT)).from(IMPORTS_STAGED).where(IMPORTS_STAGED.BATCH_ID.eq(batchId))
        val registered = dsl.select(DSL.max(IMPORTS_FILE.REGISTERED_AT)).from(IMPORTS_FILE).where(IMPORTS_FILE.BATCH_ID.eq(batchId))
        return latest(dsl.select(DSL.greatest(DSL.field(batch), DSL.field(staged), DSL.field(registered))).fetchOne()?.value1())
    }

    /** The last change to an importing batch: its state, or any of its rows. */
    fun lastRunChange(batchId: UUID): Instant {
        val dsl = tenantDsl.current()
        val batch = dsl.select(IMPORTS_BATCH.UPDATED_AT).from(IMPORTS_BATCH).where(IMPORTS_BATCH.ID.eq(batchId))
        val rows = dsl.select(DSL.max(IMPORTS_ROW.UPDATED_AT)).from(IMPORTS_ROW).where(IMPORTS_ROW.BATCH_ID.eq(batchId))
        return latest(dsl.select(DSL.greatest(DSL.field(batch), DSL.field(rows))).fetchOne()?.value1())
    }

    /** Rows of [batchId] held IMPORTING since before [before]. */
    fun staleRows(
        batchId: UUID,
        before: Instant,
    ): Int =
        tenantDsl.current().fetchCount(
            IMPORTS_ROW,
            IMPORTS_ROW.BATCH_ID
                .eq(batchId)
                .and(IMPORTS_ROW.RESULT.eq(RowResult.IMPORTING.name))
                .and(IMPORTS_ROW.UPDATED_AT.lt(before.atOffset(ZoneOffset.UTC))),
        )

    /** The instant [at] names; a batch without one counts as changed now (it is never taken for idle). */
    private fun latest(at: java.time.OffsetDateTime?): Instant = at?.toInstant() ?: Instant.MAX
}
