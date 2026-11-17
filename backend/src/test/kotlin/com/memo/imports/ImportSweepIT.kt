package com.memo.imports

import com.memo.common.tenant.TenantContext
import com.memo.imports.internal.close.SweepResult
import com.memo.imports.internal.report.ImportEndLimits
import com.memo.imports.internal.run.ImportRunLimits
import com.memo.jooq.tables.references.IMPORTS_BATCH
import com.memo.jooq.tables.references.IMPORTS_FILE
import com.memo.jooq.tables.references.IMPORTS_ROW
import com.memo.jooq.tables.references.IMPORTS_STAGED
import com.memo.testsupport.Audit
import com.memo.testsupport.aTestTenant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * The hourly sweep (ADR-0055, design E06 §13, §15; test plan E06 T8 `ImportCloseIT`'s sweep rows): it returns rows a
 * dead job held, schedules again an import, a verification or a report whose job is gone, abandons batches idle for 30
 * days and closes those completed 30 days ago (both then purge), purges replaced staged copies, and touches only its own
 * tenant's batches. The scheduling itself is off in tests: the sweep's answer says what it scheduled.
 */
class ImportSweepIT : ImportEndTest() {
    @Test
    fun `the sweep restarts an orphaned import, returns the rows its dead job held, and resumes a stalled verification`() {
        val orphan = validated(listOf(memoRow("O/1"), memoRow("O/2")))
        started(orphan)
        val row = runRows(orphan).getValue("O/1")
        assertThat(sweep().restarted).describedAs("a running import is left alone").doesNotContain(UUID.fromString(orphan))

        // Its job died 20 minutes ago holding O/1.
        val quiet = Duration.ofMinutes(20)
        transactions.inTenant(tenant.id) {
            tenantDsl
                .current()
                .update(IMPORTS_ROW)
                .set(IMPORTS_ROW.UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC).minus(quiet))
                .set(IMPORTS_ROW.RESULT, "IMPORTING")
                .set(IMPORTS_ROW.ATTEMPTS, 1)
                .where(IMPORTS_ROW.ID.eq(row.id))
                .execute()
            tenantDsl
                .current()
                .update(IMPORTS_ROW)
                .set(IMPORTS_ROW.UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC).minus(quiet))
                .where(IMPORTS_ROW.BATCH_ID.eq(UUID.fromString(orphan)))
                .execute()
        }
        backdate(orphan, IMPORTS_BATCH.UPDATED_AT, quiet)
        val swept = sweep()
        assertThat(swept.restarted).contains(UUID.fromString(orphan))
        assertThat(swept.staleRows).isEqualTo(1)
        assertThat(runRows(orphan).getValue("O/1").result).isEqualTo("READY")
        runJob(orphan)
        assertThat(runRows(orphan).values.map { it.result }).containsOnly("IMPORTED")

        assertThat(sweep().resumed).describedAs("a verification just begun is left alone").doesNotContain(UUID.fromString(orphan))
        backdate(orphan, IMPORTS_BATCH.UPDATED_AT, quiet)
        assertThat(sweep().resumed).contains(UUID.fromString(orphan))
        verify(orphan)
        renditionsDone(orphan)
        backdate(orphan, IMPORTS_BATCH.UPDATED_AT, quiet)
        assertThat(sweep().resumed).describedAs("the report next").contains(UUID.fromString(orphan))
        assertThat(reportStep(orphan)).isNull()
        assertThat(batch(orphan)["state"].asString()).isEqualTo("COMPLETED")
    }

    @Test
    fun `the sweep abandons idle batches and closes completed ones after 30 days, and both are purged`() {
        val idle = validated(listOf(memoRow("I/1")))
        val busy = validated(listOf(memoRow("I/2")))
        val done = imported(listOf(memoRow("I/3")))
        completed(done)
        val month = ImportEndLimits.IDLE.plusDays(1)
        backdate(idle, IMPORTS_BATCH.UPDATED_AT, month)
        transactions.inTenant(tenant.id) {
            val dsl = tenantDsl.current()
            val old = OffsetDateTime.now(ZoneOffset.UTC).minus(month)
            dsl
                .update(IMPORTS_STAGED)
                .set(IMPORTS_STAGED.STAGED_AT, old)
                .where(IMPORTS_STAGED.BATCH_ID.eq(UUID.fromString(idle)))
                .execute()
            dsl
                .update(IMPORTS_FILE)
                .set(IMPORTS_FILE.REGISTERED_AT, old)
                .where(IMPORTS_FILE.BATCH_ID.eq(UUID.fromString(idle)))
                .execute()
        }
        // The busy batch is as old, but its originals were registered and received just now.
        backdate(busy, IMPORTS_BATCH.UPDATED_AT, month)
        backdate(done, IMPORTS_BATCH.COMPLETED_AT, ImportEndLimits.KEEP_STAGED.plusDays(1))

        val swept = sweep()
        assertThat(swept.ended).contains(UUID.fromString(idle), UUID.fromString(done)).doesNotContain(UUID.fromString(busy))
        val abandoned = batch(idle)
        assertThat(abandoned["state"].asString()).isEqualTo("ABANDONED")
        assertThat(abandoned["closeReason"].asString()).contains("30 idle days")
        assertThat(rows(idle).values.map { it["result"].asString() }).containsOnly("NOT_STARTED")
        Audit.assertAudited(tenant.id, "import.batch.abandoned", idle, "legacy-import")
        assertThat(batch(done)["state"].asString()).isEqualTo("CLOSED")
        Audit.assertAudited(tenant.id, "import.batch.closed", done, "legacy-import")
        assertThat(batch(busy)["state"].asString()).isEqualTo("VALIDATED")

        // A purge interrupted (its job gone) is scheduled again by the next sweep, then runs to its end.
        assertThat(sweep().purging).contains(UUID.fromString(idle), UUID.fromString(done))
        purge(idle)
        purge(done)
        listOf(idle, done).forEach { assertThat(batch(it).absent("purgedAt")).describedAs(it).isFalse() }
        assertThat(sweep().purging).doesNotContain(UUID.fromString(idle), UUID.fromString(done))
    }

    @Test
    fun `the sweep purges replaced staged copies of open batches, and sweeps only its own tenant`() {
        val id = newBatch()
        val book = workbook(listOf(memoRow("P/1")), listOf(fileRow("P/1", pathOf("P/1"))))
        attach(id, book)
        val first = batch(id)["sources"].items().single()["versionId"].asString()
        attach(id, book)

        val other = registry.aTestTenant(transactions, settings, "sweep-other", mfaRequired = false)
        val elsewhere = TenantContext.call(other.id) { sweeper.sweep() }
        assertThat(elsewhere).isEqualTo(SweepResult())

        val swept = sweep()
        assertThat(swept.superseded).isGreaterThanOrEqualTo(1)
        assertThat(transactions.readInTenant(tenant.id) { files.version(UUID.fromString(first))!! }.purgedAt).isNotNull()
        assertThat(batch(id)["state"].asString()).isEqualTo("OPEN")

        // A stopped import's held rows are returned, and its imported memos' originals kept for their type's years again.
        val stopped = validated(listOf(memoRow("P/2"), memoRow("P/3")))
        runner.chunkRows = 1
        started(stopped)
        step(stopped)
        act(stopped, "stop")
        step(stopped)
        assertThat(batch(stopped)["state"].asString()).isEqualTo("STOPPED")
        val retained = sweep()
        assertThat(retained.staleRows).isZero()
        assertThat(retained.retained).describedAs("already kept as long by the import").isZero()
        assertThat(ImportRunLimits.STALE).isLessThan(ImportEndLimits.IDLE)
    }

    private fun sweep(): SweepResult = TenantContext.call(tenant.id) { sweeper.sweep() }
}
