package com.memo.imports

import com.memo.common.context.ActorContext
import com.memo.common.tenant.TenantContext
import com.memo.files.FilePurges
import com.memo.files.internal.RenditionScheduler
import com.memo.imports.internal.dryrun.Finding
import com.memo.imports.internal.dryrun.FindingStore
import com.memo.imports.internal.dryrun.ProblemLevel
import com.memo.imports.internal.dryrun.ProblemSheet
import com.memo.imports.internal.run.ImportRunLimits
import com.memo.jooq.tables.references.IMPORTS_FILE
import com.memo.jooq.tables.references.IMPORTS_MAPPING
import com.memo.jooq.tables.references.IMPORTS_ROW
import com.memo.memo.LegacyMemos
import com.memo.memo.internal.MemoTypeLifecycle
import com.memo.memo.internal.Retention
import com.memo.memo.internal.RetentionFrom
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.AppRoleJdbc.long
import com.memo.testsupport.Audit
import com.memo.testsupport.Samples
import com.memo.testsupport.aPassword
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.ageSessionFactors
import com.memo.testsupport.anActiveUser
import com.memo.testsupport.sendJson
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.UUID

/**
 * The import (ADR-0055, design E06 §4.1, §4.2, §5.3, §10, §12; test plan E06 T7 `ImportRunIT`): a validated batch starts
 * with a step-up, the count shown and a cleared importer; the job imports each row once in its own transaction, promoting
 * its originals with their intake hashes and keeping them for the type's years; it skips rows imported before unchanged,
 * rejects changed ones and rows a check now refuses, fails a row whose original is refused, retries a failing row, picks
 * up rows a dead job held, waits for renditions, stops between chunks, resumes with a step-up and finishes early; nothing
 * of workflow, tasks, notifications, watches or final records is written; and only holders in the batch's tenant act.
 */
class ImportRunIT : ImportRunTest() {
    @Autowired lateinit var typeLifecycle: MemoTypeLifecycle

    @Autowired lateinit var findingStore: FindingStore

    @Autowired lateinit var purges: FilePurges

    @Autowired internal lateinit var renditionScheduling: RenditionScheduler

