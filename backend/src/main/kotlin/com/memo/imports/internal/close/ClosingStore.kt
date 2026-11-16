package com.memo.imports.internal.close

import com.memo.common.tenant.TenantDsl
import com.memo.imports.internal.IntakeState
import com.memo.imports.internal.dryrun.RowResult
import com.memo.jooq.tables.references.IMPORTS_FILE
import com.memo.jooq.tables.references.IMPORTS_ROW
import com.memo.jooq.tables.references.IMPORTS_STAGED
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * What closing a batch changes in its own tables (design E06 §4.1, §4.2, §4.3): rows the import never reached become
 * NOT_STARTED, and each staged version is marked purged (the original received with it PURGED). Rows, originals and
 * staged versions are kept with their hashes. Inside a tenant transaction.
 */
@Repository
class ClosingStore(
    private val tenantDsl: TenantDsl,
    private val clock: Clock,
) {
    /** Rows of an abandoned batch the import never reached (READY, or still PENDING): NOT_STARTED; returns how many. */
    fun notStarted(batchId: UUID): Int =
        tenantDsl
            .current()
            .update(IMPORTS_ROW)
            .set(IMPORTS_ROW.RESULT, RowResult.NOT_STARTED.name)
            .set(IMPORTS_ROW.UPDATED_AT, now())
            .where(IMPORTS_ROW.BATCH_ID.eq(batchId), IMPORTS_ROW.RESULT.`in`(RowResult.READY.name, RowResult.PENDING.name))
            .execute()

    /** Staged versions of [batchId] not purged yet, oldest first, at most [limit]. */
    fun unpurged(
        batchId: UUID,
        limit: Int,
    ): List<UUID> =
        tenantDsl
            .current()
            .select(IMPORTS_STAGED.VERSION_ID)
            .from(IMPORTS_STAGED)
            .where(IMPORTS_STAGED.BATCH_ID.eq(batchId), IMPORTS_STAGED.PURGED_AT.isNull)
            .orderBy(IMPORTS_STAGED.STAGED_AT, IMPORTS_STAGED.VERSION_ID)
            .limit(limit)
            .fetch { it.value1()!! }

    fun unpurgedCount(batchId: UUID): Int =
        tenantDsl.current().fetchCount(IMPORTS_STAGED, IMPORTS_STAGED.BATCH_ID.eq(batchId).and(IMPORTS_STAGED.PURGED_AT.isNull))

    fun purgedCount(batchId: UUID): Int =
        tenantDsl.current().fetchCount(IMPORTS_STAGED, IMPORTS_STAGED.BATCH_ID.eq(batchId).and(IMPORTS_STAGED.PURGED_AT.isNotNull))

    /** [versionId]'s content is purged: the staged version is marked, and the original received with it is PURGED. */
    fun purged(versionId: UUID) {
        val dsl = tenantDsl.current()
        val now = now()
        dsl
            .update(IMPORTS_STAGED)
            .set(IMPORTS_STAGED.PURGED_AT, now)
            .where(IMPORTS_STAGED.VERSION_ID.eq(versionId), IMPORTS_STAGED.PURGED_AT.isNull)
            .execute()
        dsl
            .update(IMPORTS_FILE)
            .set(IMPORTS_FILE.STATE, IntakeState.PURGED.name)
            .where(IMPORTS_FILE.VERSION_ID.eq(versionId), IMPORTS_FILE.STATE.eq(IntakeState.RECEIVED.name))
            .execute()
    }

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
}
