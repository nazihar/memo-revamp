package com.memo.imports

import com.memo.imports.internal.report.ImportEndLimits
import com.memo.jooq.tables.references.IMPORTS_BATCH
import com.memo.jooq.tables.references.IMPORTS_FILE
import com.memo.jooq.tables.references.IMPORTS_ROW
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.AppRoleJdbc.long
import com.memo.testsupport.Audit
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.onHost
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.util.HexFormat
import java.util.UUID

/**
 * Verification and the reconciliation report (ADR-0055, design E06 §5.4, §8, §10, §12; test plan E06 T8 `ReconciliationIT`):
 * the verification re-reads every imported memo and original and flags what differs; the report waits for the
 * renditions or the 72-hour cap, balances, keeps its parts and the sources as records with their hashes in
 * `import.batch.completed`, prefixes cells a spreadsheet would run, lists every stored original in its manifest, and is
 * read only by holders of `memo.import` or `audit.read` cleared to the batch's highest level, in the batch's tenant.
 */
class ReconciliationIT : ImportEndTest() {
    @Test
    fun `a verified batch's report balances, keeps its parts and sources as records, and names every stored original`() {
        val formula = "=HYPERLINK(\"x\")"
        val id = imported(listOf(memoRow("REC/1"), memoRow("REC/2", subject = formula)))
        assertThat(report(id).json()["reason"].asString()).describedAs("not before completion").isEqualTo("REPORT_NOT_READY")

        verify(id)
        val verified = batch(id)
        assertThat(verified.absent("verifiedAt")).isFalse()
        assertThat(Audit.assertAudited(tenant.id, "import.batch.verified", id, "legacy-import").canonical)
            .contains("\"memos\":2", "\"originals\":2", "\"mismatches\":0")
        assertThat(reportStep(id)).describedAs("renditions pending").isEqualTo(ImportEndLimits.REPORT_WAIT)
        assertThat(batch(id)["state"].asString()).isEqualTo("VERIFYING")

        val done = completed(id)
        assertThat(done["report"]["balanced"].asBoolean()).isTrue()
        assertThat(done["allowedActions"].items().map { it.asString() }).containsExactly("CLOSE")
        val report = ok(report(id))
        assertThat(listOf("balanced", "complete", "valid").map { report[it].asBoolean() }).containsOnly(true)
        assertThat(report["failing"].items()).isEmpty()
        val totals = report["totals"]
        assertThat(listOf("read", "imported", "rejected").map { totals["rows"][it].asInt() }).containsExactly(2, 2, 0)
        assertThat(totals["originals"]["imported"]["files"].asInt()).isEqualTo(2)
        assertThat(totals["renditions"]["TEXT"]["done"].asInt()).isEqualTo(2)
        assertThat(totals["fixity"]["verified"].asInt()).isEqualTo(2)
        val parts = report["parts"].items()
        assertThat(parts.map { it["kind"].asString() }).containsExactlyInAnyOrder("SUMMARY", "DETAIL", "MANIFEST", "SOURCE_WORKBOOK")
        parts.forEach { part ->
            val stored = transactions.readInTenant(tenant.id) { files.version(UUID.fromString(part["versionId"].asString()))!! }
            val file = transactions.readInTenant(tenant.id) { files.info(stored.fileId)!! }
            assertThat(file.purpose to file.ownerId).isEqualTo("imports.record" to id)
            assertThat(stored.retainUntil).describedAs("a record").isNotNull()
            assertThat(stored.sha256).isEqualTo(part["sha256"].asString())
        }
        val source = parts.single { it["kind"].asString() == "SOURCE_WORKBOOK" }["sha256"].asString()
        assertThat(source).describedAs("the workbook used, kept").isEqualTo(verified["sources"].items().single()["sha256"].asString())
        val completed = Audit.assertAudited(tenant.id, "import.batch.completed", id, "legacy-import").canonical
        parts.forEach { assertThat(completed).contains(it["sha256"].asString()) }
        assertThat(completed).doesNotContain("HYPERLINK", "Rent of the branch")

        val manifest =
            part(id, "MANIFEST")
                .response.contentAsString
                .lines()
                .filter { it.isNotBlank() }
        assertThat(manifest).containsExactlyInAnyOrder(
            "${sha256(text("REC/1"))}  ${pathOf("REC/1")}",
            "${sha256(text("REC/2"))}  ${pathOf("REC/2")}",
        )
        Loader.loadPDF(part(id, "SUMMARY").response.contentAsByteArray).use { pdf ->
            val text = PDFTextStripper().getText(pdf)
            assertThat(text).contains("Reconciliation report", "BALANCED", "LEGACY-DMS").doesNotContain("Rent of the branch", "HYPERLINK")
        }
        XSSFWorkbook(part(id, "DETAIL").response.contentAsByteArray.inputStream()).use { book ->
            assertThat((0 until book.numberOfSheets).map { book.getSheetName(it) })
                .containsExactly("Summary", "Rows", "Files", "Originals", "Problems", "Mappings")
            val rows = book.getSheet("Rows")
            assertThat(rows.lastRowNum).isEqualTo(2)
            val subject = (0 until rows.getRow(0).lastCellNum).single { rows.getRow(0).getCell(it).stringCellValue == "Subject" }
            assertThat(rows.getRow(2).getCell(subject).stringCellValue).describedAs("prefixed").isEqualTo("'$formula")
            assertThat(book.getSheet("Files").lastRowNum).isEqualTo(2)
        }
        Audit.assertAudited(tenant.id, "file.opened", parts.single { it["kind"].asString() == "SUMMARY" }["versionId"].asString())
    }

