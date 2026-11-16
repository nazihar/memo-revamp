package com.memo.imports.internal

import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.files.FilePurges
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Purges a batch's staged copies that a later upload replaced (design E06 §4.3): a source uploaded again, or an original
 * received or refused again at the same path. Their objects leave the `work` bucket; the file rows and hashes stay. The
 * sweep (T8) runs it for every open batch, and purges everything staged when a batch closes. Call outside a transaction,
 * with the tenant bound.
 */
@Service
class StagedPurges(
    private val staged: StagedVersionStore,
    private val purges: FilePurges,
    private val transactions: TenantTransactions,
) {
    /** Purges up to [limit] replaced versions of [batchId]; returns how many. */
    fun purgeSuperseded(
        batchId: UUID,
        limit: Int = PAGE,
    ): Int {
        val tenant = TenantContext.current()
        val versions = transactions.readInTenant(tenant) { staged.superseded(batchId, limit) }
        versions.forEach { version ->
            purges.purge(version)
            transactions.inTenant(tenant) { staged.purged(version) }
        }
        return versions.size
    }

    private companion object {
        const val PAGE = 500
    }
}
