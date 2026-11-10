package com.memo.authz.internal

import com.memo.common.authz.ClassificationLevelRef
import com.memo.common.authz.Clearances
import org.springframework.stereotype.Service
import java.util.UUID

/** [Clearances] from the policy's per-user cache (evicted on every clearance change) and the level table. */
@Service
class AuthzClearances(
    private val policy: AuthzPolicy,
    private val store: ClassificationStore,
) : Clearances {
    override fun rankOf(users: Collection<UUID>): Map<UUID, Int> = users.associateWith { policy.accessOf(it).clearanceRank }

    override fun levels(): List<ClassificationLevelRef> = store.list().map { ClassificationLevelRef(it.id, it.rank, it.name) }
}
