package com.memo.imports

import com.memo.common.tenant.TenantContext
import com.memo.imports.internal.dryrun.DryRunValidator
import com.memo.imports.internal.dryrun.StageFindings
import com.memo.jooq.tables.references.IMPORTS_BATCH
import com.memo.jooq.tables.references.IMPORTS_ROW
import com.memo.jooq.tables.references.IMPORTS_ROW_FILE
import com.memo.memo.internal.MemoNumberStore
import com.memo.memo.internal.NumberKey
import com.memo.memo.internal.Numbering
import com.memo.org.OrgDirectory
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.AppRoleJdbc.long
import com.memo.testsupport.Audit
import com.memo.testsupport.Formula
import com.memo.testsupport.ImportSheets
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.onHost
import com.memo.testsupport.sendJson
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import java.sql.SQLException
import java.time.LocalDate
import java.util.HexFormat
import java.util.UUID

/**
 * The dry run (ADR-0055, design E06 §4.1, §5.2, §9, §10; test plan E06 T6 `DryRunIT`): it writes nothing but staging,
 * validates in read-only transactions, refuses each bad row with its code while the good ones are READY, matches paths
 * exactly or by case, checks rows against each other, the live series and earlier imports, goes stale when anything it
 * read changes, fails cleanly, and is open to holders of `memo.import` in their own tenant only.
 */
class DryRunIT : DryRunTest() {
    @Autowired lateinit var validator: DryRunValidator

    @Autowired lateinit var numberStore: MemoNumberStore

    @Autowired lateinit var directory: OrgDirectory

    private fun pdf(label: String) = text(label)

    /**
     * A batch of seven rows (LEG/1 linked to [linked], LEG/2 in flight, USED/1 in use, [live], LEG/5 not received, LEG/6
     * with an unconfirmed level, LEG/7), dry-run once and its mappings confirmed but the last.
     */
    private fun aBatchOfBadRows(
        linked: String,
        live: String,
    ): String {
        val used = row(number = "USED/1").let { it.copy(source = it.source.copy(sourceCode = "OTHER-DMS")) }
        import(used)
        val id = newBatch()
        attach(
            id,
            workbook(
                memos =
                    listOf(
                        memoRow("LEG/1", email = linked),
                        memoRow("LEG/2", status = "Pending"),
                        memoRow("USED/1"),
                        memoRow(live),
                        memoRow("LEG/5"),
                        memoRow("LEG/6", classification = "Restricted"),
                        memoRow("LEG/7"),
                    ),
                files =
                    listOf(
                        "LEG/1",
                        "LEG/2",
                        "USED/1",
                        live,
                        "LEG/5",
                        "LEG/6",
                        "LEG/7",
                    ).map { fileRow(it, "a/${it.replace('/', '-')}.txt") },
                approvals = listOf(approvalRow("LEG/1")),
            ),
        )
        val received = listOf("LEG/1", "LEG/2", "USED/1", live, "LEG/6", "LEG/7").associate { "a/${it.replace('/', '-')}.txt" to pdf(it) }
        originals(id, received + ("a/LEG-5.txt" to null))

        // The first dry run proposes mappings; nothing can be READY before they are confirmed.
        val first = dryRun(id)
        assertThat(first["state"].asString()).isEqualTo("VALIDATED")
        assertThat(rows(id).values.map { it["result"].asString() }.toSet()).containsExactly("REJECTED")
        assertThat(problems(id, "code=MAPPING_UNCONFIRMED")).isNotEmpty()
        confirmAll(id)
        val statuses = listOf(mapOf("legacyValue" to "Pending", "target" to "NOT_IMPORTED", "confirmed" to true))
        assertThat(saveMappings(id, "STATUS", statuses)).hasStatus(HttpStatus.OK)
        val confidential = levels()[2].id.toString()
        val restricted = listOf(mapOf("legacyValue" to "Restricted", "target" to confidential, "legacyOrder" to 3, "confirmed" to false))
        assertThat(saveMappings(id, "CLASSIFICATION", restricted)).hasStatus(HttpStatus.OK)
        return id
    }

