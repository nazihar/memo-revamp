package com.memo.imports.internal.report

import com.memo.common.tenant.TenantDsl
import com.memo.imports.internal.dryrun.PersonKeys
import com.memo.imports.internal.dryrun.ProblemStage
import com.memo.imports.internal.dryrun.StagingStore
import com.memo.jooq.tables.references.IMPORTS_FILE
import com.memo.jooq.tables.references.IMPORTS_PROBLEM
import com.memo.jooq.tables.references.IMPORTS_ROW
import com.memo.jooq.tables.references.IMPORTS_ROW_APPROVAL
import com.memo.jooq.tables.references.IMPORTS_ROW_FILE
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/** A Memos row for the report's Rows sheet. */
data class ReportRow(
    val id: UUID,
    val rowNumber: Int,
    val cells: Map<String, String>,
    val result: String,
    val memoId: UUID?,
    val errors: Int,
    val warnings: Int,
)

/** A Files row for the report's Files sheet, with the original it matched and the copy stored for it. */
data class ReportFileRow(
    val id: UUID,
    val rowNumber: Int,
    val path: String?,
    val role: String?,
    val expectedSha256: String?,
    val match: String?,
    val rowResult: String?,
    val intakePath: String?,
    val intakeSize: Long?,
    val intakeSha256: String?,
    val intakeState: String?,
    val refusal: String?,
    val storedFileId: UUID?,
    val storedVersionId: UUID?,
)

/** A registered original for the report's Originals sheet. */
data class ReportOriginal(
    val path: String,
    val state: String,
    val size: Long,
    val sha256: String?,
    val mediaType: String?,
    val refusal: String?,
    val signature: String?,
    val receivedAt: Instant?,
)

/** A problem for the report's Problems sheet. */
data class ReportProblem(
    val id: UUID,
    val stage: String,
    val level: String,
    val code: String,
    val sheet: String?,
    val rowNumber: Int?,
    val column: String?,
    val params: String?,
)

/**
 * The detail sheets' reads (design E06 §8, part 2), a page at a time on keysets, so that a batch of 50,000 rows and
 * 250,000 problems is written without holding it. Inside a tenant transaction.
 */
