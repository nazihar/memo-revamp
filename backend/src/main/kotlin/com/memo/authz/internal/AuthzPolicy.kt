package com.memo.authz.internal

import com.memo.common.authz.AccessCaches
import com.memo.common.authz.OrgTree
import com.memo.common.authz.Permission
import com.memo.common.authz.Policy
import com.memo.common.authz.Resource
import com.memo.common.authz.ScopedPermission
import com.memo.common.cache.TenantCache
import com.memo.common.cache.TenantCaches
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import org.jooq.Condition
import org.jooq.Field
import org.jooq.impl.DSL
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * The policy service (ADR-0027): role permissions, unit scope and clearance for the signed-in user of the current unit
 * of work. Effective grants are cached per user (ADR-0020) and evicted on every grant or role change; the org tree comes
 * from the org module's [OrgTree], itself cached.
 */
@Service
class AuthzPolicy(
    caches: TenantCaches,
    private val transactions: TenantTransactions,
    private val roles: RoleStore,
    private val orgTree: ObjectProvider<OrgTree>,
) : Policy {
    private val cache: TenantCache<UUID, UserAccess> = caches.register(AccessCaches.GRANTS)

    override fun allows(
        permission: Permission,
        resource: Resource,
    ): Boolean {
        val access = currentAccess() ?: return false
        val unit =
            when (resource) {
                Resource.Tenant -> null
                is Resource.Unit -> resource.id
                is Resource.Classified -> resource.unit
            }
        val cleared = resource !is Resource.Classified || access.clearanceRank >= resource.rank
        return cleared && access.grants.any { it.permission == permission && covers(it.scopeUnit, unit) }
    }

    override fun holdsAnywhere(permission: Permission): Boolean = currentAccess()?.grants?.any { it.permission == permission } == true

    override fun condition(
        permission: Permission,
        unit: Field<UUID?>?,
        rank: Field<Int?>?,
    ): Condition {
        val access = currentAccess()
        val grants = access?.grants?.filter { it.permission == permission }.orEmpty()
        val unitCondition =
            when {
                grants.isEmpty() -> DSL.falseCondition()
                grants.any { it.scopeUnit == null } -> DSL.trueCondition()
                unit == null -> DSL.falseCondition()
                else -> scopedUnits(grants).let { if (it.isEmpty()) DSL.falseCondition() else unit.`in`(it) }
            }
        // An unclassified row (null rank) is readable at any clearance.
        val rankCondition = rank?.let { it.isNull.or(it.le(access?.clearanceRank ?: 0)) }
        return if (rankCondition == null || grants.isEmpty()) unitCondition else unitCondition.and(rankCondition)
    }

    override fun grants(): List<ScopedPermission> = currentAccess()?.grants.orEmpty()

    override fun clearanceRank(): Int = currentAccess()?.clearanceRank ?: 0

    override fun isPrivileged(user: UUID): Boolean = accessOf(user).grants.any { it.permission.privileged }

    /** Deny by default: only a signed-in user inside a tenant has access. */
    private fun currentAccess(): UserAccess? {
        val user = (ActorContext.currentOrNull()?.actor as? Actor.User)?.userId
        return if (user == null || TenantContext.currentOrNull() == null) null else accessOf(user)
    }

    private fun accessOf(user: UUID): UserAccess {
        val tenant = TenantContext.current()
        return cache.get(user) { transactions.readInTenant(tenant) { roles.accessOf(user) } }
    }

    private fun covers(
        scope: UUID?,
        unit: UUID?,
    ): Boolean = scope == null || (unit != null && orgTree.ifAvailable?.isWithin(unit, scope) == true)

    private fun scopedUnits(grants: List<ScopedPermission>): Set<UUID> {
        val tree = orgTree.ifAvailable ?: return emptySet()
        return grants.mapNotNull { it.scopeUnit }.flatMap { tree.subtree(it) }.toSet()
    }
}
