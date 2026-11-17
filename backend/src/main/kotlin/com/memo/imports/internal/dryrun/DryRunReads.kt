package com.memo.imports.internal.dryrun

import com.memo.common.tenant.TenantDsl
import com.memo.imports.internal.IntakeState
import com.memo.jooq.tables.references.IMPORTS_FILE
import com.memo.jooq.tables.references.IMPORTS_ROW
import com.memo.jooq.tables.references.IMPORTS_ROW_APPROVAL
import com.memo.jooq.tables.references.IMPORTS_ROW_FILE
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.util.HexFormat
import java.util.UUID

/** A staged Memos row with its id. */
data class StoredMemo(
    val id: UUID,
    val rowNumber: Int,
    val cells: Map<String, String>,
)

/** The original a Files row matched, as intake holds it. */
data class MatchedOriginal(
    val intakeId: UUID,
    val path: String,
    val state: IntakeState,
    /** Lower-case hex; null unless received. */
    val sha256: String?,
    val sizeBytes: Long,
    val refusal: String?,
)

/** A staged Files row of a Memos row, with its match. */
data class StoredFile(
    val rowId: UUID,
    val staged: StagedFile,
    val match: PathMatch?,
    val original: MatchedOriginal?,
)

/** A staged Approvals row of a Memos row. */
data class StoredApproval(
    val rowId: UUID,
    val staged: StagedApproval,
)

/** The reads of the validation step (design E06 §5.2 step 4): chunks of Memos rows with their Files and Approvals rows. */
@Repository
class ChunkReads(
    private val tenantDsl: TenantDsl,
    private val json: JsonMapper,
) {
    /** Memos rows after [afterRow] in row order, at most [limit]. */
    fun memos(
        batchId: UUID,
        afterRow: Int,
        limit: Int,
    ): List<StoredMemo> =
        tenantDsl
            .current()
            .select(IMPORTS_ROW.ID, IMPORTS_ROW.ROW_NUMBER, IMPORTS_ROW.DATA)
            .from(IMPORTS_ROW)
            .where(IMPORTS_ROW.BATCH_ID.eq(batchId), IMPORTS_ROW.ROW_NUMBER.gt(afterRow))
            .orderBy(IMPORTS_ROW.ROW_NUMBER)
            .limit(limit)
            .fetch { StoredMemo(it.value1()!!, it.value2()!!, cells(it.value3()?.data())) }

    /** The Memos row [rowId] (the import reads its rows one at a time, design E06 §5.3). */
    fun memo(rowId: UUID): StoredMemo? =
        tenantDsl
            .current()
            .select(IMPORTS_ROW.ID, IMPORTS_ROW.ROW_NUMBER, IMPORTS_ROW.DATA)
            .from(IMPORTS_ROW)
            .where(IMPORTS_ROW.ID.eq(rowId))
            .fetchOne { StoredMemo(it.value1()!!, it.value2()!!, cells(it.value3()?.data())) }

    fun files(rowIds: Collection<UUID>): List<StoredFile> {
        if (rowIds.isEmpty()) return emptyList()
        val f = IMPORTS_ROW_FILE
        val i = IMPORTS_FILE
        return tenantDsl
            .current()
            .select(
                f.ROW_ID,
                f.ROW_NUMBER,
                f.LEGACY_NUMBER_KEY,
                f.PATH,
                f.ROLE,
                f.TITLE,
                f.EXPECTED_SHA256,
                f.CLASSIFICATION,
                f.MATCH,
                i.ID,
                i.PATH,
                i.STATE,
                i.SHA256,
                i.SIZE_BYTES,
                i.REFUSAL,
            ).from(f)
            .leftJoin(i)
            .on(i.ID.eq(f.FILE_ID))
            .where(f.ROW_ID.`in`(rowIds))
            .orderBy(f.ROW_NUMBER)
            .fetch { r ->
                StoredFile(
                    rowId = r.get(f.ROW_ID)!!,
                    staged =
                        StagedFile(
                            r.get(f.ROW_NUMBER)!!,
                            r.get(f.LEGACY_NUMBER_KEY),
                            r.get(f.PATH),
                            r.get(f.ROLE),
                            r.get(f.TITLE),
                            r.get(f.EXPECTED_SHA256),
                            r.get(f.CLASSIFICATION),
                        ),
                    match = r.get(f.MATCH)?.let(PathMatch::valueOf),
                    original =
                        r.get(i.ID)?.let { id ->
                            MatchedOriginal(
                                id,
                                r.get(i.PATH)!!,
                                IntakeState.valueOf(r.get(i.STATE)!!),
                                r.get(i.SHA256)?.let { HexFormat.of().formatHex(it) },
                                r.get(i.SIZE_BYTES)!!,
                                r.get(i.REFUSAL),
                            )
                        },
                )
            }
    }

    fun approvals(rowIds: Collection<UUID>): List<StoredApproval> {
        if (rowIds.isEmpty()) return emptyList()
        val a = IMPORTS_ROW_APPROVAL
        return tenantDsl
            .current()
            .selectFrom(a)
            .where(a.ROW_ID.`in`(rowIds))
            .orderBy(a.ROW_NUMBER)
            .fetch { r ->
                StoredApproval(
                    r.rowId!!,
                    StagedApproval(
                        r.rowNumber,
                        r.legacyNumberKey,
                        r.ordinal,
                        r.name,
                        r.email,
                        r.legacyId,
                        r.position,
                        r.decision,
                        r.decidedOn,
                        r.remark,
                    ),
                )
            }
    }

    /** The row's cells; the data also holds the rank the dry run recorded (a number), which is not a cell. */
    private fun cells(data: String?): Map<String, String> =
        data?.let { json.readTree(it).get(StagingStore.CELLS) }?.let { json.convertValue(it, CELLS) }.orEmpty()

    private companion object {
        val CELLS = object : TypeReference<Map<String, String>>() {}
    }
}

