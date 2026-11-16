package com.memo.imports.internal.report

import com.memo.common.tenant.TenantDsl
import com.memo.files.RenditionKind
import com.memo.files.RenditionState
import com.memo.imports.internal.IntakeState
import com.memo.imports.internal.dryrun.PathMatch
import com.memo.imports.internal.dryrun.ProblemLevel
import com.memo.imports.internal.dryrun.ProblemStage
import com.memo.imports.internal.dryrun.RowResult
import com.memo.jooq.tables.references.IMPORTS_FILE
import com.memo.jooq.tables.references.IMPORTS_PROBLEM
import com.memo.jooq.tables.references.IMPORTS_ROW
import com.memo.jooq.tables.references.IMPORTS_ROW_FILE
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.util.UUID

/** An original the import stored, with the Files row it came from. */
data class StoredOriginal(
    val rowNumber: Int,
    val path: String,
    val fileId: UUID,
    val versionId: UUID,
)

/**
 * The counts of the reconciliation report (design E06 §8), each line counted by its own query so that a line that does
 * not add up shows. Inside a tenant transaction.
 */
@Repository
class ReportQueries(
    private val tenantDsl: TenantDsl,
) {
    fun rows(batchId: UUID): RowTotals {
        val dsl = tenantDsl.current()
        val r = IMPORTS_ROW
        val byResult =
            dsl
                .select(r.RESULT, DSL.count())
                .from(r)
                .where(r.BATCH_ID.eq(batchId))
                .groupBy(r.RESULT)
                .fetch { it.value1()!! to (it.value2() ?: 0) }
                .toMap()
        val p = IMPORTS_PROBLEM
        val rejectedByCode =
            dsl
                .select(p.CODE, DSL.countDistinct(p.ROW_ID))
                .from(p)
                .join(r)
                .on(r.ID.eq(p.ROW_ID))
                .where(p.BATCH_ID.eq(batchId), r.RESULT.eq(RowResult.REJECTED.name), p.LEVEL.eq(ProblemLevel.ERROR.name))
                .groupBy(p.CODE)
                .fetch { it.value1()!! to (it.value2() ?: 0) }
                .toMap()
                .toSortedMap()

        fun count(result: RowResult) = byResult[result.name] ?: 0
        return RowTotals(
            read = byResult.values.sum(),
            imported = count(RowResult.IMPORTED),
            skipped = count(RowResult.SKIPPED),
            rejected = count(RowResult.REJECTED),
            failed = count(RowResult.FAILED),
            notStarted = count(RowResult.NOT_STARTED),
            rejectedByCode = rejectedByCode,
        )
    }

    fun files(batchId: UUID): FileRowTotals {
        val f = IMPORTS_ROW_FILE
        val i = IMPORTS_FILE
        val held = listOf(IntakeState.RECEIVED.name, IntakeState.PURGED.name)
        val byPath = f.MATCH.`in`(PathMatch.EXACT.name, PathMatch.CASE.name)
        val matched = byPath.and(i.STATE.`in`(held))
        val notReceived =
            f.MATCH.isNull
                .or(f.MATCH.eq(PathMatch.NONE.name))
                .or(byPath.and(i.STATE.isNull.or(i.STATE.notIn(held))))
        return tenantDsl
            .current()
            .select(
                DSL.count(),
                DSL.count().filterWhere(matched),
                DSL.count().filterWhere(notReceived),
                DSL.count().filterWhere(f.MATCH.eq(PathMatch.AMBIGUOUS.name)),
            ).from(f)
            .leftJoin(i)
            .on(i.ID.eq(f.FILE_ID))
            .where(f.BATCH_ID.eq(batchId))
            .fetchSingle { FileRowTotals(it.value1() ?: 0, it.value2() ?: 0, it.value3() ?: 0, it.value4() ?: 0) }
    }

    fun originals(batchId: UUID): OriginalTotals {
        val dsl = tenantDsl.current()
        val i = IMPORTS_FILE
        val f = IMPORTS_ROW_FILE
        val r = IMPORTS_ROW
        val referenced = DSL.field(DSL.exists(DSL.selectOne().from(f).where(f.FILE_ID.eq(i.ID))))
        val imported =
            DSL.field(
                DSL.exists(
                    DSL
                        .selectOne()
                        .from(f)
                        .join(r)
                        .on(r.ID.eq(f.ROW_ID))
                        .where(f.FILE_ID.eq(i.ID), r.RESULT.eq(DSL.inline(RowResult.IMPORTED.name))),
                ),
            )
        val groups =
            dsl
                .select(i.STATE, referenced, imported, DSL.count(), DSL.sum(i.SIZE_BYTES))
                .from(i)
                .where(i.BATCH_ID.eq(batchId))
                .groupBy(i.STATE, referenced, imported)
                .fetch {
                    val count = FilesAndBytes(it.value4() ?: 0, it.value5()?.toLong() ?: 0)
                    OriginalGroup(it.value1()!!, it.value2() == true, it.value3() == true, count)
                }

        fun sum(test: (OriginalGroup) -> Boolean) = groups.filter(test).fold(FilesAndBytes.NONE) { a, g -> a + g.count }
        val stored = { g: OriginalGroup -> g.state == IntakeState.RECEIVED.name || g.state == IntakeState.PURGED.name }
        return OriginalTotals(
            registered = sum { true },
            stored = sum(stored),
            refused = sum { it.state == IntakeState.REFUSED.name },
            neverUploaded = sum { it.state == IntakeState.EXPECTED.name },
            imported = sum { stored(it) && it.imported },
            heldBack = sum { stored(it) && it.referenced && !it.imported },
            unreferenced = sum { stored(it) && !it.referenced },
        )
    }

    /** The versions the import stored for rows it IMPORTED (one per Files row), distinct. */
    fun importedVersions(batchId: UUID): List<UUID> {
        val f = IMPORTS_ROW_FILE
        val r = IMPORTS_ROW
        return tenantDsl
            .current()
            .selectDistinct(f.STORED_VERSION_ID)
            .from(f)
            .join(r)
            .on(r.ID.eq(f.ROW_ID))
            .where(f.BATCH_ID.eq(batchId), r.RESULT.eq(RowResult.IMPORTED.name), f.STORED_VERSION_ID.isNotNull)
            .fetch { it.value1()!! }
    }

    /** Originals stored for rows the import did not import ("stored, not bound", expected none), in Files row order. */
    fun storedNotBound(batchId: UUID): List<StoredOriginal> {
        val f = IMPORTS_ROW_FILE
        val r = IMPORTS_ROW
        return tenantDsl
            .current()
            .select(f.ROW_NUMBER, DSL.coalesce(IMPORTS_FILE.PATH, f.PATH), f.STORED_FILE_ID, f.STORED_VERSION_ID)
            .from(f)
            .leftJoin(IMPORTS_FILE)
            .on(IMPORTS_FILE.ID.eq(f.FILE_ID))
            .leftJoin(r)
            .on(r.ID.eq(f.ROW_ID))
            .where(f.BATCH_ID.eq(batchId), f.STORED_FILE_ID.isNotNull)
            .and(r.RESULT.isNull.or(r.RESULT.ne(RowResult.IMPORTED.name)))
            .orderBy(f.ROW_NUMBER)
            .fetch { StoredOriginal(it.value1()!!, it.value2().orEmpty(), it.value3()!!, it.value4()!!) }
    }

    /** Originals the import stored for IMPORTED rows, with their Files row, in Files row order. */
    fun importedOriginals(batchId: UUID): List<StoredOriginal> {
        val f = IMPORTS_ROW_FILE
        val r = IMPORTS_ROW
        return tenantDsl
            .current()
            .select(f.ROW_NUMBER, DSL.coalesce(IMPORTS_FILE.PATH, f.PATH), f.STORED_FILE_ID, f.STORED_VERSION_ID)
            .from(f)
            .leftJoin(IMPORTS_FILE)
            .on(IMPORTS_FILE.ID.eq(f.FILE_ID))
            .join(r)
            .on(r.ID.eq(f.ROW_ID))
            .where(f.BATCH_ID.eq(batchId), f.STORED_FILE_ID.isNotNull, r.RESULT.eq(RowResult.IMPORTED.name))
            .orderBy(f.ROW_NUMBER)
            .fetch { StoredOriginal(it.value1()!!, it.value2().orEmpty(), it.value3()!!, it.value4()!!) }
    }

    /** Problems by level, then by code. */
    fun problems(batchId: UUID): Map<String, Map<String, Int>> {
        val p = IMPORTS_PROBLEM
        return tenantDsl
            .current()
            .select(p.LEVEL, p.CODE, DSL.count())
            .from(p)
            .where(p.BATCH_ID.eq(batchId))
            .groupBy(p.LEVEL, p.CODE)
            .fetch { Triple(it.value1()!!, it.value2()!!, it.value3() ?: 0) }
            .groupBy({ it.first }, { it.second to it.third })
            .mapValues { (_, codes) -> codes.toMap().toSortedMap() }
            .toSortedMap()
    }

    /** Problems of [stage] with [code] (the promotion's hash mismatches). */
    fun count(
        batchId: UUID,
        stage: ProblemStage,
        code: String,
    ): Int =
        tenantDsl.current().fetchCount(
            IMPORTS_PROBLEM,
            IMPORTS_PROBLEM.BATCH_ID
                .eq(batchId)
                .and(IMPORTS_PROBLEM.STAGE.eq(stage.name))
                .and(IMPORTS_PROBLEM.CODE.eq(code)),
        )

    private data class OriginalGroup(
        val state: String,
        val referenced: Boolean,
        val imported: Boolean,
        val count: FilesAndBytes,
    )

    companion object {
        /** Each kind's rendition counts over [versions] (the imported originals) from their [states]; absent: not needed. */
        fun renditions(
            versions: Collection<UUID>,
            states: Map<UUID, Map<RenditionKind, RenditionState>>,
        ): Map<String, RenditionTotals> =
            RenditionKind.entries.associate { kind ->
                val of = versions.map { states[it]?.get(kind) }

                fun count(state: RenditionState?) = of.count { it == state }
                kind.name to
                    RenditionTotals(
                        originals = versions.size,
                        done = count(RenditionState.DONE),
                        failed = count(RenditionState.FAILED),
                        notPossible = count(RenditionState.NOT_POSSIBLE),
                        pending = count(RenditionState.PENDING),
                        notNeeded = count(null),
                    )
            }
    }
}
