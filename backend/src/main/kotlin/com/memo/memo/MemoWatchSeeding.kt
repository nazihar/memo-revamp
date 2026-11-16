package com.memo.memo

import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.memo.internal.MemoListStore
import com.memo.memo.internal.MemoWatchStore
import com.memo.memo.internal.WatchReason
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Following for seeders (the demo tenant, Phase 3 E05 T8). Memos seeded before following existed have no automatic
 * watches; this fills that gap once. Never called from requests. Its own tenant transaction.
 */
@Service
class MemoWatchSeeding(
    private val store: MemoWatchStore,
    private val lists: MemoListStore,
    private val transactions: TenantTransactions,
) {
    /**
     * [user] follows every memo they started, as automatic following would have made them, unless they already follow
     * a memo; how many watches were added.
     */
    fun followStarted(user: UUID): Int =
        transactions.inTenant(TenantContext.current()) {
            if (store.followsAny(user)) 0 else lists.startedBy(user).count { store.add(it, user, WatchReason.INITIATED) }
        }
}
