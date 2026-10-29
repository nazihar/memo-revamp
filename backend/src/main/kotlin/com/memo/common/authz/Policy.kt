package com.memo.common.authz

import org.jooq.Condition
import org.jooq.Field
import java.util.UUID

/** What a permission is checked on (ADR-0027). */
sealed interface Resource {
    /** The tenant as a whole: needs a tenant-wide grant. */
    data object Tenant : Resource

    /** Something that belongs to [id]'s unit: a grant scoped to that unit or one of its ancestors also counts. */
    data class Unit(
        val id: UUID,
    ) : Resource

    /** A classified object in [unit] (null: not tied to a unit); also needs clearance rank ≥ [rank]. */
    data class Classified(
        val unit: UUID?,
        val rank: Int,
    ) : Resource
}

/**
 * The single entry point for authorisation decisions (ADR-0027), for the actor of the current unit of work. Deny by
 * default: anything but a signed-in user of an active tenant holding the permission is denied. Account state is enforced
 * by the session layer (deactivation revokes sessions); grants and the org tree are cached, so checks need no query.
 */
interface Policy {
    fun allows(
        permission: Permission,
        resource: Resource = Resource.Tenant,
    ): Boolean

    /** Throws [ForbiddenException] unless [allows]. */
    fun require(
        permission: Permission,
        resource: Resource = Resource.Tenant,
    ) {
        if (!allows(permission, resource)) throw ForbiddenException(permission)
    }

    /** Whether the actor holds [permission] at all, tenant-wide or for some unit (the gate of scoped endpoints). */
    fun holdsAnywhere(permission: Permission): Boolean

    /**
     * A SQL filter selecting exactly the rows [allows] would allow: [unit] is the row's unit column (null: the rows are not
     * tied to units, so only tenant-wide grants count), [rank] its classification rank column (null: unclassified).
     */
    fun condition(
        permission: Permission,
        unit: Field<UUID?>? = null,
        rank: Field<Int?>? = null,
    ): Condition

    /** The actor's effective permissions with their scopes (null scope: tenant-wide), for `/me/access`. */
    fun grants(): List<ScopedPermission>

    /** The actor's clearance rank (0: the lowest level). */
    fun clearanceRank(): Int

    /** Whether [user] holds any privileged permission: such users always need MFA (ADR-0024). */
    fun isPrivileged(user: UUID): Boolean
}

data class ScopedPermission(
    val permission: Permission,
    /** Unit subtree root; null for tenant-wide. */
    val scopeUnit: UUID?,
)

/** The actor lacks [permission] for the resource (403 `forbidden`). */
class ForbiddenException(
    val permission: Permission?,
) : RuntimeException(if (permission == null) "Forbidden" else "Missing permission ${permission.key}")
