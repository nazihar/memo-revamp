package com.memo.authz.internal

import com.memo.audit.AuditLog
import com.memo.common.authz.AccessCaches
import com.memo.common.authz.BuiltInRole
import com.memo.common.authz.GrantsChanged
import com.memo.common.authz.OrgTree
import com.memo.common.authz.Permission
import com.memo.common.authz.Policy
import com.memo.common.cache.CacheInvalidation
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.tenant.TenantContext
import com.memo.common.web.RefusedException
import com.memo.common.web.refuseIf
import com.memo.identity.LegacyTenantAdminFlag
import com.memo.identity.UserAccounts
import com.memo.identity.UserId
import com.memo.identity.UserStatus
import com.memo.identity.UserSummaries
import com.memo.identity.recipient
import com.memo.notification.NotificationKind
import com.memo.notification.Notifications
import com.memo.tenancy.TenantRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.util.UUID

/** An object that does not exist in this tenant (404). */
class AuthzNotFoundException(
    what: String,
) : RuntimeException("$what not found")

/**
 * Custom roles (ADR-0027): named sets of catalogue permissions. Built-in roles cannot change. Every change needs
 * `authz.roles.manage`, is audited and evicts the tenant's cached grants. Inside a tenant transaction.
 */
@Service
class RoleAdmin(
    private val roles: RoleCatalog,
    private val policy: Policy,
    private val invalidation: CacheInvalidation,
    private val audit: AuditLog,
) {
    fun get(id: UUID): Role = roles.find(id) ?: throw AuthzNotFoundException("role")

    fun create(
        name: String,
        description: String?,
        permissions: Set<Permission>,
    ): Role {
        policy.require(Permission.AUTHZ_ROLES_MANAGE)
        check(name, permissions, except = null)
        val id = roles.insert(name, description, permissions)
        val created = get(id)
        audit.record("authz.role.created", OBJECT_TYPE, id.toString(), after = created.audited())
        return created
    }

    fun update(
        id: UUID,
        expectedVersion: Long,
        name: String,
        description: String?,
        permissions: Set<Permission>,
    ): Role {
        policy.require(Permission.AUTHZ_ROLES_MANAGE)
        val before = get(id)
        refuseIf(before.builtIn != null, "BUILT_IN") { "Built-in roles cannot be changed." }
        check(name, permissions, except = id)
        refuseIf(before.grants > 0 && permissions.none { it.scopable } && before.permissions.any { it.scopable }, "IN_USE") {
            "Some grants of this role are limited to a unit; keep at least one permission that can be limited."
        }
        if (!roles.update(
                id,
                expectedVersion,
                name,
                description,
                permissions,
            )
        ) {
            throw ConcurrentModificationException("role $id", expectedVersion)
        }
        val after = get(id)
        audit.record("authz.role.updated", OBJECT_TYPE, id.toString(), before.audited(), after.audited())
        invalidation.invalidate(AccessCaches.GRANTS, TenantContext.current())
        return after
    }

    fun delete(
        id: UUID,
        expectedVersion: Long,
    ) {
        policy.require(Permission.AUTHZ_ROLES_MANAGE)
        val before = get(id)
        refuseIf(before.builtIn != null, "BUILT_IN") { "Built-in roles cannot be removed." }
        refuseIf(before.grants > 0, "IN_USE") { "Revoke the role from everyone first." }
        if (!roles.delete(id, expectedVersion)) throw ConcurrentModificationException("role $id", expectedVersion)
        audit.record("authz.role.deleted", OBJECT_TYPE, id.toString(), before.audited(), null)
    }

    private fun check(
        name: String,
        permissions: Set<Permission>,
        except: UUID?,
    ) {
        require(permissions.isNotEmpty()) { "A role needs at least one permission." }
        refuseIf(roles.nameTaken(name, except), "DUPLICATE_NAME") { "Another role has this name." }
    }

    private fun Role.audited() = mapOf("name" to name, "description" to description, "permissions" to permissions.map { it.key }.sorted())

    companion object {
        const val OBJECT_TYPE = "authz.role"
    }
}

/**
 * Role grants (ADR-0027): tenant-wide or for a unit subtree. The organisation keeps at least one active tenant-wide
 * tenant administrator; E03's `tenant_admin` flag is kept in step until it is dropped. Every change needs
 * `authz.roles.manage`, is audited, evicts the user's cached grants and publishes [GrantsChanged]. Inside a tenant
 * transaction.
 */
