package com.memo.imports

import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantDsl
import com.memo.imports.internal.dryrun.ImportDryRuns
import com.memo.jooq.tables.references.IMPORTS_BATCH
import com.memo.memo.ImportedMemoTest
import com.memo.testsupport.ImportSheets
import com.memo.testsupport.ImportSupport
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MvcTestResult
import tools.jackson.databind.JsonNode
import java.util.UUID

/**
 * Dry runs in tests (design E06 §5.2): a holder of `memo.import` signed in with a second factor, batches, workbooks with
 * the tenant's General type and the `CRD` unit, originals received through the real intake, and the dry run run by the
 * test itself (test contexts share the scheduler table, so nothing is scheduled while these tests run).
 */
abstract class DryRunTest : ImportedMemoTest() {
    @Autowired lateinit var imports: ImportSupport

    @Autowired lateinit var dryRuns: ImportDryRuns

    @Autowired lateinit var tenantDsl: TenantDsl

    lateinit var holder: UUID
    lateinit var session: Cookie

    @BeforeEach
    fun holder() {
        dryRuns.jobs = false
        val (id, cookie) = imports.importer(mvc, tenant)
        holder = id
        session = cookie
    }

    @AfterEach
    fun jobsBack() {
        dryRuns.jobs = true
    }

    /** The General type's code, as a legacy workbook would name it. */
    fun typeCode(): String = asUser(admin) { legacy.types().single { it.id == generalTypeId() }.code }

    fun newBatch(extra: String = ""): String =
        post("/api/v1/admin/imports", session, """{"name":"Legacy","sourceCode":"LEGACY-DMS","timeZone":"Asia/Dhaka"$extra}""")
            .also { assertThat(it).hasStatus(HttpStatus.CREATED) }
            .json()["id"]
            .asString()

    /** A Memos row in the template's column order. */
    fun memoRow(
        number: Any,
        status: String = "Approved",
        classification: String = "Internal",
        unit: String = "CRD",
        type: String = typeCode(),
        raised: String = "2019-03-14",
        decided: String = "2019-03-20",
        closed: String = "",
        name: String = "A. Rahman",
        email: String = "",
        legacyId: String = "",
        subject: String = "Rent of the branch at plot 7",
    ): List<Any?> = listOf(number, type, subject, status, classification, unit, raised, decided, closed, name, email, legacyId)

    fun fileRow(
        number: String,
        path: String,
        role: String = "MAIN",
        sha256: String = "",
        classification: String = "",
    ): List<Any?> = listOf(number, path, role, "", sha256, classification)

    fun approvalRow(
        number: String,
        order: Any = "1",
        name: String = "K. Ahmed",
        email: String = "",
        legacyId: String = "",
        decided: String = "2019-03-19",
    ): List<Any?> = listOf(number, order, name, email, legacyId, "Head of Credit", "Approved", decided, "")

    fun workbook(
        memos: List<List<Any?>>,
        files: List<List<Any?>>,
        approvals: List<List<Any?>> = emptyList(),
        memoHeader: List<String> = ImportSheets.MEMO_HEADER,
    ): ByteArray =
        ImportSheets.workbook(
            mapOf(
                "Memos" to listOf(memoHeader) + memos,
                "Files" to listOf(ImportSheets.FILE_HEADER) + files,
                "Approvals" to listOf(ImportSheets.APPROVAL_HEADER) + approvals,
            ),
        )

    fun attach(
        batch: String,
        bytes: ByteArray,
    ) = assertThat(upload(session, bytes, "/api/v1/files?purpose=imports.source&owner=$batch:WORKBOOK", "legacy.xlsx"))
        .hasStatus(HttpStatus.CREATED)

