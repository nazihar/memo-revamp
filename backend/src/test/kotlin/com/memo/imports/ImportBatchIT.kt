package com.memo.imports

import com.memo.common.tenant.TenantDsl
import com.memo.files.Files
import com.memo.imports.internal.ImportSources
import com.memo.imports.internal.StagedPurges
import com.memo.imports.internal.sheet.SheetLimits
import com.memo.imports.internal.sheet.SheetProblemCode
import com.memo.imports.internal.sheet.SheetRow
import com.memo.imports.internal.sheet.SheetTable
import com.memo.imports.internal.sheet.XlsxSheetReader
import com.memo.jooq.tables.references.IMPORTS_BATCH
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.AppRoleJdbc.long
import com.memo.testsupport.AppRoleJdbc.update
import com.memo.testsupport.Audit
import com.memo.testsupport.ImportSheets
import com.memo.testsupport.ImportSupport
import com.memo.testsupport.LegacySamples
import com.memo.testsupport.MemoIntegrationTest
import com.memo.testsupport.Samples
import com.memo.testsupport.TestTenant
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.sendJson
import jakarta.servlet.http.Cookie
import org.apache.poi.ss.usermodel.DataValidation
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MvcTestResult
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.sql.SQLException
import java.util.UUID

/**
 * Import batches and their intake (ADR-0055, design E06 §4.1, §5.1, §10; test plan E06 T5 `ImportBatchIT`): create,
 * view, change and list batches; sources bound to their batch (a workbook or CSV files, not both); paths registered a
 * thousand at a time, listed by state and resumed by size; originals received or refused with the pipeline's reason; the
 * tenant's template; the hostile corpus ending as batch problems; no access without `memo.import`; nothing across
 * tenants.
 */
class ImportBatchIT : MemoIntegrationTest() {
    @Autowired lateinit var imports: ImportSupport

    @Autowired lateinit var sources: ImportSources

    @Autowired lateinit var purges: StagedPurges

    @Autowired lateinit var files: Files

    @Autowired lateinit var tenantDsl: TenantDsl

    private lateinit var importer: UUID
    private lateinit var session: Cookie

    @BeforeEach
    fun importer() {
        val (id, cookie) = imports.importer(mvc, tenant)
        importer = id
        session = cookie
    }

    private fun createBatch(
        name: String = "Legacy DMS 2010-2019",
        key: String = UUID.randomUUID().toString(),
        who: Cookie = session,
        extra: String = "",
    ): MvcTestResult =
        post("/api/v1/admin/imports", who, """{"name":"$name","sourceCode":"legacy-dms","timeZone":"Asia/Dhaka"$extra}""", key)

    private fun aBatch(): String = createBatch().json()["id"].asString()

    private fun batch(id: String): MvcTestResult = read("/api/v1/admin/imports/$id", session)

    private fun register(
        id: String,
        paths: Map<String, Long>,
        who: Cookie = session,
    ): MvcTestResult =
        post(
            "/api/v1/admin/imports/$id/files",
            who,
            json.writeValueAsString(mapOf("files" to paths.map { (path, size) -> mapOf("path" to path, "size" to size) })),
        )

    private fun entries(result: MvcTestResult): Map<String, Pair<String, String>> {
        val list = result.json()
        return (0 until list.size()).associate {
            list[it]["path"].asString() to
                (list[it]["intakeId"].asString() to list[it]["state"].asString())
        }
    }

    private fun setState(
        batch: String,
        state: String,
        on: TestTenant = tenant,
    ) = transactions.inTenant(on.id) {
        tenantDsl
            .current()
            .update(IMPORTS_BATCH)
            .set(IMPORTS_BATCH.STATE, state)
            .where(IMPORTS_BATCH.ID.eq(UUID.fromString(batch)))
            .execute()
    }

