package com.memo.imports.internal.run

import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantDsl
import com.memo.imports.internal.dryrun.RowResult
import com.memo.jooq.tables.references.IMPORTS_ROW
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * The Memos rows as the import moves them (design E06 §4.2): every change is a guarded update on the row's result (and
 * its attempts where a job holds it), so a row is taken by one job at a time and committed at most once. Inside a tenant
 * transaction.
 */
@Repository
class RunRows(
    private val tenantDsl: TenantDsl,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    fun readyCount(batchId: UUID): Int =
        tenantDsl.current().fetchCount(IMPORTS_ROW, IMPORTS_ROW.BATCH_ID.eq(batchId).and(IMPORTS_ROW.RESULT.eq(RowResult.READY.name)))

    /** Gives each READY row without one its memo id (UUIDv7), a page at a time; returns how many were given one. */
    fun reserve(batchId: UUID): Int {
        val dsl = tenantDsl.current()
        var reserved = 0
        do {
            val page =
                dsl
                    .select(IMPORTS_ROW.ID)
                    .from(IMPORTS_ROW)
                    .where(IMPORTS_ROW.BATCH_ID.eq(batchId), IMPORTS_ROW.RESULT.eq(RowResult.READY.name), IMPORTS_ROW.MEMO_ID.isNull)
                    .orderBy(IMPORTS_ROW.ROW_NUMBER)
                    .limit(PAGE)
                    .fetch { it.value1()!! }
            if (page.isNotEmpty()) {
                dsl
                    .batch(
                        page.map { id ->
                            dsl.update(IMPORTS_ROW).set(IMPORTS_ROW.MEMO_ID, ids.next()).where(IMPORTS_ROW.ID.eq(id))
                        },
                    ).execute()
            }
            reserved += page.size
        } while (page.size == PAGE)
        return reserved
    }

    /** The next READY rows of [batchId] in row order, at most [limit]. */
    fun next(
        batchId: UUID,
        limit: Int,
    ): List<UUID> =
        tenantDsl
            .current()
            .select(IMPORTS_ROW.ID)
            .from(IMPORTS_ROW)
            .where(IMPORTS_ROW.BATCH_ID.eq(batchId), IMPORTS_ROW.RESULT.eq(RowResult.READY.name))
            .orderBy(IMPORTS_ROW.ROW_NUMBER)
            .limit(limit)
            .fetch { it.value1()!! }

    /** READY → IMPORTING, one attempt more; the attempt's number, or null when another job took the row first. */
    fun take(rowId: UUID): Int? =
        tenantDsl
            .current()
            .update(IMPORTS_ROW)
            .set(IMPORTS_ROW.RESULT, RowResult.IMPORTING.name)
            .set(IMPORTS_ROW.ATTEMPTS, IMPORTS_ROW.ATTEMPTS.plus(1))
            .set(IMPORTS_ROW.UPDATED_AT, now())
            .where(IMPORTS_ROW.ID.eq(rowId), IMPORTS_ROW.RESULT.eq(RowResult.READY.name))
            .returning(IMPORTS_ROW.ATTEMPTS)
            .fetchOne()
            ?.attempts

    /**
     * IMPORTING → [result] for the job that holds [attempt]; [errors] more are counted on the row. False when the row is no
     * longer that attempt's (recovered as stale, or finished after a stop): the caller's work then rolls back.
     */
    fun settle(
        rowId: UUID,
        attempt: Int,
        result: RowResult,
        errors: Int = 0,
    ): Boolean =
        tenantDsl
            .current()
            .update(IMPORTS_ROW)
            .set(IMPORTS_ROW.RESULT, result.name)
            .set(IMPORTS_ROW.ERRORS, IMPORTS_ROW.ERRORS.plus(errors))
            .set(IMPORTS_ROW.UPDATED_AT, now())
            .where(IMPORTS_ROW.ID.eq(rowId), IMPORTS_ROW.RESULT.eq(RowResult.IMPORTING.name), IMPORTS_ROW.ATTEMPTS.eq(attempt))
            .execute() == 1

    /**
     * Rows of [batchId] IMPORTING for longer than [stale] (a job died holding them): back to READY for another attempt, or
     * FAILED once they had [attempts]. Returns the ids of the rows that failed.
     */
    fun recover(
        batchId: UUID,
        stale: Duration,
        attempts: Int,
    ): List<UUID> {
        val dsl = tenantDsl.current()
        val now = now()
        val held = IMPORTS_ROW.BATCH_ID.eq(batchId).and(IMPORTS_ROW.RESULT.eq(RowResult.IMPORTING.name))
        val old = IMPORTS_ROW.UPDATED_AT.lt(now.minus(stale))
        val failed =
            dsl
                .update(IMPORTS_ROW)
                .set(IMPORTS_ROW.RESULT, RowResult.FAILED.name)
                .set(IMPORTS_ROW.ERRORS, IMPORTS_ROW.ERRORS.plus(1))
                .set(IMPORTS_ROW.UPDATED_AT, now)
                .where(held, old, IMPORTS_ROW.ATTEMPTS.ge(attempts))
                .returning(IMPORTS_ROW.ID)
                .fetch { it.id }
        dsl
            .update(IMPORTS_ROW)
            .set(IMPORTS_ROW.RESULT, RowResult.READY.name)
            .set(IMPORTS_ROW.UPDATED_AT, now)
            .where(held, old)
            .execute()
        return failed
    }

    /** IMPORTING → READY for the job holding [attempt]: the attempt failed and the row will be tried again. */
    fun release(
        rowId: UUID,
        attempt: Int,
    ): Boolean =
        tenantDsl
            .current()
            .update(IMPORTS_ROW)
            .set(IMPORTS_ROW.RESULT, RowResult.READY.name)
            .set(IMPORTS_ROW.UPDATED_AT, now())
            .where(IMPORTS_ROW.ID.eq(rowId), IMPORTS_ROW.RESULT.eq(RowResult.IMPORTING.name), IMPORTS_ROW.ATTEMPTS.eq(attempt))
            .execute() == 1

    /** The rows of a batch finished after a stop that the import never reached (READY, or held by no job): NOT_STARTED. */
    fun notStarted(batchId: UUID): Int =
        tenantDsl
            .current()
            .update(IMPORTS_ROW)
            .set(IMPORTS_ROW.RESULT, RowResult.NOT_STARTED.name)
            .set(IMPORTS_ROW.UPDATED_AT, now())
            .where(IMPORTS_ROW.BATCH_ID.eq(batchId), IMPORTS_ROW.RESULT.`in`(RowResult.READY.name, RowResult.IMPORTING.name))
            .execute()

    /** Rows of [batchId] a job holds now. */
    fun inFlight(batchId: UUID): Int =
        tenantDsl.current().fetchCount(IMPORTS_ROW, IMPORTS_ROW.BATCH_ID.eq(batchId).and(IMPORTS_ROW.RESULT.eq(RowResult.IMPORTING.name)))

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    private companion object {
        const val PAGE = 1_000
    }
}