    @Test
    fun `each bad row is refused with its code, the others are READY, and nothing but staging is written`() {
        val live = "M-${LocalDate.now().year}-99999"
        val id = aBatchOfBadRows(person().account.email, live)

        assertThat(requestDryRun(id)).hasStatus(HttpStatus.ACCEPTED)
        val before = businessTables()
        runJob(id)
        assertThat(businessTables()).describedAs("every business table's rows and checksum").isEqualTo(before)

        val rows = rows(id)
        assertThat(rows.mapValues { it.value["result"].asString() }).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "LEG/1" to "READY",
                "LEG/2" to "REJECTED",
                "USED/1" to "REJECTED",
                live to "REJECTED",
                "LEG/5" to "REJECTED",
                "LEG/6" to "REJECTED",
                "LEG/7" to "READY",
            ),
        )
        assertThat(rows.getValue("LEG/2").codes()).contains("IN_FLIGHT_NOT_IMPORTED")
        assertThat(rows.getValue("USED/1").codes()).contains("NUMBER_IN_USE")
        assertThat(rows.getValue(live).codes()).contains("NUMBER_IN_LIVE_SERIES")
        assertThat(rows.getValue("LEG/5").codes()).contains("FILE_NOT_RECEIVED")
        assertThat(rows.getValue("LEG/6").codes()).contains("MAPPING_UNCONFIRMED")
        assertThat(rows.getValue("LEG/7").codes()).containsExactly("PERSON_NOT_LINKED")
        assertThat(rows.getValue("LEG/1")["warnings"].asInt()).describedAs("the approver is named only").isEqualTo(1)

        val view = batch(id)
        assertThat(view["state"].asString()).isEqualTo("VALIDATED")
        assertThat(view["rows"]["READY"].asInt()).isEqualTo(2)
        assertThat(view["dryRunHash"].asString()).hasSize(SHA256_HEX)
        assertThat(view["allowedActions"].items().map { it.asString() }).contains("DRY_RUN", "MAP")
        val maxRank = transactions.readInTenant(tenant.id) { batchRecord(id).maxRank }
        assertThat(maxRank).describedAs("both READY rows are Internal").isEqualTo(1)
        val audited = Audit.assertAudited(tenant.id, "import.dry_run.finished", id, "legacy-import")
        assertThat(audited.canonical).contains("VALIDATED", "inputHash")
        Audit.assertAudited(tenant.id, "import.dry_run.requested", id, holder.toString())
        // A live number's problem names the series' format, never the subject.
        val liveProblem = problems(id, "code=NUMBER_IN_LIVE_SERIES").single()
        assertThat(liveProblem["params"]["format"].asString()).startsWith("M-")
        assertThat(problems(id).flatMap { it["params"].properties().map { p -> p.value.toString() } })
            .noneMatch { it.contains("Rent of the branch") }
    }

    @Test
    fun `a number the live series may still give is refused, in the current or a later period above the counter`() {
        val year = asUser(admin) { directory.today() }.year
        asUser(admin) { numberStore.start(NumberKey(Numbering.GENERAL, year.toString(), ""), COUNTER) }
        val numbers =
            mapOf(
                "M-${year - 1}-00999" to "READY",
                "M-$year-00050" to "READY",
                "M-$year-00500" to "REJECTED",
                "M-${year + 1}-00001" to "REJECTED",
                "X-$year-00500" to "READY",
            )
        val id = newBatch()
        attach(
            id,
            workbook(
                numbers.keys.map { memoRow(it) },
                numbers.keys.mapIndexed { i, number -> fileRow(number, "live/$i.txt") },
            ),
        )
        originals(id, numbers.keys.withIndex().associate { (i, number) -> "live/$i.txt" to pdf(number) })
        confirmedDryRun(id)
        val rows = rows(id)
        assertThat(rows.mapValues { it.value["result"].asString() }).containsExactlyInAnyOrderEntriesOf(numbers)
        numbers.filterValues { it == "REJECTED" }.keys.forEach { assertThat(rows.getValue(it).codes()).contains("NUMBER_IN_LIVE_SERIES") }

        // A batch prefix takes the numbers out of the series' format.
        val settings = """{"name":"Legacy","sourceCode":"LEGACY-DMS","timeZone":"Asia/Dhaka","numberPrefix":"OLD/"}"""
        assertThat(put("/api/v1/admin/imports/$id", session, settings, batch(id)["version"].asLong())).hasStatus(HttpStatus.OK)
        dryRun(id)
        assertThat(rows(id).values.map { it["result"].asString() }.toSet()).containsExactly("READY")
    }

    @Test
    fun `validation runs only in read-only transactions, where a write fails with SQLSTATE 25006`() {
        val id = UUID.fromString(newBatch())
        val write =
            catchThrowable {
                transactions.readInTenant(tenant.id) {
                    tenantDsl
                        .current()
                        .update(IMPORTS_BATCH)
                        .set(IMPORTS_BATCH.NAME, "x")
                        .where(IMPORTS_BATCH.ID.eq(id))
                        .execute()
                }
            }
        assertThat(write).isInstanceOf(DataAccessException::class.java)
        assertThat(((write as DataAccessException).cause as? SQLException)?.sqlState ?: write.sqlState()).isEqualTo("25006")

        // The validator itself refuses a transaction that could write (here it would join the caller's).
        val refused =
            catchThrowable {
                TenantContext.call(tenant.id) { transactions.inTenant(tenant.id) { validator.validate(id, StageFindings()) } }
            }
        assertThat(refused).isInstanceOf(IllegalStateException::class.java).hasMessageContaining("read-only")
    }

    @Test
    fun `paths match exactly or by case, ambiguous and missing ones refuse their row, and unused originals are listed`() {
        val id = newBatch()
        attach(
            id,
            workbook(
                memos = (1..5).map { memoRow("P/$it") },
                files =
                    listOf(
                        fileRow("P/1", "docs/exact.txt"),
                        fileRow("P/2", "DOCS/CASE.TXT"),
                        fileRow("P/3", "docs/twin.txt"),
                        fileRow("P/4", "nowhere/missing.txt"),
                        fileRow("P/5", "docs\\windows.txt"),
                    ),
            ),
        )
        originals(
            id,
            mapOf(
                "docs/exact.txt" to pdf("exact"),
                "docs/case.txt" to pdf("case"),
                "Docs/Twin.txt" to pdf("twin 1"),
                "docs/TWIN.txt" to pdf("twin 2"),
                "docs/windows.txt" to pdf("windows"),
                "spare/unused.txt" to pdf("unused"),
            ),
        )
        confirmedDryRun(id)

        assertThat(transactions.readInTenant(tenant.id) { matches(id) }).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "docs/exact.txt" to "EXACT",
                "DOCS/CASE.TXT" to "CASE",
                "docs/twin.txt" to "AMBIGUOUS",
                "nowhere/missing.txt" to "NONE",
                "docs/windows.txt" to "EXACT",
            ),
        )
        val rows = rows(id)
        assertThat(rows.getValue("P/1")["result"].asString()).isEqualTo("READY")
        assertThat(rows.getValue("P/2")["result"].asString()).isEqualTo("READY")
        assertThat(rows.getValue("P/2").codes()).contains("PATH_CASE_MATCH")
        assertThat(rows.getValue("P/3").codes()).contains("FILE_AMBIGUOUS")
        assertThat(rows.getValue("P/4").codes()).contains("FILE_NOT_RECEIVED")
        assertThat(rows.getValue("P/5")["result"].asString()).isEqualTo("READY")

        val files = problems(id, "level=FILE")
        assertThat(files.map { it["code"].asString() to it["params"]["path"].asString() }).containsExactlyInAnyOrder(
            "FILE_UNREFERENCED" to "spare/unused.txt",
            "FILE_UNREFERENCED" to "Docs/Twin.txt",
            "FILE_UNREFERENCED" to "docs/TWIN.txt",
            "PATH_CASE_ONLY" to "Docs/Twin.txt",
            "PATH_CASE_ONLY" to "docs/TWIN.txt",
        )
        assertThat(files.map { it["sheet"].asString() }.toSet()).containsExactly("ORIGINALS")
    }

    /** A workbook with duplicate, similar, numeric, formula, shared, hash-mismatched, malformed and orphan rows. */
    private fun crossRowWorkbook(): ByteArray =
        ImportSheets.workbook(
            mapOf(
                "Memos" to
                    listOf(ImportSheets.MEMO_HEADER) +
                    listOf(
                        memoRow("DUP/1"),
                        memoRow("dup/1"),
                        memoRow("SIM-2"),
                        memoRow("SIM/2"),
                        memoRow(12_345),
                        memoRow("FX/1", subject = "").toMutableList().also { it[2] = Formula("UPPER(\"rent\")", 0.0) },
                        memoRow("SH/1"),
                        memoRow("SH/2"),
                        memoRow("HASH/1"),
                        memoRow("BAD/1", raised = "14/03/2019", decided = "2019-03-01"),
                    ),
                "Files" to
                    listOf(ImportSheets.FILE_HEADER) +
                    listOf(
                        fileRow("DUP/1", "dup1.txt"),
                        fileRow("dup/1", "dup2.txt"),
                        fileRow("SIM-2", "sim1.txt"),
                        fileRow("SIM/2", "sim2.txt"),
                        fileRow("12345", "number.txt"),
                        fileRow("FX/1", "formula.txt"),
                        fileRow("SH/1", "shared-a.txt"),
                        fileRow("SH/2", "shared-b.txt"),
                        fileRow("HASH/1", "hash.txt", sha256 = "0".repeat(SHA256_HEX)),
                        fileRow("BAD/1", "bad.txt", role = "COVER"),
                        fileRow("NOBODY/1", "orphan.txt"),
                    ),
                "Approvals" to listOf(ImportSheets.APPROVAL_HEADER, approvalRow("NOBODY/2")),
            ),
        )

    @Test
    fun `rows are checked against each other and their cells, and a file must be received whole and as listed`() {
        val id = newBatch()
        val shared = pdf("shared")
        attach(id, crossRowWorkbook())
        originals(
            id,
            mapOf(
                "dup1.txt" to pdf("d1"),
                "dup2.txt" to pdf("d2"),
                "sim1.txt" to pdf("s1"),
                "sim2.txt" to pdf("s2"),
                "number.txt" to pdf("n"),
                "formula.txt" to pdf("f"),
                "shared-a.txt" to shared,
                "shared-b.txt" to shared,
                "hash.txt" to pdf("good"),
                "bad.txt" to pdf("b"),
            ),
        )
        confirmedDryRun(id)

        val rows = transactions.readInTenant(tenant.id) { rowsByNumber(id) }
        val codes = problems(id).groupBy({ it["rowNumber"]?.asInt() to it["sheet"]?.asString() }, { it["code"].asString() })

        fun memo(n: Int) = codes[n to "MEMOS"].orEmpty()
        assertThat(memo(2)).contains("NUMBER_DUPLICATE_IN_BATCH")
        assertThat(memo(3)).contains("NUMBER_DUPLICATE_IN_BATCH")
        assertThat(memo(4)).contains("NUMBER_SIMILAR").doesNotContain("NUMBER_DUPLICATE_IN_BATCH")
        assertThat(rows.getValue(4)).describedAs("similar numbers are only listed").isEqualTo("READY")
        assertThat(memo(6)).contains("NUMBER_NOT_TEXT")
        assertThat(memo(7)).contains("FORMULA_CACHED")
        assertThat(memo(8) + memo(9)).contains("FILE_SHARED")
        assertThat(codes[10 to "FILES"].orEmpty()).contains("FILE_HASH_MISMATCH")
        assertThat(memo(11)).contains("DATE_INVALID")
        assertThat(codes[11 to "FILES"].orEmpty()).contains("VALUE_INVALID")
        assertThat(codes[12 to "FILES"].orEmpty()).containsExactly("ORPHAN_ROW")
        assertThat(codes[2 to "APPROVALS"].orEmpty()).containsExactly("ORPHAN_ROW")
        assertThat(rows.getValue(8)).isEqualTo("READY")
        assertThat(rows.getValue(10)).isEqualTo("REJECTED")
    }

    @Test
    fun `a row imported before is skipped when unchanged and refused when changed`() {
        val id = newBatch()
        attach(id, workbook(listOf(memoRow("OLD/1"), memoRow("OLD/2")), listOf(fileRow("OLD/1", "old1.txt"), fileRow("OLD/2", "old2.txt"))))
        originals(id, mapOf("old1.txt" to pdf("old 1"), "old2.txt" to pdf("old 2")))
        confirmedDryRun(id)
        val fingerprints =
            transactions.readInTenant(tenant.id) {
                tenantDsl
                    .current()
                    .select(IMPORTS_ROW.LEGACY_NUMBER, IMPORTS_ROW.FINGERPRINT)
                    .from(IMPORTS_ROW)
                    .where(IMPORTS_ROW.BATCH_ID.eq(UUID.fromString(id)))
                    .fetch { it.value1()!! to HexFormat.of().formatHex(it.value2()!!) }
                    .toMap()
            }
        // Earlier imports under the same row key: one with this row's fingerprint, one with another.
        listOf("OLD/1" to fingerprints.getValue("OLD/1"), "OLD/2" to "f".repeat(SHA256_HEX)).forEach { (number, fingerprint) ->
            val earlier = row(number = "$number-x")
            import(earlier.copy(source = earlier.source.copy(legacyNumber = number, fingerprint = fingerprint)))
        }
        dryRun(id)
        val rows = rows(id)
        assertThat(rows.getValue("OLD/1")["result"].asString()).isEqualTo("READY")
        assertThat(rows.getValue("OLD/1").codes()).contains("ALREADY_IMPORTED").doesNotContain("NUMBER_IN_USE")
        assertThat(problems(id, "code=ALREADY_IMPORTED").single()["level"].asString()).isEqualTo("INFO")
        assertThat(rows.getValue("OLD/2")["result"].asString()).isEqualTo("REJECTED")
        assertThat(rows.getValue("OLD/2").codes()).contains("ALREADY_IMPORTED_CHANGED")
    }

    @Test
    fun `a validated batch goes back to OPEN when a setting, a source, an original or a mapping changes`() {
        val id = newBatch()
        val book = workbook(listOf(memoRow("ST/1")), listOf(fileRow("ST/1", "st.txt")))
        attach(id, book)
        val intake = originals(id, mapOf("st.txt" to pdf("st")))
        dryRun(id)
        confirmAll(id)

        fun validated(): String {
            val view = dryRun(id)
            assertThat(view["state"].asString()).isEqualTo("VALIDATED")
            return view["dryRunHash"].asString()
        }
        val hash = validated()

        val settings = """{"name":"Legacy","sourceCode":"LEGACY-DMS","timeZone":"Asia/Dhaka","numberPrefix":"L-"}"""
        assertThat(put("/api/v1/admin/imports/$id", session, settings, batch(id)["version"].asLong())).hasStatus(HttpStatus.OK)
        assertThat(batch(id)["state"].asString()).isEqualTo("OPEN")
        assertThat(validated()).describedAs("the prefix is in the input hash").isNotEqualTo(hash)

        attach(id, book)
        assertThat(batch(id)["state"].asString()).isEqualTo("OPEN")
        validated()

        assertThat(upload(session, pdf("st again"), "/api/v1/files?purpose=imports.intake&owner=${intake.getValue("st.txt")}", "st.txt"))
            .hasStatus(HttpStatus.CREATED)
        assertThat(batch(id)["state"].asString()).isEqualTo("OPEN")
        validated()

        val mapping = mappings(id)["mappings"].items().first { it["kind"].asString() == "STATUS" }
        val entry = mapOf("legacyValue" to mapping["legacyValue"].asString(), "target" to "CLOSED", "confirmed" to true)
        assertThat(saveMappings(id, "STATUS", listOf(entry))).hasStatus(HttpStatus.OK)
        assertThat(batch(id)["state"].asString()).isEqualTo("OPEN")
        val closed = dryRun(id)
        assertThat(closed["state"].asString()).describedAs("CLOSED needs a closing date").isEqualTo("VALIDATED")
        assertThat(rows(id).getValue("ST/1").codes()).contains("VALUE_REQUIRED")
    }

    @Test
    fun `a dry run starts only from OPEN or VALIDATED with its sources, and once while it runs`() {
        val id = newBatch()
        assertThat(requestDryRun(id).json()["reason"].asString()).isEqualTo("SOURCE_MISSING")
        attach(id, workbook(listOf(memoRow("SM/1")), listOf(fileRow("SM/1", "sm.txt"))))
        val started = requestDryRun(id)
        assertThat(started).hasStatus(HttpStatus.ACCEPTED)
        assertThat(started.json()["state"].asString()).isEqualTo("DRY_RUN")
        val again = requestDryRun(id)
        assertThat(again).hasStatus(HttpStatus.ACCEPTED)
        assertThat(again.json()["version"].asLong()).describedAs("no second run").isEqualTo(started.json()["version"].asLong())
        // While it runs, nothing it reads may change.
        val body = listOf(mapOf("legacyValue" to "Approved", "target" to "APPROVED", "confirmed" to true))
        assertThat(
            put("/api/v1/admin/imports/$id/mappings/STATUS", session, json.writeValueAsString(body), again.json()["version"].asLong()),
        ).hasStatus(HttpStatus.CONFLICT)
        runJob(id)
        assertThat(batch(id)["state"].asString()).isEqualTo("VALIDATED")

        listOf("IMPORTING", "STOPPING", "STOPPED", "VERIFYING", "COMPLETED", "CLOSED", "ABANDONED").forEach { state ->
            setState(id, state)
            val refused = requestDryRun(id)
            assertThat(refused).describedAs(state).hasStatus(HttpStatus.CONFLICT)
            assertThat(refused.json()["reason"].asString()).isEqualTo("WRONG_STATE")
        }
        setState(id, "OPEN")
        assertThat(requestDryRun(id)).hasStatus(HttpStatus.ACCEPTED)
        // A job for a batch no longer in DRY_RUN does nothing.
        setState(id, "OPEN")
        runJob(id)
        assertThat(batch(id)["state"].asString()).isEqualTo("OPEN")
    }

    @Test
    fun `a failing dry run is retried, and after its last attempt the batch is OPEN with DRY_RUN_FAILED`() {
        val id = newBatch()
        attach(id, workbook(listOf(memoRow("F/1")), listOf(fileRow("F/1", "f.txt"))))
        assertThat(requestDryRun(id)).hasStatus(HttpStatus.ACCEPTED)
        // The source's version no longer exists: reading it fails.
        transactions.inTenant(tenant.id) {
            tenantDsl
                .current()
                .update(IMPORTS_BATCH)
                .set(IMPORTS_BATCH.WORKBOOK_VERSION_ID, UUID.randomUUID())
                .where(IMPORTS_BATCH.ID.eq(UUID.fromString(id)))
                .execute()
        }
        assertThat(catchThrowable { runJob(id, failures = 0) }).isNotNull()
        assertThat(batch(id)["state"].asString()).describedAs("retried later").isEqualTo("DRY_RUN")
        runJob(id, failures = 2)
        val view = batch(id)
        assertThat(view["state"].asString()).isEqualTo("OPEN")
        assertThat(problems(id).map { it["code"].asString() }).containsExactly("DRY_RUN_FAILED")
        assertThat(view["problems"]["BATCH"].asInt()).isEqualTo(1)
    }

    @Test
    fun `a batch problem or an unknown field column stops the dry run and leaves the batch OPEN`() {
        val id = newBatch()
        val header = ImportSheets.MEMO_HEADER + "field:nosuchfield"
        attach(id, workbook(listOf(memoRow("U/1") + "x"), listOf(fileRow("U/1", "u.txt")), memoHeader = header))
        val view = dryRun(id)
        assertThat(view["state"].asString()).isEqualTo("OPEN")
        val problem = problems(id).single()
        assertThat(problem["code"].asString()).isEqualTo("COLUMN_UNKNOWN")
        assertThat(problem["level"].asString()).isEqualTo("BATCH")
        assertThat(problem["column"].asString()).isEqualTo("field:nosuchfield")
        assertThat(view["rows"].size()).describedAs("no rows staged").isZero()

        attach(id, ImportSheets.workbook(mapOf("Memos" to listOf(listOf("legacy_number")))))
        assertThat(dryRun(id)["state"].asString()).isEqualTo("OPEN")
        val missing = problems(id)
        assertThat(missing.map { it["code"].asString() }).contains("SHEET_MISSING")
        assertThat(missing.map { it["level"].asString() }.toSet()).containsExactly("BATCH")
    }

    @Test
    fun `problems filter by level, code, sheet and row, page by cursor, and rows show subjects only within clearance`() {
        val id = newBatch()
        attach(
            id,
            workbook(
                listOf(memoRow("C/1", classification = "Public"), memoRow("C/2", classification = "Confidential"), memoRow("C/3")),
                listOf(fileRow("C/1", "c1.txt"), fileRow("C/2", "c2.txt"), fileRow("C/3", "missing.txt")),
            ),
        )
        originals(id, mapOf("c1.txt" to pdf("c1"), "c2.txt" to pdf("c2")))
        confirmedDryRun(id)

        val all = problems(id)
        assertThat(problems(id, "level=ERROR").map { it["level"].asString() }.toSet()).containsExactly("ERROR")
        assertThat(problems(id, "code=FILE_NOT_RECEIVED").single()["sheet"].asString()).isEqualTo("FILES")
        assertThat(problems(id, "sheet=FILES&row=4").single()["code"].asString()).isEqualTo("FILE_NOT_RECEIVED")
        assertThat(problems(id, "level=WARNING&code=PERSON_NOT_LINKED")).hasSize(3)
        val paged = problems(id, "limit=1")
        assertThat(paged.map { it["id"].asString() }).containsExactlyElementsOf(all.map { it["id"].asString() })
        val page = read("/api/v1/admin/imports/$id/problems?limit=1", session).json()
        assertThat(page["byLevel"]["WARNING"].asInt()).isEqualTo(3)
        assertThat(page["byCode"]["FILE_NOT_RECEIVED"].asInt()).isEqualTo(1)
        assertThat(read("/api/v1/admin/imports/$id/problems?cursor=nonsense", session)).hasStatus(HttpStatus.BAD_REQUEST)

        // The holder has no clearance: only the Public row's subject shows.
        val rows = rows(id)
        assertThat(rows.getValue("C/1")["subject"].asString()).isEqualTo("Rent of the branch at plot 7")
        assertThat(rows.getValue("C/1")["restricted"].asBoolean()).isFalse()
        assertThat(rows.getValue("C/2").absent("subject")).isTrue()
        assertThat(rows.getValue("C/2")["restricted"].asBoolean()).isTrue()
        val rejected = read("/api/v1/admin/imports/$id/rows?result=REJECTED", session).json()["rows"].items()
        assertThat(rejected.map { it["legacyNumber"].asString() }).containsExactly("C/3")
        val first = read("/api/v1/admin/imports/$id/rows?limit=2", session).json()
        val rest = read("/api/v1/admin/imports/$id/rows?limit=2&cursor=${first["next"].asString()}", session).json()
        assertThat(first["rows"].items().map { it["rowNumber"].asInt() } + rest["rows"].items().map { it["rowNumber"].asInt() })
            .containsExactly(2, 3, 4)
    }

    @Test
    fun `without memo import every endpoint is forbidden, and another tenant sees nothing of a dry run`() {
        val id = newBatch()
        attach(id, workbook(listOf(memoRow("T/1")), listOf(fileRow("T/1", "t.txt")), listOf(approvalRow("T/1"))))
        originals(id, mapOf("t.txt" to pdf("t")))
        dryRun(id)
        val stranger = person().session
        listOf(
            post("/api/v1/admin/imports/$id/dry-run", stranger),
            read("/api/v1/admin/imports/$id/problems", stranger),
            read("/api/v1/admin/imports/$id/rows", stranger),
            read("/api/v1/admin/imports/$id/mappings", stranger),
            put("/api/v1/admin/imports/$id/mappings/STATUS", stranger, "[]", 0),
        ).forEach { assertThat(it).hasStatus(HttpStatus.FORBIDDEN) }

        val other = registry.aTestTenant(transactions, settings, "dry-run-other", mfaRequired = false)
        val (_, otherSession) = imports.importer(mvc, other)

        fun foreign(
            method: HttpMethod,
            uri: String,
            body: String = "{}",
            headers: Map<String, String> = emptyMap(),
        ) = mvc.sendJson(method, other, uri, otherSession, body, headers)
        listOf(
            foreign(HttpMethod.POST, "/api/v1/admin/imports/$id/dry-run"),
            foreign(HttpMethod.PUT, "/api/v1/admin/imports/$id/mappings/STATUS", "[]", mapOf("If-Match" to "\"0\"")),
        ).forEach { assertThat(it).hasStatus(HttpStatus.NOT_FOUND) }
        listOf("problems", "rows", "mappings").forEach { part ->
            val result =
                mvc
                    .get()
                    .uri("/api/v1/admin/imports/$id/$part")
                    .with(onHost(other.slug))
                    .cookie(otherSession)
                    .exchange()
            assertThat(result).describedAs(part).hasStatus(HttpStatus.NOT_FOUND)
        }
        AppRoleJdbc.inTransaction(other.id) { c ->
            listOf("imports_row", "imports_row_file", "imports_row_approval", "imports_mapping", "imports_problem").forEach { table ->
                assertThat(c.long("select count(*) from $table where tenant_id = ?", tenant.id.value)).describedAs(table).isZero()
            }
        }
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            assertThat(c.long("select count(*) from imports_row_approval where batch_id = ?", UUID.fromString(id))).isEqualTo(1)
        }
    }

    private fun batchRecord(id: String) =
        tenantDsl
            .current()
            .selectFrom(IMPORTS_BATCH)
            .where(IMPORTS_BATCH.ID.eq(UUID.fromString(id)))
            .fetchSingle()

    private fun matches(id: String): Map<String, String?> =
        tenantDsl
            .current()
            .select(IMPORTS_ROW_FILE.PATH, IMPORTS_ROW_FILE.MATCH)
            .from(IMPORTS_ROW_FILE)
            .where(IMPORTS_ROW_FILE.BATCH_ID.eq(UUID.fromString(id)))
            .fetch { it.value1()!! to it.value2() }
            .toMap()

    private fun rowsByNumber(id: String): Map<Int, String> =
        tenantDsl
            .current()
            .select(IMPORTS_ROW.ROW_NUMBER, IMPORTS_ROW.RESULT)
            .from(IMPORTS_ROW)
            .where(IMPORTS_ROW.BATCH_ID.eq(UUID.fromString(id)))
            .fetch { it.value1()!! to it.value2()!! }
            .toMap()

    /**
     * Every table under row-level security outside the imports module and the audit log that the application role reads, as
     * the tenant sees it: its row count and a checksum of its rows (the dry run may write only staging rows, problems,
     * mappings and the batch). The search tables, which the role reaches only through their functions, are left out.
     */
    private fun businessTables(): Map<String, String> =
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            val tables =
                c
                    .prepareStatement(
                        "select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace " +
                            "where n.nspname = current_schema() and c.relkind in ('r', 'p') and c.relrowsecurity " +
                            "and has_table_privilege(c.oid, 'SELECT') " +
                            "and c.relname not like 'imports\\_%' and c.relname not like 'audit%' order by 1",
                    ).use { st -> st.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() } }
            assertThat(tables).describedAs("tables under RLS").contains("memo", "memo_legacy")
            tables.associateWith { table ->
                // The rendition worker updates its rows (state, attempts) for the originals uploaded above, at any moment;
                // only a row added or removed there would be the dry run's.
                val checksum = if (table == WORKER_TABLE) "''" else "coalesce(md5(string_agg(t::text, '|' order by t::text)), '')"
                c
                    .prepareStatement(
                        "select count(*) || ':' || $checksum from $table t",
                    ).use { st ->
                        st.executeQuery().use { rs ->
                            rs.next()
                            rs.getString(1)
                        }
                    }
            }
        }

    private companion object {
        const val SHA256_HEX = 64
        const val COUNTER = 100L
        const val WORKER_TABLE = "files_rendition"
    }
}