    private fun source(
        batch: String,
        table: String,
        bytes: ByteArray,
        name: String,
        who: Cookie = session,
    ) = upload(who, bytes, "/api/v1/files?purpose=imports.source&owner=$batch:$table", name)

    private fun original(
        intakeId: String,
        bytes: ByteArray,
        name: String,
        who: Cookie = session,
    ) = upload(who, bytes, "/api/v1/files?purpose=imports.intake&owner=$intakeId", name)

    @Test
    fun `a holder creates, views, changes and lists batches, each change audited`() {
        val key = UUID.randomUUID().toString()
        val created = createBatch(key = key, extra = ""","numberPrefix":"L-"""")
        assertThat(created).hasStatus(HttpStatus.CREATED)
        assertThat(created.response.getHeader(HttpHeaders.ETAG)).isEqualTo("\"0\"")
        val body = created.json()
        val id = body["id"].asString()
        assertThat(listOf("state", "sourceCode", "numberPrefix").map { body[it].asString() }).containsExactly("OPEN", "LEGACY-DMS", "L-")
        assertThat(body["originals"]["expected"].asInt()).isZero()
        val actions = body["allowedActions"].items().map { it.asString() }
        assertThat(actions).containsExactly("EDIT", "ATTACH_SOURCE", "REGISTER_ORIGINALS", "MAP")
        // The same key gives the same batch.
        assertThat(createBatch(key = key, extra = ""","numberPrefix":"L-"""").json()["id"].asString()).isEqualTo(id)
        Audit.assertAudited(tenant.id, "import.batch.created", id, importer.toString())

        val changed =
            put(
                "/api/v1/admin/imports/$id",
                session,
                """{"name":"Legacy DMS","sourceCode":"OLD-DMS","timeZone":"Europe/London","defaultClassificationId":"${levels()[1].id}"}""",
                0,
            )
        assertThat(changed).hasStatus(HttpStatus.OK)
        assertThat(changed.json()["version"].asLong()).isEqualTo(1)
        assertThat(changed.json()["timeZone"].asString()).isEqualTo("Europe/London")
        assertThat(changed.json().absent("numberPrefix")).isTrue()
        assertThat(Audit.assertAudited(tenant.id, "import.batch.changed", id).canonical).contains("OLD-DMS", "LEGACY-DMS")
        assertThat(put("/api/v1/admin/imports/$id", session, """{"name":"X","sourceCode":"X","timeZone":"UTC"}""", 0))
            .hasStatus(HttpStatus.PRECONDITION_FAILED)

        val invalid =
            put(
                "/api/v1/admin/imports/$id",
                session,
                """{"name":" ","sourceCode":"-bad","timeZone":"Mars/Olympus","numberPrefix":"/x",""" +
                    """"defaultClassificationId":"${UUID.randomUUID()}"}""",
                1,
            )
        assertThat(invalid).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
        assertThat(invalid.errors()).containsExactlyInAnyOrder(
            "name:REQUIRED",
            "sourceCode:SOURCE_CODE_INVALID",
            "timeZone:TIME_ZONE_INVALID",
            "numberPrefix:NUMBER_PREFIX_INVALID",
            "defaultClassificationId:unknown",
        )

        val second = aBatch()
        val first = read("/api/v1/admin/imports?limit=1", session).json()
        assertThat(first["batches"].items().map { it["id"].asString() }).containsExactly(second)
        val next = read("/api/v1/admin/imports?limit=1&cursor=${first["next"].asString()}", session).json()
        assertThat(next["batches"].items().map { it["id"].asString() }).containsExactly(id)
        assertThat(next.absent("next")).isTrue()

        setState(id, "IMPORTING")
        assertThat(
            put(
                "/api/v1/admin/imports/$id",
                session,
                """{"name":"Late","sourceCode":"X","timeZone":"UTC"}""",
                1,
            ).json()["reason"].asString(),
        ).isEqualTo("WRONG_STATE")
    }

    @Test
    fun `at most five batches may be open at once`() {
        repeat(MAX_OPEN) { assertThat(createBatch()).hasStatus(HttpStatus.CREATED) }
        val refused = createBatch()
        assertThat(refused).hasStatus(HttpStatus.CONFLICT)
        assertThat(refused.json()["reason"].asString()).isEqualTo("TOO_MANY_BATCHES")

        setState(read("/api/v1/admin/imports", session).json()["batches"][0]["id"].asString(), "ABANDONED")
        assertThat(createBatch()).hasStatus(HttpStatus.CREATED)
    }

    @Test
    fun `sources bind to their batch, a workbook replaces the CSV files and the reverse, and replaced copies are purged`() {
        val id = aBatch()
        val memos = ImportSheets.csv(ImportSheets.MEMO_HEADER.joinToString(","), "CR/1,GEN,Rent,Approved,,HO,2019-03-14,,,A,,")
        val filesCsv = ImportSheets.csv(ImportSheets.FILE_HEADER.joinToString(","), "CR/1,a/memo.pdf,MAIN,,,")
        assertThat(source(id, "MEMOS", memos, "memos.csv")).hasStatus(HttpStatus.CREATED)
        assertThat(source(id, "FILES", filesCsv, "files.csv")).hasStatus(HttpStatus.CREATED)
        val csv = batch(id).json()
        assertThat(csv["sourceKind"].asString()).isEqualTo("CSV")
        assertThat(csv["sources"].items().map { it["table"].asString() }).containsExactlyInAnyOrder("MEMOS", "FILES")
        assertThat(csv["version"].asLong()).isEqualTo(2)
        assertThat(Audit.assertAudited(tenant.id, "import.source.attached", id).canonical).contains("FILES", "sha256")
        val rows = mutableListOf<SheetRow>()
        val parsed = tenantRun { sources.parse(UUID.fromString(id), rows::add) }
        assertThat(parsed.problems).isEmpty()
        assertThat(rows.map { it.table }).containsExactly(SheetTable.MEMOS, SheetTable.FILES)

        setState(id, "VALIDATED")
        assertThat(source(id, "WORKBOOK", ImportSheets.good(2), "legacy.xlsx")).hasStatus(HttpStatus.CREATED)
        val workbook = batch(id).json()
        assertThat(workbook["state"].asString()).describedAs("a new source needs a new dry run").isEqualTo("OPEN")
        assertThat(workbook["sourceKind"].asString()).isEqualTo("XLSX")
        assertThat(workbook["sources"].items().map { it["table"].asString() }).containsExactly("WORKBOOK")
        assertThat(tenantRun { sources.parse(UUID.fromString(id), {}) }.rows).containsEntry(SheetTable.MEMOS, 2)

        val replaced = csv["sources"].items().map { UUID.fromString(it["versionId"].asString()) }
        assertThat(tenantRun { purges.purgeSuperseded(UUID.fromString(id)) }).isEqualTo(2)
        val purgedAt = transactions.readInTenant(tenant.id) { replaced.map { version -> files.version(version)?.purgedAt } }
        assertThat(purgedAt).hasSize(2).doesNotContainNull()
        assertThat(tenantRun { purges.purgeSuperseded(UUID.fromString(id)) }).isZero()

        // Someone without memo.import, an unknown table and a batch whose import started are all refused.
        assertThat(source(id, "MEMOS", memos, "memos.csv", who = person().session)).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(source(id, "LISTS", memos, "memos.csv")).hasStatus(HttpStatus.FORBIDDEN)
        setState(id, "IMPORTING")
        assertThat(source(id, "MEMOS", memos, "memos.csv")).hasStatus(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `a thousand paths register in one call, the list resumes by state and size, and uploads are received or refused`() {
        val id = aBatch()
        val paths = (1..1_000).associate { "archive/%04d/memo.pdf".format(it) to it.toLong() }
        val registered = register(id, paths)
        assertThat(registered).hasStatus(HttpStatus.OK)
        val entries = entries(registered)
        assertThat(entries).hasSize(1_000)
        assertThat(entries.values.map { it.second }.toSet()).containsExactly("EXPECTED")
        assertThat(Audit.assertAudited(tenant.id, "import.originals.registered", id).canonical).contains("1000")

        val pdf = entries.getValue("archive/0001/memo.pdf").first
        val msg = entries.getValue("archive/0002/memo.pdf").first
        assertThat(original(pdf, Samples.pdf(), "memo.pdf")).hasStatus(HttpStatus.CREATED)
        assertThat(original(msg, LegacySamples.msg(), "memo.msg")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)

        // Registering again: known paths keep their state (NFC makes the decomposed é the same path).
        val again = entries(register(id, mapOf("archive/0001/memo.pdf" to 1L, "new/Café.doc" to 7L)))
        assertThat(again.getValue("archive/0001/memo.pdf").second).isEqualTo("RECEIVED")
        assertThat(entries(register(id, mapOf("new/Café.doc" to 7L))).getValue("new/Café.doc").first)
            .isEqualTo(again.getValue("new/Café.doc").first)

        val received = read("/api/v1/admin/imports/$id/files?state=RECEIVED", session).json()["files"].items().single()
        assertThat(received["path"].asString()).isEqualTo("archive/0001/memo.pdf")
        assertThat(received["size"].asLong()).isEqualTo(Samples.pdf().size.toLong())
        assertThat(received["sha256"].asString()).hasSize(SHA256_HEX)
        assertThat(received["mediaType"].asString()).isEqualTo("application/pdf")
        val refused = read("/api/v1/admin/imports/$id/files?state=REFUSED", session).json()["files"].items().single()
        assertThat(refused["refusal"].asString()).isEqualTo("TYPE_NOT_ALLOWED")

        // The resume: what is still to send, a page at a time.
        val pending = mutableListOf<String>()
        var cursor: String? = null
        do {
            val page =
                read(
                    "/api/v1/admin/imports/$id/files?state=EXPECTED,REFUSED&limit=400" + (cursor?.let { "&cursor=$it" } ?: ""),
                    session,
                ).json()
            pending += page["files"].items().map { it["path"].asString() }
            cursor = page["next"]?.takeUnless { it.isNull }?.asString()
        } while (cursor != null)
        assertThat(pending).hasSize(1_000).doesNotContain("archive/0001/memo.pdf").contains("archive/0002/memo.pdf")
        val view = batch(id).json()["originals"]
        assertThat(view["expected"].asInt()).isEqualTo(999)
        assertThat(view["received"].asInt()).isEqualTo(1)
        assertThat(view["refused"].asInt()).isEqualTo(1)

        // A refused path may be sent again; a replaced original's earlier copy is superseded.
        assertThat(original(msg, Samples.pdf(), "memo.pdf")).hasStatus(HttpStatus.CREATED)
        assertThat(original(pdf, Samples.pdf(), "memo.pdf")).hasStatus(HttpStatus.CREATED)
        assertThat(tenantRun { purges.purgeSuperseded(UUID.fromString(id)) }).isEqualTo(1)
    }

    @Test
    fun `bad paths, repeated paths and the batch's limits refuse the whole call`() {
        val id = aBatch()
        val bad = register(id, mapOf("a/../b.pdf" to 1L, "/etc/passwd" to 1L, "ok.pdf" to -1L))
        assertThat(bad).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
        assertThat(bad.errors()).containsExactly("files[0].path:PATH_INVALID", "files[1].path:PATH_INVALID", "files[2].size:SIZE_INVALID")
        val repeated =
            post(
                "/api/v1/admin/imports/$id/files",
                session,
                """{"files":[{"path":"a/Café.pdf","size":1},{"path":"a/Café.pdf","size":1}]}""",
            )
        assertThat(repeated.errors()).containsExactly("files[1].path:PATH_DUPLICATE")
        assertThat(register(id, (0..1_000).associate { "p$it" to 1L }).errors()).containsExactly("files:COUNT")
        assertThat(register(id, mapOf("huge.tif" to (200L shl 30) + 1)).errors()).containsExactly("files:LIMIT_EXCEEDED")
        assertThat(read("/api/v1/admin/imports/$id/files", session).json()["files"].size()).isZero()

        setState(id, "IMPORTING")
        assertThat(register(id, mapOf("late.pdf" to 1L)).json()["reason"].asString()).isEqualTo("WRONG_STATE")
    }

    @Test
    fun `an original is refused for an entry whose batch has moved on, and a received one sends a validated batch back to open`() {
        val open = aBatch()
        val moved = aBatch()
        val ownEntry = entries(register(open, mapOf("a.pdf" to 1L))).getValue("a.pdf").first
        val otherEntry = entries(register(moved, mapOf("b.pdf" to 1L))).getValue("b.pdf").first
        setState(moved, "IMPORTING")

        assertThat(original(otherEntry, Samples.pdf(), "b.pdf")).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(original(UUID.randomUUID().toString(), Samples.pdf(), "c.pdf")).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(original(ownEntry, Samples.pdf(), "a.pdf", who = person().session)).hasStatus(HttpStatus.FORBIDDEN)

        setState(open, "VALIDATED")
        assertThat(original(ownEntry, Samples.pdf(), "a.pdf")).hasStatus(HttpStatus.CREATED)
        assertThat(batch(open).json()["state"].asString()).isEqualTo("OPEN")
    }

    @Test
    fun `the template is the tenant's, every column Text, with warning lists, and it reads back`() {
        val result = read("/api/v1/admin/imports/template", session)
        assertThat(result).hasStatus(HttpStatus.OK)
        assertThat(result.response.contentType).isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        assertThat(
            result.response.getHeader(HttpHeaders.CONTENT_DISPOSITION),
        ).startsWith("attachment").contains("memo-import-template.xlsx")
        val bytes = result.response.contentAsByteArray

        XSSFWorkbook(bytes.inputStream()).use { workbook ->
            assertThat(workbook.map { it.sheetName }).containsExactly("Instructions", "Memos", "Files", "Approvals", "Lists")
            val memos = workbook.getSheet("Memos")
            assertThat(memos.getRow(0).map { it.stringCellValue }).containsExactlyElementsOf(ImportSheets.MEMO_HEADER)
            assertThat(memos.getColumnStyle(0).dataFormatString).isEqualTo("@")
            assertThat(memos.getRow(1).getCell(2).stringCellValue).contains("delete me")
            val lists = workbook.getSheet("Lists")
            val values = lists.flatMap { row -> row.map { it.stringCellValue } }
            assertThat(values).contains("APPROVED", "NOT_IMPORTED", levels()[0].name)
            assertThat(memos.dataValidations.map { it.errorStyle }.toSet()).containsExactly(DataValidation.ErrorStyle.WARNING)
            assertThat(memos.dataValidations).hasSizeGreaterThanOrEqualTo(2)
        }
        val read = XlsxSheetReader().read(bytes.inputStream(), {})
        assertThat(read.problems).isEmpty()
        assertThat(read.rows).containsEntry(SheetTable.MEMOS, 1).containsEntry(SheetTable.FILES, 1).containsEntry(SheetTable.APPROVALS, 1)
    }

    @Test
    fun `the hostile corpus ends as batch problems or upload refusals, and the parser keeps working`() {
        val good = ImportSheets.good()
        val workbooks =
            mapOf(
                "dtd" to ImportSheets.withDoctype(good, "xl/sharedStrings.xml", "sst"),
                "bomb" to ImportSheets.bomb(),
                "too many rows" to
                    ImportSheets.workbook(
                        mapOf(
                            "Memos" to listOf(ImportSheets.MEMO_HEADER) + (1..ROWS_OVER).map { ImportSheets.memo("CR/$it") },
                            "Files" to listOf(ImportSheets.FILE_HEADER),
                        ),
                    ),
                "unknown sheet" to ImportSheets.workbook(mapOf("Sheet1" to listOf(listOf("x")))),
            )
        val header = ImportSheets.MEMO_HEADER.joinToString(",")
        val files = ImportSheets.csv(ImportSheets.FILE_HEADER.joinToString(","))
        val csvs =
            mapOf(
                "windows-1252" to
                    ImportSheets.csv(header, "CR/1,GEN,Café,Approved,,HO,2019-03-14,,,A,,").let {
                        String(it, StandardCharsets.UTF_8).toByteArray(Charset.forName("windows-1252"))
                    },
                "giant field" to ImportSheets.csv(header, "CR/1,GEN,\"${"y".repeat(40_000)}\",Approved,,HO,2019-03-14,,,A,,"),
                "unterminated quote" to ImportSheets.csv(header, "CR/1,GEN,\"open"),
                "duplicate column" to ImportSheets.csv("$header,legacy_number"),
            )
        val outcomes = mutableMapOf<String, Any>()
        workbooks.forEach { (case, bytes) -> outcomes[case] = outcome(aFreshBatch(), "WORKBOOK", bytes, "legacy.xlsx", null) }
        csvs.forEach { (case, bytes) -> outcomes[case] = outcome(aFreshBatch(), "MEMOS", bytes, "memos.csv", files) }

        assertThat(outcomes["too many rows"]).isEqualTo(SheetProblemCode.TOO_MANY_ROWS)
        assertThat(outcomes["unknown sheet"]).isEqualTo(SheetProblemCode.SHEET_UNKNOWN)
        assertThat(outcomes["windows-1252"]).isEqualTo(SheetProblemCode.NOT_UTF8)
        assertThat(outcomes["giant field"]).isEqualTo(SheetProblemCode.VALUE_TOO_LONG_FOR_FILE)
        assertThat(outcomes["unterminated quote"]).isEqualTo(SheetProblemCode.MALFORMED)
        assertThat(outcomes["duplicate column"]).isEqualTo(SheetProblemCode.COLUMN_DUPLICATE)
        // A DTD or a bomb: refused by the pipeline's own zip checks, or by the reader.
        assertThat(outcomes["dtd"]).isIn(SheetProblemCode.MALFORMED, HttpStatus.UNPROCESSABLE_CONTENT)
        assertThat(outcomes["bomb"]).isIn(SheetProblemCode.MALFORMED, HttpStatus.UNPROCESSABLE_CONTENT)

        val id = aFreshBatch()
        assertThat(source(id, "WORKBOOK", good, "legacy.xlsx")).hasStatus(HttpStatus.CREATED)
        assertThat(tenantRun { sources.parse(UUID.fromString(id), {}) }.problems).isEmpty()
    }

    /** One batch per case: the tenant allows five open at a time, so earlier ones are abandoned. */
    private fun aFreshBatch(): String {
        read("/api/v1/admin/imports?limit=100", session).json()["batches"].items().forEach { setState(it["id"].asString(), "ABANDONED") }
        return aBatch()
    }

    /** The upload's refusal status, or the first batch problem of reading the sources (null when none). */
    private fun outcome(
        batch: String,
        table: String,
        bytes: ByteArray,
        name: String,
        files: ByteArray?,
    ): Any {
        val uploaded = source(batch, table, bytes, name)
        if (files != null) assertThat(source(batch, "FILES", files, "files.csv")).hasStatus(HttpStatus.CREATED)
        val limits = SheetLimits(maxRows = SheetLimits.MAX_ROWS + (SheetTable.MEMOS to ROWS_CAP))
        return if (uploaded.response.status != HttpStatus.CREATED.value()) {
            HttpStatus.valueOf(uploaded.response.status)
        } else {
            tenantRun { sources.parse(UUID.fromString(batch), {}, limits) }.problems.firstOrNull()?.code ?: "none"
        }
    }

    @Test
    fun `without memo import every endpoint is forbidden, and another tenant sees nothing`() {
        val id = aBatch()
        val entry = entries(register(id, mapOf("a.pdf" to 1L))).getValue("a.pdf").first
        val stranger = person().session
        listOf(
            read("/api/v1/admin/imports", stranger),
            read("/api/v1/admin/imports/$id", stranger),
            read("/api/v1/admin/imports/template", stranger),
            read("/api/v1/admin/imports/$id/files", stranger),
            createBatch(who = stranger),
            register(id, mapOf("b.pdf" to 1L), stranger),
            mvc.sendJson(HttpMethod.PUT, tenant, "/api/v1/admin/imports/$id", stranger, "{}", mapOf("If-Match" to "\"0\"")),
        ).forEach { assertThat(it).hasStatus(HttpStatus.FORBIDDEN) }

        val other = registry.aTestTenant(transactions, settings, "imports-other", mfaRequired = false)
        val (_, otherSession) = imports.importer(mvc, other)
        val foreign =
            mvc
                .get()
                .uri("/api/v1/admin/imports/$id")
                .with(com.memo.testsupport.onHost(other.slug))
                .cookie(otherSession)
                .exchange()
        assertThat(foreign).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(
            mvc.sendJson(HttpMethod.POST, other, "/api/v1/admin/imports/$id/files", otherSession, """{"files":[{"path":"x","size":1}]}"""),
        ).hasStatus(HttpStatus.NOT_FOUND)

        val tables = listOf("imports_batch", "imports_file", "imports_staged", "imports_row", "imports_problem", "imports_mapping")
        AppRoleJdbc.inTransaction(other.id) { c ->
            tables.forEach { table ->
                assertThat(c.long("select count(*) from $table where tenant_id = ?", tenant.id.value)).describedAs(table).isZero()
            }
            assertThat(c.update("update imports_batch set name = 'x' where id = ?", UUID.fromString(id))).isZero()
            assertThat(c.update("update imports_file set path = 'x' where id = ?", UUID.fromString(entry))).isZero()
        }
        val insert =
            runCatching {
                AppRoleJdbc.inTransaction(other.id) { c ->
                    c.update(
                        "insert into imports_file (id, tenant_id, batch_id, path, path_fold, size_bytes, registered_at) " +
                            "values (?, ?, ?, 'x', 'x', 1, now())",
                        UUID.randomUUID(),
                        tenant.id.value,
                        UUID.fromString(id),
                    )
                }
            }.exceptionOrNull()
        assertThat(insert).isInstanceOf(SQLException::class.java)
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            assertThat(runCatching { c.update("delete from imports_batch where id = ?", UUID.fromString(id)) }.exceptionOrNull())
                .describedAs("batches are never deleted")
                .isInstanceOf(SQLException::class.java)
        }
    }

    private fun tools.jackson.databind.JsonNode.items(): List<tools.jackson.databind.JsonNode> = (0 until size()).map { get(it) }

    /** Runs [block] with the tenant and the importer bound, outside a transaction (as the dry run's job will). */
    private fun <T> tenantRun(block: () -> T): T =
        com.memo.common.context.ActorContext.call(
            com.memo.common.context
                .ActorScope(
                    com.memo.common.context.Actor
                        .User(importer),
                    null,
                ),
        ) {
            com.memo.common.tenant.TenantContext
                .call(tenant.id, block)
        }

    private companion object {
        const val MAX_OPEN = 5
        const val SHA256_HEX = 64

        // The row caps are the design's in production; a small one here keeps the workbook small.
        const val ROWS_CAP = 3
        const val ROWS_OVER = ROWS_CAP + 1
    }
}
