package com.memo.authz.internal

import com.memo.common.authz.OrgTree
import com.memo.common.authz.RoleHolders
import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.references.AUTHZ_GRANT
import com.memo.jooq.tables.references.AUTHZ_ROLE
import org.springframework.stereotype.Service
import java.util.UUID

/** [RoleHolders] over the grants table; a grant scoped to a unit covers that unit's subtree (ADR-0027). */
@Service
class AuthzRoleHolders(
    private val tenantDsl: TenantDsl,
    private val tree: OrgTree,
) : RoleHolders {
    override fun holders(
        roleId: UUID,
        unit: UUID?,
    ): Set<UUID> =
        tenantDsl
            .current()
            .select(AUTHZ_GRANT.USER_ID, AUTHZ_GRANT.SCOPE_UNIT_ID)
            .from(AUTHZ_GRANT)
            .where(AUTHZ_GRANT.ROLE_ID.eq(roleId))
            .fetch()
            .filter { grant ->
                val scope = grant.value2()
                scope == null || (unit != null && tree.isWithin(unit, scope))
            }.mapNotNull { it.value1() }
            .toSet()

    override fun exists(roleId: UUID): Boolean = tenantDsl.current().fetchExists(AUTHZ_ROLE, AUTHZ_ROLE.ID.eq(roleId))
}
