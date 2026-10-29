package com.memo.authz.internal

import com.memo.audit.AuditLog
import com.memo.common.authz.AccessAdministration
import com.memo.common.authz.AccessCaches
import com.memo.common.authz.BuiltInRole
import com.memo.common.cache.CacheInvalidation
import com.memo.common.tenant.TenantDsl
import org.springframework.stereotype.Service
import java.util.UUID

/** Grants of built-in roles on behalf of other modules (ADR-0027). Inside a tenant transaction. */
@Service
class RoleGrants(
    private val roles: RoleStore,
    private val tenantDsl: TenantDsl,
    private val invalidation: CacheInvalidation,
    private val audit: AuditLog,
) : AccessAdministration {
    override fun grantBuiltIn(
        user: UUID,
        role: BuiltInRole,
    ) {
        require(role != BuiltInRole.USER) { "every active user holds the user role implicitly" }
        val grant = roles.insertGrant(user, roleId(role), null) ?: return
        audit.record(
            "authz.grant.created",
            "authz.grant",
            grant.toString(),
            after = mapOf("userId" to user.toString(), "role" to role.key, "scopeUnitId" to null),
        )
        invalidation.invalidate(AccessCaches.GRANTS, tenantDsl.tenant(), user.toString())
    }

    override fun tenantAdmins(users: Collection<UUID>): Set<UUID> = roles.tenantWideHolders(roleId(BuiltInRole.TENANT_ADMIN), users)

    override fun otherTenantAdminsLocked(except: UUID): Set<UUID> {
        val roleId = roleId(BuiltInRole.TENANT_ADMIN)
        roles.lockRole(roleId)
        return roles.tenantWideHolders(roleId) - except
    }

    /** The built-in role's id, creating the built-in roles first in a tenant that predates them. */
    private fun roleId(role: BuiltInRole): UUID =
        roles.builtInRoleId(role) ?: run {
            roles.ensureBuiltInRoles()
            requireNotNull(roles.builtInRoleId(role)) { "built-in role ${role.key} missing" }
        }
}
