package com.memo.imports.internal

import com.memo.common.tenant.TenantDsl
import com.memo.imports.internal.dryrun.MemoColumns
import com.memo.imports.internal.dryrun.ProblemLevel
import com.memo.imports.internal.dryrun.ProblemSheet
import com.memo.imports.internal.dryrun.ProblemStage
import com.memo.imports.internal.dryrun.RowResult
import com.memo.imports.internal.dryrun.StagingStore
import com.memo.jooq.tables.references.IMPORTS_PROBLEM
import com.memo.jooq.tables.references.IMPORTS_ROW
import org.jooq.Condition
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** The filters of the problem list (design E06 §10); each list empty or null means any. */
data class ProblemFilter(
    val levels: Set<ProblemLevel> = emptySet(),
    val codes: Set<String> = emptySet(),
    val sheet: ProblemSheet? = null,
    val rowNumber: Int? = null,
)

/** The keyset position of the problem list: `(level, code, id)`, as its index orders it. */
data class ProblemPosition(
    val level: String,
    val code: String,
    val id: UUID,
)

/** A problem as stored. */
data class StoredProblem(
    val id: UUID,
    val stage: ProblemStage,
    val level: ProblemLevel,
    val code: String,
    val sheet: ProblemSheet?,
    val rowNumber: Int?,
    val column: String?,
    val rowId: UUID?,
    val intakeId: UUID?,
    val params: Map<String, Any?>,
)

/** A Memos row as the rows list shows it; [subject] and [rank] are read from the staged values. */
data class StoredRow(
    val id: UUID,
    val rowNumber: Int,
    val legacyNumber: String?,
    val result: RowResult,
    val errors: Int,
    val warnings: Int,
    val memoId: UUID?,
    val subject: String?,
    val rank: Int?,
)

/** The reads of the problem and row lists, keyset-paged; inside a tenant transaction. */
@Repository
class ImportDryRunQueries(
    private val tenantDsl: TenantDsl,
    private val json: JsonMapper,
) {
    fun problems(
        batchId: UUID,
        filter: ProblemFilter,
        after: ProblemPosition?,
        limit: Int,
    ): List<StoredProblem> {
        val p = IMPORTS_PROBLEM
        val conditions =
            listOfNotNull<Condition>(
                p.BATCH_ID.eq(batchId),
                filter.levels.takeIf { it.isNotEmpty() }?.let { levels -> p.LEVEL.`in`(levels.map { it.name }) },
                filter.codes.takeIf { it.isNotEmpty() }?.let { p.CODE.`in`(it) },
                filter.sheet?.let { p.SHEET.eq(it.name) },
                filter.rowNumber?.let { p.ROW_NUMBER.eq(it) },
                after?.let { DSL.row(p.LEVEL, p.CODE, p.ID).gt(it.level, it.code, it.id) },
            )
        return tenantDsl
            .current()
            .selectFrom(p)
            .where(conditions)
            .orderBy(p.LEVEL, p.CODE, p.ID)
            .limit(limit)
            .fetch { r ->
                StoredProblem(
                    r.id,
                    ProblemStage.valueOf(r.stage),
                    ProblemLevel.valueOf(r.level),
                    r.code,
                    r.sheet?.let(ProblemSheet::valueOf),
                    r.rowNumber,
                    r.columnName,
                    r.rowId,
                    r.fileId,
                    r.params?.let { json.readValue(it.data(), PARAMS) }.orEmpty(),
                )
            }
    }

    /** The batch's problems counted by level and by code. */
    fun problemCounts(batchId: UUID): Pair<Map<String, Int>, Map<String, Int>> {
        val p = IMPORTS_PROBLEM
        val rows =
            tenantDsl
                .current()
                .select(p.LEVEL, p.CODE, DSL.count())
                .from(p)
                .where(p.BATCH_ID.eq(batchId))
                .groupBy(p.LEVEL, p.CODE)
                .fetch { Triple(it.value1()!!, it.value2()!!, it.value3() ?: 0) }
        val byLevel = rows.groupBy({ it.first }, { it.third }).mapValues { it.value.sum() }
        val byCode = rows.groupBy({ it.second }, { it.third }).mapValues { it.value.sum() }
        return byLevel to byCode
    }

    /** Memos rows in row order after [afterRow], of [results] when given. */
    fun rows(
        batchId: UUID,
        results: Set<RowResult>,
        afterRow: Int?,
        limit: Int,
    ): List<StoredRow> {
        val r = IMPORTS_ROW
        val subject =
            DSL.field(
                "{0} -> {1} ->> {2}",
                String::class.java,
                r.DATA,
                DSL.inline(StagingStore.CELLS),
                DSL.inline(MemoColumns.SUBJECT),
            )
        val rank = DSL.field("({0} ->> {1})::int", SQLDataType.INTEGER, r.DATA, DSL.inline(StagingStore.RANK))
        return tenantDsl
            .current()
            .select(r.ID, r.ROW_NUMBER, r.LEGACY_NUMBER, r.RESULT, r.ERRORS, r.WARNINGS, r.MEMO_ID, subject, rank)
            .from(r)
            .where(r.BATCH_ID.eq(batchId))
            .and(if (results.isEmpty()) DSL.noCondition() else r.RESULT.`in`(results.map { it.name }))
            .and(afterRow?.let { r.ROW_NUMBER.gt(it) } ?: DSL.noCondition())
            .orderBy(r.ROW_NUMBER)
            .limit(limit)
            .fetch {
                StoredRow(
                    it.value1()!!,
                    it.value2()!!,
                    it.value3(),
                    RowResult.valueOf(it.value4() ?: RowResult.PENDING.name),
                    it.value5() ?: 0,
                    it.value6() ?: 0,
                    it.value7(),
                    it.value8(),
                    it.value9(),
                )
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

    private companion object {
        val PARAMS = object : TypeReference<Map<String, Any?>>() {}
    }
}
