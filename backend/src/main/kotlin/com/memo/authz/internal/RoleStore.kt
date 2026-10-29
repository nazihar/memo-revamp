package com.memo.authz.internal

import com.memo.common.authz.BuiltInRole
import com.memo.common.authz.Permission
import com.memo.common.authz.ScopedPermission
import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.references.AUTHZ_CLASSIFICATION_LEVEL
import com.memo.jooq.tables.references.AUTHZ_CLEARANCE
import com.memo.jooq.tables.references.AUTHZ_GRANT
import com.memo.jooq.tables.references.AUTHZ_ROLE
import com.memo.jooq.tables.references.AUTHZ_ROLE_PERMISSION
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** What a user may do, as the policy evaluates it (ADR-0027); cached per user. */
data class UserAccess(
    val grants: List<ScopedPermission>,
    val clearanceRank: Int,
)

/** Roles and grants (ADR-0027). Every method runs inside a tenant transaction. */
@Repository
class RoleStore(
    private val tenantDsl: TenantDsl,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    /** Inserts the built-in roles that are missing (idempotent, safe on several nodes at once). */
    fun ensureBuiltInRoles() {
        val now = now()
        BuiltInRole.entries.forEach { role ->
            tenantDsl
                .current()
                .insertInto(AUTHZ_ROLE)
                .set(AUTHZ_ROLE.ID, ids.next())
                .set(AUTHZ_ROLE.TENANT_ID, tenantDsl.tenant().value)
                .set(AUTHZ_ROLE.KEY, role.key)
                .set(AUTHZ_ROLE.NAME, role.displayName)
                .set(AUTHZ_ROLE.CREATED_AT, now)
                .set(AUTHZ_ROLE.UPDATED_AT, now)
                .onConflict(AUTHZ_ROLE.TENANT_ID, AUTHZ_ROLE.KEY)
                .doNothing()
                .execute()
        }
    }

    fun builtInRoleId(role: BuiltInRole): UUID? =
        tenantDsl
            .current()
            .select(AUTHZ_ROLE.ID)
            .from(AUTHZ_ROLE)
            .where(AUTHZ_ROLE.KEY.eq(role.key))
            .fetchOne(AUTHZ_ROLE.ID)

    /** Locks the role row until commit, serialising checks such as "is this the last tenant admin". */
    fun lockRole(roleId: UUID) {
        tenantDsl
            .current()
            .select(AUTHZ_ROLE.ID)
            .from(AUTHZ_ROLE)
            .where(AUTHZ_ROLE.ID.eq(roleId))
            .forUpdate()
            .fetch()
    }

    /** Inserts the grant; returns its id, or null when the user already holds the role with this scope. */
    fun insertGrant(
        user: UUID,
        roleId: UUID,
        scopeUnit: UUID?,
    ): UUID? {
        val id = ids.next()
        val inserted =
            tenantDsl
                .current()
                .insertInto(AUTHZ_GRANT)
                .set(AUTHZ_GRANT.ID, id)
                .set(AUTHZ_GRANT.TENANT_ID, tenantDsl.tenant().value)
                .set(AUTHZ_GRANT.USER_ID, user)
                .set(AUTHZ_GRANT.ROLE_ID, roleId)
                .set(AUTHZ_GRANT.SCOPE_UNIT_ID, scopeUnit)
                .set(AUTHZ_GRANT.GRANTED_AT, now())
                .onConflictDoNothing()
                .execute()
        return if (inserted == 1) id else null
    }

    /** Users (of [among], when given) holding [roleId] tenant-wide. */
    fun tenantWideHolders(
        roleId: UUID,
        among: Collection<UUID>? = null,
    ): Set<UUID> {
        if (among != null && among.isEmpty()) return emptySet()
        var condition = AUTHZ_GRANT.ROLE_ID.eq(roleId).and(AUTHZ_GRANT.SCOPE_UNIT_ID.isNull)
        if (among != null) condition = condition.and(AUTHZ_GRANT.USER_ID.`in`(among))
        return tenantDsl
            .current()
            .select(AUTHZ_GRANT.USER_ID)
            .from(AUTHZ_GRANT)
            .where(condition)
            .fetchSet(AUTHZ_GRANT.USER_ID)
            .filterNotNull()
            .toSet()
    }

    /** The user's effective permissions with scopes, plus the implicit [BuiltInRole.USER], and their clearance. */
    fun accessOf(user: UUID): UserAccess {
        val dsl = tenantDsl.current()
        val grants =
            dsl
                .select(AUTHZ_GRANT.ROLE_ID, AUTHZ_ROLE.KEY, AUTHZ_GRANT.SCOPE_UNIT_ID)
                .from(AUTHZ_GRANT)
                .join(AUTHZ_ROLE)
                .on(AUTHZ_ROLE.TENANT_ID.eq(AUTHZ_GRANT.TENANT_ID), AUTHZ_ROLE.ID.eq(AUTHZ_GRANT.ROLE_ID))
                .where(AUTHZ_GRANT.USER_ID.eq(user))
                .fetch()
        val customRoles = grants.filter { it.value2() == null }.map { it.value1()!! }.toSet()
        val customPermissions: Map<UUID, List<Permission>> =
            if (customRoles.isEmpty()) {
                emptyMap()
            } else {
                dsl
                    .select(AUTHZ_ROLE_PERMISSION.ROLE_ID, AUTHZ_ROLE_PERMISSION.PERMISSION)
                    .from(AUTHZ_ROLE_PERMISSION)
                    .where(AUTHZ_ROLE_PERMISSION.ROLE_ID.`in`(customRoles))
                    .fetch()
                    // Keys no longer in the catalogue grant nothing.
                    .mapNotNull { row -> Permission.ofKey(row.value2()!!)?.let { row.value1()!! to it } }
                    .groupBy({ it.first }, { it.second })
            }
        val scoped =
            grants.flatMap { row ->
                val permissions =
                    row.value2()?.let { BuiltInRole.ofKey(it)?.permissions } ?: customPermissions[row.value1()].orEmpty()
                // A scoped grant covers only scopable permissions (the API refuses others; this guards old rows).
                permissions
                    .filter { row.value3() == null || it.scopable }
                    .map { ScopedPermission(it, row.value3()) }
            }
        val implicit = BuiltInRole.USER.permissions.map { ScopedPermission(it, null) }
        return UserAccess((implicit + scoped).distinct(), clearanceRankOf(user))
    }

    private fun clearanceRankOf(user: UUID): Int =
        tenantDsl
            .current()
            .select(AUTHZ_CLASSIFICATION_LEVEL.RANK)
            .from(AUTHZ_CLEARANCE)
            .join(AUTHZ_CLASSIFICATION_LEVEL)
            .on(
                AUTHZ_CLASSIFICATION_LEVEL.TENANT_ID.eq(AUTHZ_CLEARANCE.TENANT_ID),
                AUTHZ_CLASSIFICATION_LEVEL.ID.eq(AUTHZ_CLEARANCE.LEVEL_ID),
            ).where(AUTHZ_CLEARANCE.USER_ID.eq(user))
            .fetchOne(AUTHZ_CLASSIFICATION_LEVEL.RANK) ?: 0

    private fun now() = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
}
