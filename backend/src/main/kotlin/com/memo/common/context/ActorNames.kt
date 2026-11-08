package com.memo.common.context

import java.util.UUID

/**
 * Display names of users, for views that show who did something (the audit viewer). Implemented by the identity module,
 * so modules below it (audit) can show names without depending on it. Inside a tenant transaction.
 */
fun interface ActorNames {
    fun names(users: Collection<UUID>): Map<UUID, String>
}