@Repository
class ReportSheetReads(
    private val tenantDsl: TenantDsl,
    private val json: JsonMapper,
) {
    fun rows(
        batchId: UUID,
        afterRow: Int,
        limit: Int,
    ): List<ReportRow> {
        val r = IMPORTS_ROW
        return tenantDsl
            .current()
            .select(r.ID, r.ROW_NUMBER, r.DATA, r.RESULT, r.MEMO_ID, r.ERRORS, r.WARNINGS)
            .from(r)
            .where(r.BATCH_ID.eq(batchId), r.ROW_NUMBER.gt(afterRow))
            .orderBy(r.ROW_NUMBER)
            .limit(limit)
            .fetch { x ->
                ReportRow(x.value1()!!, x.value2()!!, cells(x.value3()?.data()), x.value4()!!, x.value5(), x.value6()!!, x.value7()!!)
            }
    }

    /** The distinct problem codes of each of [rowIds]. */
    fun codes(rowIds: Collection<UUID>): Map<UUID, List<String>> {
        if (rowIds.isEmpty()) return emptyMap()
        val p = IMPORTS_PROBLEM
        return tenantDsl
            .current()
            .selectDistinct(p.ROW_ID, p.CODE)
            .from(p)
            .where(p.ROW_ID.`in`(rowIds))
            .orderBy(p.ROW_ID, p.CODE)
            .fetch { it.value1()!! to it.value2()!! }
            .groupBy({ it.first }, { it.second })
    }

    /** The people each of [rowIds] names in its Approvals rows, as `email:` or `id:` keys (the PERSON mappings' keys). */
    fun approvers(rowIds: Collection<UUID>): Map<UUID, List<String>> {
        if (rowIds.isEmpty()) return emptyMap()
        val a = IMPORTS_ROW_APPROVAL
        return tenantDsl
            .current()
            .select(a.ROW_ID, a.EMAIL, a.LEGACY_ID)
            .from(a)
            .where(a.ROW_ID.`in`(rowIds))
            .orderBy(a.ROW_ID, a.ROW_NUMBER)
            .fetch { r -> r.value1()!! to PersonKeys.of(r.value2(), r.value3()) }
            .filter { it.second != null }
            .groupBy({ it.first }, { it.second!! })
    }

    fun files(
        batchId: UUID,
        after: Pair<Int, UUID>?,
        limit: Int,
    ): List<ReportFileRow> {
        val f = IMPORTS_ROW_FILE
        val i = IMPORTS_FILE
        val r = IMPORTS_ROW
        return tenantDsl
            .current()
            .select(
                f.ID,
                f.ROW_NUMBER,
                f.PATH,
                f.ROLE,
                f.EXPECTED_SHA256,
                f.MATCH,
                r.RESULT,
                i.PATH,
                i.SIZE_BYTES,
                i.SHA256,
                i.STATE,
                i.REFUSAL,
                f.STORED_FILE_ID,
                f.STORED_VERSION_ID,
            ).from(f)
            .leftJoin(i)
            .on(i.ID.eq(f.FILE_ID))
            .leftJoin(r)
            .on(r.ID.eq(f.ROW_ID))
            .where(f.BATCH_ID.eq(batchId))
            .and(after?.let { (row, id) -> DSL.row(f.ROW_NUMBER, f.ID).gt(row, id) } ?: DSL.noCondition())
            .orderBy(f.ROW_NUMBER, f.ID)
            .limit(limit)
            .fetch { x ->
                ReportFileRow(
                    id = x.get(f.ID)!!,
                    rowNumber = x.get(f.ROW_NUMBER)!!,
                    path = x.get(f.PATH),
                    role = x.get(f.ROLE),
                    expectedSha256 = x.get(f.EXPECTED_SHA256),
                    match = x.get(f.MATCH),
                    rowResult = x.get(r.RESULT),
                    intakePath = x.get(i.PATH),
                    intakeSize = x.get(i.SIZE_BYTES),
                    intakeSha256 = x.get(i.SHA256)?.let(HEX::formatHex),
                    intakeState = x.get(i.STATE),
                    refusal = x.get(i.REFUSAL),
                    storedFileId = x.get(f.STORED_FILE_ID),
                    storedVersionId = x.get(f.STORED_VERSION_ID),
                )
            }
    }

    /** The VERIFY problems' codes by Files row number (the verification result of each original). */
    fun verified(batchId: UUID): Map<Int, List<String>> {
        val p = IMPORTS_PROBLEM
        return tenantDsl
            .current()
            .select(p.ROW_NUMBER, p.CODE)
            .from(p)
            .where(p.BATCH_ID.eq(batchId), p.STAGE.eq(ProblemStage.VERIFY.name), p.SHEET.eq("FILES"), p.ROW_NUMBER.isNotNull)
            .fetch { it.value1()!! to it.value2()!! }
            .groupBy({ it.first }, { it.second })
    }

    fun originals(
        batchId: UUID,
        afterPath: String?,
        limit: Int,
    ): List<ReportOriginal> {
        val i = IMPORTS_FILE
        return tenantDsl
            .current()
            .selectFrom(i)
            .where(i.BATCH_ID.eq(batchId))
            .and(afterPath?.let { i.PATH.gt(it) } ?: DSL.noCondition())
            .orderBy(i.PATH)
            .limit(limit)
            .fetch { r ->
                ReportOriginal(
                    r.path,
                    r.state ?: "",
                    r.sizeBytes,
                    r.sha256?.let(HEX::formatHex),
                    r.mediaType,
                    r.refusal,
                    r.refusalSignature,
                    r.receivedAt?.toInstant(),
                )
            }
    }

    fun problems(
        batchId: UUID,
        afterId: UUID?,
        limit: Int,
    ): List<ReportProblem> {
        val p = IMPORTS_PROBLEM
        return tenantDsl
            .current()
            .selectFrom(p)
            .where(p.BATCH_ID.eq(batchId))
            .and(afterId?.let { p.ID.gt(it) } ?: DSL.noCondition())
            .orderBy(p.ID)
            .limit(limit)
            .fetch { r -> ReportProblem(r.id, r.stage, r.level, r.code, r.sheet, r.rowNumber, r.columnName, r.params?.data()) }
    }

    private fun cells(data: String?): Map<String, String> =
        data?.let { json.readTree(it).get(StagingStore.CELLS) }?.let { json.convertValue(it, CELLS) }.orEmpty()

    private companion object {
        val HEX: HexFormat = HexFormat.of()
        val CELLS = object : TypeReference<Map<String, String>>() {}
    }
}