@Service
class GrantAdmin(
    private val grants: RoleStore,
    private val roles: RoleCatalog,
    private val access: RoleGrants,
    private val users: UserSummaries,
    private val accounts: UserAccounts,
    private val notifications: Notifications,
    private val registry: TenantRegistry,
    private val legacyFlag: LegacyTenantAdminFlag,
    private val orgTree: ObjectProvider<OrgTree>,
    private val policy: Policy,
    private val invalidation: CacheInvalidation,
    private val audit: AuditLog,
    private val events: ApplicationEventPublisher,
) {
    fun grantsOf(user: UUID): List<GrantRow> {
        policy.require(Permission.AUTHZ_ROLES_MANAGE)
        requireUser(user)
        return grants.grantsOf(user)
    }

    fun grant(
        user: UUID,
        roleId: UUID,
        scopeUnit: UUID?,
    ): GrantRow {
        policy.require(Permission.AUTHZ_ROLES_MANAGE)
        requireUser(user)
        val role = roles.find(roleId) ?: throw AuthzNotFoundException("role")
        refuseIf(role.builtIn == BuiltInRole.USER, "IMPLICIT") { "Everyone holds the user role already." }
        refuseIf(scopeUnit != null && role.permissions.none { it.scopable }, "NOT_SCOPABLE") {
            "This role has no permission that can be limited to a unit."
        }
        refuseIf(scopeUnit != null && orgTree.ifAvailable?.subtree(scopeUnit).isNullOrEmpty(), "UNKNOWN_UNIT") { "No such unit." }
        val id =
            grants.insertGrant(user, roleId, scopeUnit) ?: throw RefusedException("DUPLICATE", "The person already holds this role there.")
        if (role.builtIn == BuiltInRole.TENANT_ADMIN && scopeUnit == null) legacyFlag.set(UserId(user), true)
        audit.record(
            "authz.grant.created",
            OBJECT_TYPE,
            id.toString(),
            after = mapOf("userId" to user.toString(), "roleId" to roleId.toString(), "scopeUnitId" to scopeUnit?.toString()),
        )
        changed(user, gained = true)
        accounts.find(UserId(user))?.let {
            notifications.notify(
                it.recipient(),
                NotificationKind.ROLE_GRANTED,
                mapOf("role" to role.name, "organisation" to registry.current()?.name.orEmpty()),
            )
        }
        return grants.grantsOf(user).first { it.id == id }
    }

    fun revoke(id: UUID) {
        policy.require(Permission.AUTHZ_ROLES_MANAGE)
        val grant = grants.findGrant(id) ?: throw AuthzNotFoundException("grant")
        val role = roles.find(grant.roleId)
        val tenantAdmin = role?.builtIn == BuiltInRole.TENANT_ADMIN && grant.scopeUnit == null
        if (tenantAdmin) {
            val others = access.otherTenantAdminsLocked(grant.userId)
            refuseIf(users.of(others).values.none { it.status == UserStatus.ACTIVE }, "LAST_ADMIN") {
                "The organisation needs at least one active administrator."
            }
        }
        refuseIf(!grants.deleteGrant(id), "GONE") { "The grant was already revoked." }
        if (tenantAdmin) legacyFlag.set(UserId(grant.userId), false)
        audit.record(
            "authz.grant.revoked",
            OBJECT_TYPE,
            id.toString(),
            mapOf("userId" to grant.userId.toString(), "roleId" to grant.roleId.toString(), "scopeUnitId" to grant.scopeUnit?.toString()),
            null,
        )
        changed(grant.userId, gained = false)
    }

    private fun requireUser(user: UUID) {
        users.of(user) ?: throw AuthzNotFoundException("user")
    }

    private fun changed(
        user: UUID,
        gained: Boolean,
    ) {
        val tenant = TenantContext.current()
        invalidation.invalidate(AccessCaches.GRANTS, tenant, user.toString())
        events.publishEvent(GrantsChanged(tenant.value, user, gained))
    }

    companion object {
        const val OBJECT_TYPE = "authz.grant"
    }
}

/**
 * Classification levels and clearances (ADR-0027). Levels are dense ranks 0..n-1; reordering rewrites them. Every
 * change needs `authz.classifications.manage` and is audited. Inside a tenant transaction.
 */