/** A Files or Approvals row whose legacy number no Memos row has (`ORPHAN_ROW`). */
data class Orphan(
    val sheet: ProblemSheet,
    val rowNumber: Int,
    val key: String?,
)

/** The set-based checks across rows (design E06 §5.2): duplicates, similar numbers, shared content and orphans. */
@Repository
class CrossRowReads(
    private val tenantDsl: TenantDsl,
) {
    /** Legacy number keys that more than one Memos row has. */
    fun duplicateKeys(batchId: UUID): Set<String> =
        tenantDsl
            .current()
            .select(IMPORTS_ROW.LEGACY_NUMBER_KEY)
            .from(IMPORTS_ROW)
            .where(IMPORTS_ROW.BATCH_ID.eq(batchId), IMPORTS_ROW.LEGACY_NUMBER_KEY.isNotNull)
            .groupBy(IMPORTS_ROW.LEGACY_NUMBER_KEY)
            .having(DSL.count().gt(1))
            .fetch { it.value1()!! }
            .toSet()

    /**
     * Keys that differ from another key only in case or separators (`NUMBER_SIMILAR`: search's reference matching would
     * find both). Keys are already upper case, so only separators are left to strip.
     */
    fun similarKeys(batchId: UUID): Set<String> =
        tenantDsl
            .current()
            .selectDistinct(IMPORTS_ROW.LEGACY_NUMBER_KEY)
            .from(IMPORTS_ROW)
            .where(IMPORTS_ROW.BATCH_ID.eq(batchId), IMPORTS_ROW.LEGACY_NUMBER_KEY.isNotNull)
            .fetch { it.value1()!! }
            .groupBy { key -> key.filter { it.isLetterOrDigit() } }
            .values
            .filter { it.size > 1 }
            .flatten()
            .toSet()

    /** Memos rows whose originals share content (the same SHA-256) with another row's (`FILE_SHARED`). */
    fun sharedRows(batchId: UUID): Set<UUID> {
        val f = IMPORTS_ROW_FILE
        val i = IMPORTS_FILE
        val shared =
            DSL
                .select(i.SHA256)
                .from(f)
                .join(i)
                .on(i.ID.eq(f.FILE_ID))
                .where(f.BATCH_ID.eq(batchId), f.ROW_ID.isNotNull, i.SHA256.isNotNull)
                .groupBy(i.SHA256)
                .having(DSL.countDistinct(f.ROW_ID).gt(1))
        return tenantDsl
            .current()
            .selectDistinct(f.ROW_ID)
            .from(f)
            .join(i)
            .on(i.ID.eq(f.FILE_ID))
            .where(f.BATCH_ID.eq(batchId), f.ROW_ID.isNotNull, i.SHA256.`in`(shared))
            .fetch { it.value1()!! }
            .toSet()
    }

    /** Files and Approvals rows that found no Memos row. */
    fun orphans(batchId: UUID): List<Orphan> {
        val f = IMPORTS_ROW_FILE
        val a = IMPORTS_ROW_APPROVAL
        val files =
            tenantDsl
                .current()
                .select(f.ROW_NUMBER, f.LEGACY_NUMBER_KEY)
                .from(f)
                .where(f.BATCH_ID.eq(batchId), f.ROW_ID.isNull)
                .orderBy(f.ROW_NUMBER)
                .fetch { Orphan(ProblemSheet.FILES, it.value1()!!, it.value2()) }
        val approvals =
            tenantDsl
                .current()
                .select(a.ROW_NUMBER, a.LEGACY_NUMBER_KEY)
                .from(a)
                .where(a.BATCH_ID.eq(batchId), a.ROW_ID.isNull)
                .orderBy(a.ROW_NUMBER)
                .fetch { Orphan(ProblemSheet.APPROVALS, it.value1()!!, it.value2()) }
        return files + approvals
    }

    /** Received or refused originals that no Files row matched (`FILE_UNREFERENCED`, or `FILE_REFUSED` for a refused one). */
    fun unreferenced(batchId: UUID): List<MatchedOriginal> {
        val f = IMPORTS_ROW_FILE
        val i = IMPORTS_FILE
        return tenantDsl
            .current()
            .select(i.ID, i.PATH, i.STATE, i.SHA256, i.SIZE_BYTES, i.REFUSAL)
            .from(i)
            .where(i.BATCH_ID.eq(batchId), i.STATE.`in`(IntakeState.RECEIVED.name, IntakeState.REFUSED.name))
            .andNotExists(DSL.selectOne().from(f).where(f.BATCH_ID.eq(batchId), f.FILE_ID.eq(i.ID)))
            .orderBy(i.PATH)
            .fetch { r ->
                MatchedOriginal(
                    r.value1()!!,
                    r.value2()!!,
                    IntakeState.valueOf(r.value3()!!),
                    r.value4()?.let { HexFormat.of().formatHex(it) },
                    r.value5()!!,
                    r.value6(),
                )
            }
    }

    /** Registered originals whose path differs from another only in case (`PATH_CASE_ONLY`: both are kept). */
    fun caseOnly(batchId: UUID): List<Pair<UUID, String>> {
        val i = IMPORTS_FILE
        val folds =
            DSL
                .select(i.PATH_FOLD)
                .from(i)
                .where(i.BATCH_ID.eq(batchId))
                .groupBy(i.PATH_FOLD)
                .having(DSL.count().gt(1))
        return tenantDsl
            .current()
            .select(i.ID, i.PATH)
            .from(i)
            .where(i.BATCH_ID.eq(batchId), i.PATH_FOLD.`in`(folds))
            .orderBy(i.PATH)
            .fetch { it.value1()!! to it.value2()!! }
    }

    /** Files rows by how their path matched. */
    fun matches(batchId: UUID): Map<String, Int> =
        tenantDsl
            .current()
            .select(IMPORTS_ROW_FILE.MATCH, DSL.count())
            .from(IMPORTS_ROW_FILE)
            .where(IMPORTS_ROW_FILE.BATCH_ID.eq(batchId))
            .groupBy(IMPORTS_ROW_FILE.MATCH)
            .fetch { (it.value1() ?: PathMatch.NONE.name) to (it.value2() ?: 0) }
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.sum() }
}
