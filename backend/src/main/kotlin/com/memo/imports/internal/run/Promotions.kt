package com.memo.imports.internal.run

import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.references.IMPORTS_FILE
import com.memo.jooq.tables.references.IMPORTS_ROW
import com.memo.jooq.tables.references.IMPORTS_ROW_FILE
import org.springframework.stereotype.Repository
import java.util.HexFormat
import java.util.UUID

/** One Files row's original to promote: the staged version received for it and the SHA-256 it had at intake. */
data class Promotion(
    val rowFileId: UUID,
    val rowNumber: Int,
    val path: String?,
    /** Null when no received original matched (the row cannot be imported). */
    val intakeVersionId: UUID?,
    /** Lower-case hex; null with [intakeVersionId]. */
    val sha256: String?,
)

/** A Memos row as the import takes it: the memo id reserved at the start, and its Files rows' originals in row order. */
data class RowTarget(
    val memoId: UUID,
    val promotions: List<Promotion>,
)

/** What the import reads and records about a row's originals (design E06 §5.3 steps 5 and 6). Inside a tenant transaction. */
@Repository
class Promotions(
    private val tenantDsl: TenantDsl,
) {
    /** The row's reserved memo id and its originals to promote; null when the row has no memo id (it was never READY at start). */
    fun target(rowId: UUID): RowTarget? {
        val dsl = tenantDsl.current()
        val memoId =
            dsl
                .select(IMPORTS_ROW.MEMO_ID)
                .from(IMPORTS_ROW)
                .where(IMPORTS_ROW.ID.eq(rowId))
                .fetchOne()
                ?.value1() ?: return null
        val f = IMPORTS_ROW_FILE
        val i = IMPORTS_FILE
        val promotions =
            dsl
                .select(f.ID, f.ROW_NUMBER, f.PATH, i.VERSION_ID, i.SHA256)
                .from(f)
                .leftJoin(i)
                .on(i.ID.eq(f.FILE_ID))
                .where(f.ROW_ID.eq(rowId))
                .orderBy(f.ROW_NUMBER)
                .fetch { r -> Promotion(r.value1()!!, r.value2()!!, r.value3(), r.value4(), r.value5()?.let(HexFormat.of()::formatHex)) }
        return RowTarget(memoId, promotions)
    }

    /** The Files row's original was promoted as [fileId] at [versionId] (kept even if its row then fails). */
    fun stored(
        rowFileId: UUID,
        fileId: UUID,
        versionId: UUID,
    ) {
        tenantDsl
            .current()
            .update(IMPORTS_ROW_FILE)
            .set(IMPORTS_ROW_FILE.STORED_FILE_ID, fileId)
            .set(IMPORTS_ROW_FILE.STORED_VERSION_ID, versionId)
            .where(IMPORTS_ROW_FILE.ID.eq(rowFileId))
            .execute()
    }
}
