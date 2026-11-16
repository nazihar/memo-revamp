package com.memo.memo.internal.legacy

import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.references.MEMO_LEGACY
import com.memo.jooq.tables.references.MEMO_LEGACY_FILE
import com.memo.memo.BoundOriginal
import com.memo.memo.ImportedMemoRecord
import com.memo.memo.MemoOrigin
import org.springframework.stereotype.Repository
import java.util.HexFormat
import java.util.UUID
import com.memo.jooq.tables.references.MEMO_ as MEMO

/**
 * Imported memos as stored, for the import's verification pass (design E06 §5.4): the memo row with origin IMPORTED, its
 * legacy facts and its bound originals, two queries per call. Inside a tenant transaction.
 */
@Repository
class ImportedMemoReader(
    private val tenantDsl: TenantDsl,
) {
    fun of(memoIds: Collection<UUID>): Map<UUID, ImportedMemoRecord> {
        if (memoIds.isEmpty()) return emptyMap()
        val dsl = tenantDsl.current()
        val l = MEMO_LEGACY
        val f = MEMO_LEGACY_FILE
        val originals =
            dsl
                .select(f.MEMO_ID, f.FILE_ID, f.RELATIVE_PATH, f.SHA256, f.SIZE_BYTES)
                .from(f)
                .where(f.MEMO_ID.`in`(memoIds))
                .orderBy(f.MEMO_ID, f.POSITION)
                .fetch { r -> r.value1()!! to BoundOriginal(r.value2()!!, r.value3()!!, HEX.formatHex(r.value4()!!), r.value5()!!) }
                .groupBy({ it.first }, { it.second })
        return dsl
            .select(l.MEMO_ID, l.BATCH_ID, l.ROW_NUMBER, l.SOURCE_CODE, l.LEGACY_NUMBER_KEY, l.ROW_FINGERPRINT)
            .from(l)
            .join(MEMO)
            .on(MEMO.ID.eq(l.MEMO_ID))
            .where(l.MEMO_ID.`in`(memoIds), MEMO.ORIGIN.eq(MemoOrigin.IMPORTED.name))
            .fetch { r ->
                val memoId = r.value1()!!
                ImportedMemoRecord(
                    memoId = memoId,
                    batchId = r.value2()!!,
                    rowNumber = r.value3()!!,
                    sourceCode = r.value4()!!,
                    legacyNumberKey = r.value5()!!,
                    fingerprint = HEX.formatHex(r.value6()!!),
                    originals = originals[memoId].orEmpty(),
                )
            }.associateBy { it.memoId }
    }

    private companion object {
        val HEX: HexFormat = HexFormat.of()
    }
}
