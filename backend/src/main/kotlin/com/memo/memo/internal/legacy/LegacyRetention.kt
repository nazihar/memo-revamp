package com.memo.memo.internal.legacy

import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.files.Files
import com.memo.files.RecordRetentions
import com.memo.memo.internal.MemoStore
import com.memo.memo.internal.MemoTypes
import org.springframework.stereotype.Component
import java.time.ZoneOffset
import java.util.UUID

/**
 * The retention of an imported memo's originals (design E06 §10, as built in T3 and T7): the import promotes them before
 * their memo exists, so the files service locks them for the tenant's default years; once the memo is committed its type
 * decides. Each original's current version is kept until it was stored plus the type's years when that is later (locks
 * only lengthen; `RecordRetentions` audits each extension). A type without retention years leaves the default.
 */
@Component
class LegacyRetention(
    private val memos: MemoStore,
    private val types: MemoTypes,
    private val reader: LegacyMemoReader,
    private val files: Files,
    private val retentions: RecordRetentions,
    private val transactions: TenantTransactions,
) {
    /** Outside a transaction, with the tenant bound; returns how many versions were extended. */
    fun retain(memoId: UUID): Int {
        val tenant = TenantContext.current()
        val targets =
            transactions.readInTenant(tenant) {
                val memo = memos.find(memoId) ?: return@readInTenant emptyList()
                val years =
                    types
                        .versionFor(memo)
                        ?.settings
                        ?.retention
                        ?.years ?: return@readInTenant emptyList()
                reader.originals(memoId).mapNotNull { original ->
                    files.info(original.fileId)?.currentVersionId?.let(files::version)?.let { version ->
                        version.id to
                            version.uploadedAt
                                .atOffset(ZoneOffset.UTC)
                                .plusYears(years.toLong())
                                .toInstant()
                    }
                }
            }
        return targets.count { (versionId, until) -> retentions.extend(versionId, until) }
    }
}
