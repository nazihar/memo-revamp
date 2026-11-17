package com.memo.imports.internal.report

import com.memo.common.tenant.TenantDsl
import com.memo.imports.internal.dryrun.ProblemStage
import com.memo.imports.internal.dryrun.RowResult
import com.memo.jooq.tables.references.IMPORTS_FILE
import com.memo.jooq.tables.references.IMPORTS_PROBLEM
import com.memo.jooq.tables.references.IMPORTS_ROW
import com.memo.jooq.tables.references.IMPORTS_ROW_FILE
import org.springframework.stereotype.Repository
import java.util.HexFormat
import java.util.UUID

/** An IMPORTED row as the import left it: its reserved memo id, row key and fingerprint. */
data class ImportedRow(
    val rowId: UUID,
    val rowNumber: Int,
    val memoId: UUID,
    val legacyNumberKey: String?,
    /** Lower-case hex. */
    val fingerprint: String?,
)

/** A Files row with the original it matched at intake and the copy the import stored for it. */
data class RowOriginal(
    val rowFileId: UUID,
    val rowId: UUID?,
    val rowNumber: Int,
    val path: String?,
    /** Lower-case hex SHA-256 of the original as received at intake. */
    val intakeSha256: String?,
    val storedFileId: UUID?,
    val storedVersionId: UUID?,
)

/** What the verification pass reads and clears (design E06 §5.4). Inside a tenant transaction. */
@Repository
class VerifyReads(
    private val tenantDsl: TenantDsl,
) {
    /** IMPORTED rows of [batchId] after row [afterRow], in row order, at most [limit]. */
    fun importedAfter(
        batchId: UUID,
        afterRow: Int,
        limit: Int,
    ): List<ImportedRow> =
        tenantDsl
            .current()
            .select(IMPORTS_ROW.ID, IMPORTS_ROW.ROW_NUMBER, IMPORTS_ROW.MEMO_ID, IMPORTS_ROW.LEGACY_NUMBER_KEY, IMPORTS_ROW.FINGERPRINT)
            .from(IMPORTS_ROW)
            .where(
                IMPORTS_ROW.BATCH_ID.eq(batchId),
                IMPORTS_ROW.RESULT.eq(RowResult.IMPORTED.name),
                IMPORTS_ROW.ROW_NUMBER.gt(afterRow),
                IMPORTS_ROW.MEMO_ID.isNotNull,
            ).orderBy(IMPORTS_ROW.ROW_NUMBER)
            .limit(limit)
            .fetch { ImportedRow(it.value1()!!, it.value2()!!, it.value3()!!, it.value4(), it.value5()?.let(HEX::formatHex)) }

    /** The Files rows of [rowIds] with their intake hash and stored copy, in row order. */
    fun originals(rowIds: Collection<UUID>): List<RowOriginal> {
        if (rowIds.isEmpty()) return emptyList()
        val f = IMPORTS_ROW_FILE
        val i = IMPORTS_FILE
        return tenantDsl
            .current()
            .select(f.ID, f.ROW_ID, f.ROW_NUMBER, f.PATH, i.SHA256, f.STORED_FILE_ID, f.STORED_VERSION_ID)
            .from(f)
            .leftJoin(i)
            .on(i.ID.eq(f.FILE_ID))
            .where(f.ROW_ID.`in`(rowIds))
            .orderBy(f.ROW_NUMBER)
            .fetch { r ->
                RowOriginal(r.value1()!!, r.value2(), r.value3()!!, r.value4(), r.value5()?.let(HEX::formatHex), r.value6(), r.value7())
            }
    }

    /** Removes what an earlier, interrupted verification of [batchId] recorded: it starts again from the beginning. */
    fun clear(batchId: UUID) {
        tenantDsl
            .current()
            .deleteFrom(IMPORTS_PROBLEM)
            .where(IMPORTS_PROBLEM.BATCH_ID.eq(batchId), IMPORTS_PROBLEM.STAGE.eq(ProblemStage.VERIFY.name))
            .execute()
    }

    private companion object {
        val HEX: HexFormat = HexFormat.of()
    }
}