    /** Registers [paths] and uploads each with its content (null: registered only); returns each path's intake id. */
    fun originals(
        batch: String,
        paths: Map<String, ByteArray?>,
    ): Map<String, String> {
        val body =
            json.writeValueAsString(
                mapOf("files" to paths.map { (path, bytes) -> mapOf("path" to path, "size" to (bytes?.size ?: 1)) }),
            )
        val registered = post("/api/v1/admin/imports/$batch/files", session, body).json()
        val ids = (0 until registered.size()).associate { registered[it]["path"].asString() to registered[it]["intakeId"].asString() }
        paths.forEach { (path, bytes) ->
            if (bytes != null) {
                val name = path.substringAfterLast('/')
                assertThat(upload(session, bytes, "/api/v1/files?purpose=imports.intake&owner=${ids.getValue(path)}", name))
                    .hasStatus(HttpStatus.CREATED)
            }
        }
        return ids
    }

    /** A text original whose content is unique to [label]. */
    fun text(label: String): ByteArray = "Original memo $label\n".toByteArray()

    fun requestDryRun(batch: String): MvcTestResult = post("/api/v1/admin/imports/$batch/dry-run", session)

    /** Runs the job's work for [batch] as the scheduler would, with [failures] earlier failed attempts. */
    fun runJob(
        batch: String,
        failures: Int = 0,
    ) = TenantContext.call(tenant.id) { dryRuns.run(UUID.fromString(batch), failures) }

    /** Requests a dry run, runs it, and answers the batch. */
    fun dryRun(batch: String): JsonNode {
        assertThat(requestDryRun(batch)).hasStatus(HttpStatus.ACCEPTED)
        runJob(batch)
        return batch(batch)
    }

    fun batch(id: String): JsonNode = read("/api/v1/admin/imports/$id", session).json()

    fun mappings(batch: String): JsonNode = read("/api/v1/admin/imports/$batch/mappings", session).json()

    /** Saves [entries] of [kind] at the batch's current version. */
    fun saveMappings(
        batch: String,
        kind: String,
        entries: List<Map<String, Any?>>,
    ): MvcTestResult =
        put("/api/v1/admin/imports/$batch/mappings/$kind", session, json.writeValueAsString(entries), batch(batch)["version"].asLong())

    /** Confirms every mapping of the batch as it stands (proposals kept, a classification's order as proposed). */
    fun confirmAll(batch: String) {
        mappings(batch)["mappings"].items().groupBy { it["kind"].asString() }.forEach { (kind, list) ->
            val entries =
                list.map {
                    mapOf(
                        "legacyValue" to it["legacyValue"].asString(),
                        "target" to it["target"]?.takeUnless { t -> t.isNull }?.asString(),
                        "legacyOrder" to it["legacyOrder"]?.takeUnless { o -> o.isNull }?.asInt(),
                        "confirmed" to true,
                    )
                }
            assertThat(saveMappings(batch, kind, entries)).hasStatus(HttpStatus.OK)
        }
    }

    /** A dry run to propose the mappings, all of them confirmed, and a second dry run; answers the batch. */
    fun confirmedDryRun(batch: String): JsonNode {
        dryRun(batch)
        confirmAll(batch)
        return dryRun(batch)
    }

    /** All problems of [batch] matching [query], page by page. */
    fun problems(
        batch: String,
        query: String = "",
    ): List<JsonNode> {
        val all = mutableListOf<JsonNode>()
        var cursor: String? = null
        do {
            val page = read("/api/v1/admin/imports/$batch/problems?$query" + (cursor?.let { "&cursor=$it" } ?: ""), session).json()
            all += page["problems"].items()
            cursor = page["next"]?.takeUnless { it.isNull }?.asString()
        } while (cursor != null)
        return all
    }

    /** The rows of [batch] by legacy number. */
    fun rows(batch: String): Map<String, JsonNode> =
        read("/api/v1/admin/imports/$batch/rows", session).json()["rows"].items().associateBy { it["legacyNumber"].asString() }

    fun setState(
        batch: String,
        state: String,
    ) = transactions.inTenant(tenant.id) {
        tenantDsl
            .current()
            .update(IMPORTS_BATCH)
            .set(IMPORTS_BATCH.STATE, state)
            .where(IMPORTS_BATCH.ID.eq(UUID.fromString(batch)))
            .execute()
    }

    fun JsonNode.items(): List<JsonNode> = (0 until size()).map { get(it) }

    fun JsonNode.codes(): List<String> = get("codes").items().map { it.asString() }
}