@Service
class ClassificationAdmin(
    private val levels: ClassificationStore,
    private val users: UserSummaries,
    private val policy: Policy,
    private val invalidation: CacheInvalidation,
    private val audit: AuditLog,
) {
    fun list(): List<ClassificationLevel> = levels.list()

    /** Adds a level above all others (the most sensitive); reorder to place it elsewhere. */
    fun create(
        name: String,
        description: String?,
    ): ClassificationLevel {
        policy.require(Permission.AUTHZ_CLASSIFICATIONS_MANAGE)
        val all = levels.list()
        refuseIf(all.any { it.name.equals(name, ignoreCase = true) }, "DUPLICATE_NAME") { "Another level has this name." }
        refuseIf(all.size >= MAX_LEVELS, "TOO_MANY") { "An organisation has at most $MAX_LEVELS levels." }
        val id = levels.insert(all.size, name, description)
        val created = get(id)
        audit.record("authz.classification.created", OBJECT_TYPE, id.toString(), after = created.audited())
        return created
    }

    fun rename(
        id: UUID,
        expectedVersion: Long,
        name: String,
        description: String?,
    ): ClassificationLevel {
        policy.require(Permission.AUTHZ_CLASSIFICATIONS_MANAGE)
        val before = get(id)
        refuseIf(levels.list().any { it.id != id && it.name.equals(name, ignoreCase = true) }, "DUPLICATE_NAME") {
            "Another level has this name."
        }
        if (!levels.rename(id, expectedVersion, name, description)) throw ConcurrentModificationException("level $id", expectedVersion)
        val after = get(id)
        audit.record("authz.classification.updated", OBJECT_TYPE, id.toString(), before.audited(), after.audited())
        return after
    }

    /** New order, lowest first; must list every level exactly once. Clearances follow their level. */
    fun reorder(ordered: List<UUID>): List<ClassificationLevel> {
        policy.require(Permission.AUTHZ_CLASSIFICATIONS_MANAGE)
        val before = levels.list()
        require(ordered.size == before.size && ordered.toSet() == before.map { it.id }.toSet()) { "List every level exactly once." }
        levels.rerank(ordered)
        audit.record(
            "authz.classification.reordered",
            OBJECT_TYPE,
            null,
            mapOf("order" to before.map { it.id.toString() }),
            mapOf("order" to ordered.map { it.toString() }),
        )
        invalidation.invalidate(AccessCaches.GRANTS, TenantContext.current())
        return levels.list()
    }

    fun delete(id: UUID) {
        policy.require(Permission.AUTHZ_CLASSIFICATIONS_MANAGE)
        val before = get(id)
        val all = levels.list()
        refuseIf(all.size == 1, "LAST_LEVEL") { "An organisation needs at least one level." }
        refuseIf(levels.clearedTo(id) > 0, "IN_USE") { "Some people are cleared to this level." }
        levels.delete(id)
        levels.rerank(all.filter { it.id != id }.map { it.id })
        audit.record("authz.classification.deleted", OBJECT_TYPE, id.toString(), before.audited(), null)
        invalidation.invalidate(AccessCaches.GRANTS, TenantContext.current())
    }

    fun clearanceOf(user: UUID): ClassificationLevel {
        users.of(user) ?: throw AuthzNotFoundException("user")
        val id = levels.clearanceOf(user)
        return levels.list().let { all -> all.firstOrNull { it.id == id } ?: all.first() }
    }

    fun setClearance(
        user: UUID,
        levelId: UUID,
    ): ClassificationLevel {
        policy.require(Permission.AUTHZ_CLASSIFICATIONS_MANAGE)
        val before = clearanceOf(user)
        val level = get(levelId)
        levels.setClearance(user, levelId)
        audit.record(
            "authz.clearance.changed",
            "authz.clearance",
            user.toString(),
            mapOf("levelId" to before.id.toString(), "rank" to before.rank),
            mapOf("levelId" to level.id.toString(), "rank" to level.rank),
        )
        invalidation.invalidate(AccessCaches.GRANTS, TenantContext.current(), user.toString())
        return level
    }

    private fun get(id: UUID): ClassificationLevel = levels.list().firstOrNull { it.id == id } ?: throw AuthzNotFoundException("level")

    private fun ClassificationLevel.audited() = mapOf("rank" to rank, "name" to name, "description" to description)

    companion object {
        const val OBJECT_TYPE = "authz.classification"
        private const val MAX_LEVELS = 20
    }
}
