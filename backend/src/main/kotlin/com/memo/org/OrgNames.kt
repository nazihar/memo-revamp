package com.memo.org

import com.memo.org.internal.UnitStore
import org.springframework.stereotype.Service
import java.util.UUID

/** Names of organisation records by id, for display in other modules (the workflow builder, E06). Inside a tenant transaction. */
@Service
class OrgNames(
    private val units: UnitStore,
) {
    /** The names of the units of [ids] that exist. */
    fun units(ids: Collection<UUID>): Map<UUID, String> = units.names(ids)
}