    @Test
    fun `a validated batch imports with a step-up, its originals promoted whole and kept for the type's years`() {
        retainGeneralFor(YEARS)
        val linked = person()
        val annex = "originals/LEG-1-annex.txt" to text("annex")
        val id =
            validated(
                listOf(memoRow("LEG/1"), memoRow("LEG/2")),
                approvals = listOf(approvalRow("LEG/1", email = linked.account.email)),
                extraFiles = mapOf("LEG/1" to annex),
            )
        val before = untouchedTables()

        val started = start(id, 2)
        assertThat(started).hasStatus(HttpStatus.ACCEPTED)
        val view = started.json()
        assertThat(view["state"].asString()).isEqualTo("IMPORTING")
        assertThat(view["startedBy"].asString()).isEqualTo(holder.toString())
        assertThat(view.absent("stepUpAt")).isFalse()
        assertThat(view["progress"]["left"].asInt()).isEqualTo(2)
        assertThat(view["allowedActions"].items().map { it.asString() }).containsExactly("STOP")
        assertThat(runRows(id).values.map { it.memoId }).doesNotContainNull().doesNotHaveDuplicates()

        runJob(id)
        val done = batch(id)
        assertThat(done["state"].asString()).isEqualTo("VERIFYING")
        assertThat(done.absent("finishedAt")).isFalse()
        assertThat(listOf("imported", "done", "left").map { done["progress"][it].asInt() }).containsExactly(2, 2, 0)
        val rows = runRows(id)
        assertThat(rows.values.map { it.result to it.attempts }).containsOnly("IMPORTED" to 1)
        val memo = rows.getValue("LEG/1").memoId!!
        assertThat(promoted(rows.getValue("LEG/1").id)).hasSize(2).allSatisfy { file ->
            val stored = transactions.readInTenant(tenant.id) { files.version(file.storedVersionId!!)!! }
            assertThat(stored.sha256).describedAs("promoted whole").isEqualTo(file.intakeSha256)
            assertThat(stored.retainUntil).describedAs("kept for the type's years").isAfter(Instant.now().plus(LONGER, ChronoUnit.DAYS))
            Audit.assertAudited(tenant.id, "file.retention.extended", stored.id.toString())
        }
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            assertThat(c.long("select count(*) from memo where id = ? and origin = 'IMPORTED' and reference = 'LEG/1'", memo)).isOne()
            assertThat(c.long("select count(*) from memo_legacy_file where memo_id = ?", memo)).isEqualTo(2)
            assertThat(c.long("select count(*) from memo_participant where memo_id = ? and user_id = ?", memo, linked.id)).isOne()
            assertThat(c.long("select count(*) from memo_participant where user_id = ?", holder)).describedAs("the importer").isZero()
        }
        assertThat(untouchedTables()).describedAs("no workflow, task, notification, watch or final record").isEqualTo(before)
        Audit.assertAudited(tenant.id, "import.batch.started", id, holder.toString())
        Audit.assertAudited(tenant.id, "import.batch.finished", id, "legacy-import")
        assertThat(Audit.assertAudited(tenant.id, "memo.imported", memo.toString(), "legacy-import").canonical).contains(holder.toString())
    }

    @Test
    fun `a start needs a fresh step-up, the count shown, a cleared importer, a validated batch and an unchanged one`() {
        val id = validated(listOf(memoRow("S/1"), memoRow("S/2", classification = "Confidential")))
        val (_, stale) = imports.importer(mvc, tenant)
        ageSessionFactors(stale)
        assertThat(start(id, 2, stale).json()["type"].asString()).isEqualTo("urn:memo:problem:step-up-required")
        assertThat(start(id, 2, version = null)).hasStatus(HttpStatus.PRECONDITION_REQUIRED)
        assertThat(start(id, 2, version = 0)).hasStatus(HttpStatus.PRECONDITION_FAILED)
        assertThat(start(id, 1).json()["reason"].asString()).isEqualTo("ROWS_CHANGED")
        val (_, uncleared) = imports.importer(mvc, tenant)
        assertThat(start(id, 2, uncleared).errors()).containsExactly("clearance:IMPORTER_NOT_CLEARED")

        // A mapping changed behind the batch's back: what the dry run read is no longer what would be imported.
        val mapping = IMPORTS_MAPPING
        transactions.inTenant(tenant.id) {
            tenantDsl
                .current()
                .update(mapping)
                .set(mapping.LEGACY_ORDER, mapping.LEGACY_ORDER.plus(1))
                .where(mapping.BATCH_ID.eq(UUID.fromString(id)), mapping.KIND.eq("CLASSIFICATION"))
                .execute()
        }
        assertThat(start(id, 2).json()["reason"].asString()).isEqualTo("DRY_RUN_STALE")
        assertThat(batch(id)["state"].asString()).isEqualTo("VALIDATED")

        val open = newBatch()
        assertThat(start(open, 1).json()["reason"].asString()).isEqualTo("WRONG_STATE")
        val rejected = validated(listOf(memoRow("S/3"), memoRow("S/4", status = "Pending")))
        transactions.inTenant(tenant.id) {
            findingStore.add(UUID.fromString(rejected), null, listOf(Finding(ProblemLevel.BATCH, "MALFORMED", ProblemSheet.BATCH)))
        }
        assertThat(start(rejected, 1).errors()).containsExactly("problems:BATCH_PROBLEMS")
        val nothing = validated(listOf(memoRow("S/5", status = "Pending")))
        assertThat(start(nothing, 0).errors()).containsExactly("expectedRows:NOTHING_TO_IMPORT")
        assertThat(runRows(id).values.map { it.memoId }).describedAs("nothing reserved").containsOnlyNulls()
    }

    @Test
    fun `a stop takes effect between chunks, a resume needs a step-up, and a finish leaves the rest not started`() {
        val id = validated(listOf(memoRow("R/1"), memoRow("R/2"), memoRow("R/3")))
        runner.chunkRows = 1
        started(id)
        assertThat(step(id)).isEqualTo(Duration.ZERO)
        assertThat(act(id, "stop")).hasStatus(HttpStatus.ACCEPTED)
        assertThat(act(id, "stop").json()["state"].asString()).describedAs("a repeat").isEqualTo("STOPPING")
        assertThat(act(id, "resume").json()["reason"].asString()).isEqualTo("WRONG_STATE")
        assertThat(step(id)).isNull()
        val stopped = batch(id)
        assertThat(stopped["state"].asString()).isEqualTo("STOPPED")
        assertThat(stopped["allowedActions"].items().map { it.asString() }).containsExactly("RESUME", "FINISH")
        assertThat(stopped["progress"]["imported"].asInt()).isOne()
        Audit.assertAudited(tenant.id, "import.batch.stopped", id, "legacy-import")
        assertThat(Audit.entries(tenant.id).filter { it.action == "import.batch.stopped" }.map { it.actorId })
            .containsExactly(holder.toString(), "legacy-import")
        assertThat(step(id)).describedAs("a stopped batch has no work").isNull()

        val (_, stale) = imports.importer(mvc, tenant)
        ageSessionFactors(stale)
        assertThat(act(id, "resume", stale).json()["type"].asString()).isEqualTo("urn:memo:problem:step-up-required")
        assertThat(act(id, "resume")).hasStatus(HttpStatus.ACCEPTED)
        Audit.assertAudited(tenant.id, "import.batch.resumed", id, holder.toString())
        assertThat(step(id)).isEqualTo(Duration.ZERO)
        assertThat(act(id, "stop")).hasStatus(HttpStatus.ACCEPTED)
        assertThat(step(id)).isNull()

        val finished = act(id, "finish")
        assertThat(finished).hasStatus(HttpStatus.ACCEPTED)
        assertThat(finished.json()["state"].asString()).isEqualTo("VERIFYING")
        assertThat(runRows(id).mapValues { it.value.result }).containsExactlyInAnyOrderEntriesOf(
            mapOf("R/1" to "IMPORTED", "R/2" to "IMPORTED", "R/3" to "NOT_STARTED"),
        )
        assertThat(batch(id)["progress"]["notStarted"].asInt()).isOne()
        Audit.assertAudited(tenant.id, "import.batch.finished", id, holder.toString())
        listOf("finish", "resume", "stop").forEach { assertThat(act(id, it).json()["reason"].asString()).isEqualTo("WRONG_STATE") }
    }

    @Test
    fun `a row imported before is skipped unchanged and refused changed, and a check failing now rejects its row`() {
        val first = validated(listOf(memoRow("K/1"), memoRow("K/2")))
        val second = validated(listOf(memoRow("K/1"), memoRow("K/2", subject = "Changed since"), memoRow("K/3")))
        started(first)
        runJob(first)
        setState(first, "COMPLETED")
        // K/3's number is taken by another legacy source in the meantime.
        val taken = row(number = "K/3").let { it.copy(source = it.source.copy(sourceCode = "OTHER-DMS")) }
        import(taken)

        started(second)
        runJob(second)
        val rows = runRows(second)
        assertThat(rows.mapValues { it.value.result }).containsExactlyInAnyOrderEntriesOf(
            mapOf("K/1" to "SKIPPED", "K/2" to "REJECTED", "K/3" to "REJECTED"),
        )
        val skipped = problems(second, "code=ALREADY_IMPORTED").single()
        assertThat(listOf("level", "stage").map { skipped[it].asString() }).containsExactly("INFO", "IMPORT")
        assertThat(problems(second, "code=ALREADY_IMPORTED_CHANGED").single()["stage"].asString()).isEqualTo("IMPORT")
        assertThat(problems(second, "code=NUMBER_IN_USE").single()["rowId"].asString()).isEqualTo(rows.getValue("K/3").id.toString())
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            assertThat(c.long("select count(*) from memo_legacy where legacy_number_key in ('K/1', 'K/2')")).isEqualTo(2)
            assertThat(c.long("select count(*) from memo where id = ?", rows.getValue("K/1").memoId!!)).describedAs("skipped").isZero()
        }
        assertThat(batch(second)["progress"].let { listOf(it["skipped"].asInt(), it["rejected"].asInt()) }).containsExactly(1, 2)
    }

    @Test
    fun `a refused original or a differing hash fails its row only, and a failing row is retried up to its last attempt`() {
        val id = validated(listOf(memoRow("H/1"), memoRow("H/2"), memoRow("H/3")))
        started(id)
        val rows = runRows(id)
        // H/1's staged copy now claims another hash (as a corrupted copy would); H/2's staged content is purged.
        val h1 = promoted(rows.getValue("H/1").id).single()
        transactions.inTenant(tenant.id) {
            tenantDsl
                .current()
                .update(IMPORTS_FILE)
                .set(IMPORTS_FILE.SHA256, HexFormat.of().parseHex(sha256("tampered".toByteArray())))
                .where(IMPORTS_FILE.VERSION_ID.eq(h1.intakeVersionId))
                .execute()
        }
        val h2 = promoted(rows.getValue("H/2").id).single()
        TenantContext.call(tenant.id) { ActorContext.asSystem("test-purge") { purges.purge(h2.intakeVersionId) } }

        runJob(id)
        val after = runRows(id)
        assertThat(after.mapValues { it.value.result to it.value.attempts }).containsExactlyInAnyOrderEntriesOf(
            mapOf("H/1" to ("FAILED" to 1), "H/2" to ("FAILED" to ImportRunLimits.ATTEMPTS), "H/3" to ("IMPORTED" to 1)),
        )
        val mismatch = problems(id, "code=FILE_HASH_MISMATCH").single()
        assertThat(listOf("sheet", "stage").map { mismatch[it].asString() }).containsExactly("FILES", "IMPORT")
        assertThat(problems(id, "code=IMPORT_ERROR").single()["rowId"].asString()).isEqualTo(rows.getValue("H/2").id.toString())
        assertThat(promoted(rows.getValue("H/1").id).single().storedVersionId).describedAs("nothing stored").isNull()
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            assertThat(
                c.long("select count(*) from memo where id in (?, ?)", after.getValue("H/1").memoId!!, after.getValue("H/2").memoId!!),
            ).isZero()
        }
        assertThat(batch(id)["state"].asString()).isEqualTo("VERIFYING")
    }

    @Test
    fun `a row a dead job held is picked up again, reusing the original it had already promoted, and imported once`() {
        val id = validated(listOf(memoRow("D/1"), memoRow("D/2")))
        started(id)
        val row = runRows(id).getValue("D/1")
        val file = promoted(row.id).single()
        // A job promoted D/1's original, then died before its commit.
        val stored =
            TenantContext.call(tenant.id) {
                ActorContext.asSystem("legacy-import") {
                    fileUploads.copyFrom(
                        file.intakeVersionId,
                        LegacyMemos.ORIGINAL_PURPOSE,
                        row.memoId.toString(),
                        "imp-$id-${file.rowFileId}",
                        file.intakeSha256,
                    )
                }
            }
        holdRow(row.id, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1))
        assertThat(step(id)).describedAs("D/2 is imported, D/1 is still held").isEqualTo(Duration.ZERO)
        assertThat(step(id)).describedAs("waits for the held row").isEqualTo(ImportRunLimits.HELD_WAIT)
        holdRow(row.id, OffsetDateTime.now(ZoneOffset.UTC).minus(ImportRunLimits.STALE).minusMinutes(1))
        runJob(id)

        val after = runRows(id).getValue("D/1")
        assertThat(after.result to after.attempts).isEqualTo("IMPORTED" to 2)
        assertThat(promoted(row.id).single().storedVersionId).isEqualTo(stored)
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            assertThat(c.long("select count(*) from files_file where owner_id = ?", row.memoId.toString())).isOne()
            assertThat(c.long("select count(*) from memo_legacy_file where memo_id = ?", row.memoId!!)).isOne()
        }
        assertThat(Audit.entries(tenant.id).count { it.action == "memo.imported" && it.objectId == row.memoId.toString() }).isOne()
        assertThat(batch(id)["state"].asString()).isEqualTo("VERIFYING")
    }

    @Test
    fun `the job waits while the imported originals' renditions are behind, and says so`() {
        val id =
            validated(
                listOf(memoRow("T/1"), memoRow("T/2")),
                contents = mapOf("T/1" to Samples.pdf(1), "T/2" to Samples.pdf(2)),
                extension = "pdf",
            )
        runner.chunkRows = 1
        runner.renditionBacklog = 1
        renditionScheduling.jobs = false
        try {
            started(id)
            assertThat(step(id)).isEqualTo(Duration.ZERO)
            assertThat(step(id)).isEqualTo(ImportRunLimits.THROTTLE_WAIT)
            val waiting = batch(id)["progress"]
            assertThat(waiting["throttled"].asBoolean()).isTrue()
            assertThat(waiting.absent("throttledSince")).isFalse()
            assertThat(runRows(id).getValue("T/2").result).isEqualTo("READY")

            runner.renditionBacklog = ImportRunLimits.RENDITION_BACKLOG
            runJob(id)
            assertThat(batch(id)["progress"]["throttled"].asBoolean()).isFalse()
            assertThat(runRows(id).values.map { it.result }).containsOnly("IMPORTED")
        } finally {
            renditionScheduling.jobs = true
        }
    }

    @Test
    fun `without memo import the import is forbidden, and another tenant's holder finds no batch`() {
        val id = validated(listOf(memoRow("X/1")))
        val stranger = person(CONFIDENTIAL).session
        assertThat(start(id, 1, stranger)).hasStatus(HttpStatus.FORBIDDEN)
        listOf("stop", "resume", "finish").forEach { assertThat(act(id, it, stranger)).describedAs(it).hasStatus(HttpStatus.FORBIDDEN) }

        val other = registry.aTestTenant(transactions, settings, "import-other", mfaRequired = false)
        val (_, otherSession) = imports.importer(mvc, other)
        listOf("start", "stop", "resume", "finish").forEach { action ->
            val result =
                mvc.sendJson(HttpMethod.POST, other, "/api/v1/admin/imports/$id/$action", otherSession, """{"expectedRows":1}""", IF_MATCH)
            assertThat(result).describedAs(action).hasStatus(HttpStatus.NOT_FOUND)
        }
        assertThat(batch(id)["state"].asString()).isEqualTo("VALIDATED")
        // The job of another tenant does nothing to this batch: it is not there.
        assertThat(TenantContext.call(other.id) { runner.step(UUID.fromString(id)) }).isNull()
        assertThat(runRows(id).getValue("X/1").result).isEqualTo("READY")
    }

    /** Gives the General type a new published version that keeps records for [years]. */
    private fun retainGeneralFor(years: Int) {
        val checker = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
        val type = generalTypeId()
        val published = asUser(admin) { memoTypes.published(type)!! }
        val draft =
            asUser(admin) { typeLifecycle.newDraft(type, published.settings.copy(retention = Retention(years, RetentionFrom.CLOSURE))) }
        asUser(admin) { typeLifecycle.submit(type, draft.number, draft.revision) }
        asUser(checker) { typeLifecycle.approve(type, draft.number, null) }
    }

    /** Marks [rowId] as held by a job since [since], as a job that died would leave it. */
    private fun holdRow(
        rowId: UUID,
        since: OffsetDateTime,
    ) = transactions.inTenant(tenant.id) {
        tenantDsl
            .current()
            .update(IMPORTS_ROW)
            .set(IMPORTS_ROW.RESULT, "IMPORTING")
            .set(IMPORTS_ROW.ATTEMPTS, 1)
            .set(IMPORTS_ROW.UPDATED_AT, since)
            .where(IMPORTS_ROW.ID.eq(rowId))
            .execute()
    }

    /** Row counts of the tables an import must never write: workflow, tasks, notifications, watches, final records. */
    private fun untouchedTables(): Map<String, Long> =
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            UNTOUCHED.associateWith { table -> c.long("select count(*) from $table") }
        }

    private companion object {
        const val YEARS = 25
        const val DAYS_A_YEAR = 365L

        /** Longer than the tenant's default ten years: the type's 25 apply. */
        const val LONGER = (YEARS - 1) * DAYS_A_YEAR
        val IF_MATCH = mapOf("If-Match" to "\"0\"")
        val UNTOUCHED =
            listOf(
                "workflow_instance",
                "workflow_task",
                "workflow_action",
                "notification_item",
                "notification_mail_outbox",
                "memo_watch",
                "memo_watch_event",
                "memo_rendition",
                "memo_approval",
                "memo_note",
                "memo_receipt",
            )
    }
}
