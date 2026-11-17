package com.memo.imports.internal.run

import com.memo.common.tenant.TenantDsl
import com.memo.imports.internal.BatchState
import com.memo.imports.internal.ImportBatch
import com.memo.imports.internal.ImportProgressView
import com.memo.imports.internal.dryrun.RowResult
import com.memo.jooq.tables.references.IMPORTS_ROW
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * A started import's progress for the batch's screen (design E06 §10, §14): the rows it took on (those given a memo id at
 * the start) by result, the rate over the last [ImportRunLimits.RATE_WINDOW], the estimated end at that rate, and whether
 * the job waits for renditions. Inside a tenant transaction.
 */
@Repository
class RunProgress(
    private val tenantDsl: TenantDsl,
    private val clock: Clock,
) {
    /** Null until the import starts. */
    fun of(batch: ImportBatch): ImportProgressView? {
        if (batch.run.startedAt == null) return null
        val byResult = byResult(batch.id)

        fun count(vararg results: RowResult) = results.sumOf { byResult[it.name] ?: 0 }
        val done = count(RowResult.IMPORTED, RowResult.SKIPPED, RowResult.REJECTED, RowResult.FAILED)
        val left = count(RowResult.READY, RowResult.IMPORTING)
        val now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
        val recent = recent(batch.id, now.minus(ImportRunLimits.RATE_WINDOW))
        val perMinute = recent.toDouble() / ImportRunLimits.RATE_WINDOW.toMinutes()
        val running = batch.state == BatchState.IMPORTING || batch.state == BatchState.STOPPING
        val end =
            if (running && left > 0 && recent > 0) {
                now.plus(Duration.ofSeconds((left / perMinute * SECONDS_PER_MINUTE).toLong())).toInstant()
            } else {
                null
            }
        return ImportProgressView(
            rows = byResult.values.sum(),
            done = done,
            imported = count(RowResult.IMPORTED),
            skipped = count(RowResult.SKIPPED),
            rejected = count(RowResult.REJECTED),
            failed = count(RowResult.FAILED),
            notStarted = count(RowResult.NOT_STARTED),
            left = left,
            ratePerMinute = perMinute,
            estimatedEnd = end,
            throttled = batch.run.throttledAt != null,
            throttledSince = batch.run.throttledAt,
        )
    }

    private fun byResult(batchId: UUID): Map<String, Int> =
        tenantDsl
            .current()
            .select(IMPORTS_ROW.RESULT, DSL.count())
            .from(IMPORTS_ROW)
            .where(IMPORTS_ROW.BATCH_ID.eq(batchId), IMPORTS_ROW.MEMO_ID.isNotNull)
            .groupBy(IMPORTS_ROW.RESULT)
            .fetch { it.value1()!! to (it.value2() ?: 0) }
            .toMap()

    /** Rows the import finished since [since]. */
    private fun recent(
        batchId: UUID,
        since: OffsetDateTime,
    ): Int =
        tenantDsl.current().fetchCount(
            IMPORTS_ROW,
            DSL.and(
                IMPORTS_ROW.BATCH_ID.eq(batchId),
                IMPORTS_ROW.MEMO_ID.isNotNull,
                IMPORTS_ROW.RESULT.`in`(FINISHED),
                IMPORTS_ROW.UPDATED_AT.ge(since),
            ),
        )

    private companion object {
        const val SECONDS_PER_MINUTE = 60
        val FINISHED = listOf(RowResult.IMPORTED, RowResult.SKIPPED, RowResult.REJECTED, RowResult.FAILED).map { it.name }
    }
}
