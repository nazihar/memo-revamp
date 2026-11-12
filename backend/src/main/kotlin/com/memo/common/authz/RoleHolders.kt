package com.memo.common.authz

import java.util.UUID

/**
 * Who holds a role where (ADR-0027, design E04 §1), for the workflow's `role` assignees. Implemented by `authz`; inside a
 * tenant transaction.
 */
interface RoleHolders {
    /**
     * The users granted [roleId] tenant-wide, or for [unit] or a unit above it ([unit] null: tenant-wide grants only).
     * Account state is not checked here; the caller filters people who cannot act.
     */
    fun holders(
        roleId: UUID,
        unit: UUID?,
    ): Set<UUID>

    /** Whether the tenant has a role with [roleId]. */
    fun exists(roleId: UUID): Boolean
}
