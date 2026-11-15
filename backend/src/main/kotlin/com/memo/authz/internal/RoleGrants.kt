package com.memo.authz.internal

import com.memo.audit.AuditLog
import com.memo.common.authz.AccessAdministration
import com.memo.common.authz.AccessCaches
import com.memo.common.authz.BuiltInRole
import com.memo.common.authz.Permission
import com.memo.common.cache.CacheInvalidation
import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.references.AUTHZ_GRANT
import com.memo.jooq.tables.references.AUTHZ_ROLE
import com.memo.jooq.tables.references.AUTHZ_ROLE_PERMISSION
import org.jooq.impl.DSL
import org.springframework.stereotype.Service
import java.util.UUID

/** Grants of built-in roles on behalf of other modules (ADR-0027). Inside a tenant transaction. */
@Service
class RoleGrants(
    private val roles: RoleStore,
    private val catalog: RoleCatalog,
    private val tenantDsl: TenantDsl,
    private val invalidation: CacheInvalidation,
    private val audit: AuditLog,
    private val levels: ClassificationStore,
) : AccessAdministration {
    override fun ensureClearance(
        user: UUID,
        rank: Int,
    ) {
        val all = levels.list()
        val target = requireNotNull(all.firstOrNull { it.rank == rank }) { "no classification level of rank $rank" }
        val before = levels.clearanceOf(user)?.let { id -> all.firstOrNull { it.id == id } } ?: all.first()
        if (before.rank >= rank) return
        levels.setClearance(user, target.id)
        audit.record(
            "authz.clearance.changed",
            "authz.clearance",
            user.toString(),
            mapOf("levelId" to before.id.toString(), "rank" to before.rank),
            mapOf("levelId" to target.id.toString(), "rank" to target.rank),
        )
        invalidation.invalidate(AccessCaches.GRANTS, tenantDsl.tenant(), user.toString())
    }

    override fun grantBuiltIn(
        user: UUID,
        role: BuiltInRole,
        scopeUnit: UUID?,
    ) {
        require(role != BuiltInRole.USER) { "every active user holds the user role implicitly" }
        require(scopeUnit == null || role.permissions.any { it.scopable }) { "${role.key} has no permission that can be scoped" }
        val grant = roles.insertGrant(user, roleId(role), scopeUnit) ?: return
        audit.record(
            "authz.grant.created",
            "authz.grant",
            grant.toString(),
            after = mapOf("userId" to user.toString(), "role" to role.key, "scopeUnitId" to scopeUnit?.toString()),
        )
        invalidation.invalidate(AccessCaches.GRANTS, tenantDsl.tenant(), user.toString())
    }

    override fun grantCustom(
        user: UUID,
        roleName: String,
        permissions: Set<Permission>,
        scopeUnit: UUID?,
    ) {
        val existing = catalog.all().firstOrNull { it.builtIn == null && it.name.equals(roleName, ignoreCase = true) }
        val roleId =
            existing?.id ?: catalog.insert(roleName, null, permissions).also { id ->
                audit.record("authz.role.created", "authz.role", id.toString(), after = mapOf("name" to roleName))
            }
        val grant = roles.insertGrant(user, roleId, scopeUnit) ?: return
        audit.record(
            "authz.grant.created",
            "authz.grant",
            grant.toString(),
            after = mapOf("userId" to user.toString(), "roleId" to roleId.toString(), "scopeUnitId" to scopeUnit?.toString()),
        )
        invalidation.invalidate(AccessCaches.GRANTS, tenantDsl.tenant(), user.toString())
    }

    override fun tenantAdmins(users: Collection<UUID>): Set<UUID> = roles.tenantWideHolders(roleId(BuiltInRole.TENANT_ADMIN), users)

    override fun otherTenantAdminsLocked(except: UUID): Set<UUID> {
        val roleId = roleId(BuiltInRole.TENANT_ADMIN)
        roles.lockRole(roleId)
        return roles.tenantWideHolders(roleId) - except
    }

    override fun tenantWideHolders(permission: Permission): Set<UUID> {
        val builtIn = BuiltInRole.entries.filter { permission in it.permissions && it != BuiltInRole.USER }.map { it.key }
        val custom =
            DSL.exists(
                DSL
                    .selectOne()
                    .from(AUTHZ_ROLE_PERMISSION)
                    .where(AUTHZ_ROLE_PERMISSION.ROLE_ID.eq(AUTHZ_ROLE.ID), AUTHZ_ROLE_PERMISSION.PERMISSION.eq(permission.key)),
            )
        return tenantDsl
            .current()
            .selectDistinct(AUTHZ_GRANT.USER_ID)
            .from(AUTHZ_GRANT)
            .join(AUTHZ_ROLE)
            .on(AUTHZ_ROLE.TENANT_ID.eq(AUTHZ_GRANT.TENANT_ID), AUTHZ_ROLE.ID.eq(AUTHZ_GRANT.ROLE_ID))
            .where(AUTHZ_GRANT.SCOPE_UNIT_ID.isNull, AUTHZ_ROLE.KEY.`in`(builtIn).or(AUTHZ_ROLE.KEY.isNull.and(custom)))
            .fetchSet(AUTHZ_GRANT.USER_ID)
            .filterNotNull()
            .toSet()
    }

    /** The built-in role's id, creating the built-in roles first in a tenant that predates them. */
    private fun roleId(role: BuiltInRole): UUID =
        roles.builtInRoleId(role) ?: run {
            roles.ensureBuiltInRoles()
            requireNotNull(roles.builtInRoleId(role)) { "built-in role ${role.key} missing" }
        }
}