    @Test
    fun `verification flags a memo whose facts differ and an original whose content differs, and the report says so`() {
        val id = imported(listOf(memoRow("T/1"), memoRow("T/2")))
        val rows = runRows(id)
        // T/1's original now differs from what intake received; T/2's memo no longer has the row's facts.
        val t1 = promoted(rows.getValue("T/1").id).single()
        transactions.inTenant(tenant.id) {
            val dsl = tenantDsl.current()
            dsl
                .update(IMPORTS_FILE)
                .set(IMPORTS_FILE.SHA256, HexFormat.of().parseHex(sha256("tampered".toByteArray())))
                .where(IMPORTS_FILE.VERSION_ID.eq(t1.intakeVersionId))
                .execute()
            dsl
                .update(IMPORTS_ROW)
                .set(IMPORTS_ROW.FINGERPRINT, ByteArray(FINGERPRINT_BYTES))
                .where(IMPORTS_ROW.ID.eq(rows.getValue("T/2").id))
                .execute()
        }

        val done = completed(id)
        assertThat(problems(id, "code=FIXITY_MISMATCH").single().let { listOf(it["stage"], it["sheet"]).map { v -> v.asString() } })
            .containsExactly("VERIFY", "FILES")
        val differs = problems(id, "code=VERIFY_FACTS_DIFFER").single()
        assertThat(differs["rowId"].asString()).isEqualTo(rows.getValue("T/2").id.toString())
        assertThat(differs["params"]["fields"].items().map { it.asString() }).containsExactly("fingerprint")
        assertThat(Audit.assertAudited(tenant.id, "memo.legacy.mismatch", rows.getValue("T/1").memoId.toString()).canonical)
            .contains(sha256("tampered".toByteArray()))
        assertThat(Audit.assertAudited(tenant.id, "import.batch.verified", id).canonical).contains("\"mismatches\":1")

        assertThat(done["report"]["balanced"].asBoolean()).isFalse()
        val report = ok(report(id))
        assertThat(report["failing"].items().map { it.asString() }).containsExactly("FIXITY")
        assertThat(report["valid"].asBoolean()).isFalse()
        assertThat(report["totals"]["fixity"]["mismatches"].asInt()).isOne()
        Loader.loadPDF(part(id, "SUMMARY").response.contentAsByteArray).use { pdf ->
            assertThat(PDFTextStripper().getText(pdf)).contains("UNBALANCED: FIXITY")
        }
    }

    @Test
    fun `the report waits for renditions, and at the 72-hour cap is built with the pending ones stated`() {
        val id = imported(listOf(memoRow("W/1"), memoRow("W/2")), mapOf())
        verify(id)
        assertThat(reportStep(id)).isEqualTo(ImportEndLimits.REPORT_WAIT)
        backdate(id, IMPORTS_BATCH.FINISHED_AT, ImportEndLimits.REPORT_MAX_WAIT.plusMinutes(1))

        assertThat(reportStep(id)).isNull()
        assertThat(batch(id)["state"].asString()).isEqualTo("COMPLETED")
        val renditions = ok(report(id))["totals"]["renditions"]
        assertThat(listOf("PDF", "TEXT").map { renditions[it]["pending"].asInt() }).containsExactly(2, 2)
        assertThat(ok(report(id))["balanced"].asBoolean()).describedAs("pending is a stated category").isTrue()
        assertThat(reportStep(id)).describedAs("stored once").isNull()
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            assertThat(c.long("select count(*) from files_file where purpose = 'imports.record' and owner_id = ?", id)).isEqualTo(4)
        }
    }

    @Test
    fun `only holders of memo import or audit read cleared to the batch's highest level read the report and its parts`() {
        val id = imported(listOf(memoRow("C/1", classification = "Confidential")))
        completed(id)
        assertThat(batch(id)["maxRank"].asInt()).isEqualTo(CONFIDENTIAL)
        assertThat(report(id)).hasStatus(HttpStatus.OK)
        assertThat(part(id, "DETAIL")).hasStatus(HttpStatus.OK)

        val (_, uncleared) = imports.importer(mvc, tenant)
        assertThat(report(id, uncleared)).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(part(id, "DETAIL", uncleared)).hasStatus(HttpStatus.FORBIDDEN)
        val (auditor, auditorSession) = imports.auditor(mvc, tenant)
        assertThat(report(id, auditorSession)).hasStatus(HttpStatus.FORBIDDEN)
        clear(auditor, CONFIDENTIAL)
        assertThat(report(id, auditorSession)).hasStatus(HttpStatus.OK)
        assertThat(part(id, "SUMMARY", auditorSession)).hasStatus(HttpStatus.OK)
        val reader = person(CONFIDENTIAL).session
        assertThat(report(id, reader)).describedAs("neither permission").hasStatus(HttpStatus.FORBIDDEN)
        assertThat(part(id, "MANIFEST", reader)).hasStatus(HttpStatus.FORBIDDEN)

        val other = registry.aTestTenant(transactions, settings, "report-other", mfaRequired = false)
        val (_, otherSession) = imports.importer(mvc, other)
        assertThat(
            mvc
                .get()
                .uri("/api/v1/admin/imports/$id/report")
                .with(onHost(other.slug))
                .cookie(otherSession)
                .exchange(),
        ).hasStatus(HttpStatus.NOT_FOUND)
        AppRoleJdbc.inTransaction(other.id) { c ->
            assertThat(c.long("select count(*) from files_file where purpose = 'imports.record'")).describedAs("RLS").isZero()
        }
    }

    private companion object {
        const val FINGERPRINT_BYTES = 32
    }
}
