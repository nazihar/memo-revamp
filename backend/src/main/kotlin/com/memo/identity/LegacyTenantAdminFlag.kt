package com.memo.identity

import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.references.IDENTITY_USER
import org.springframework.stereotype.Component

/**
 * E03's `identity_user.tenant_admin` flag, replaced by the tenant admin role grant (ADR-0027) through expand → migrate
 * → contract: still written in step with the grant for one release, read only to carry it over into grants, then
 * dropped. Inside a tenant transaction.
 */
@Component
class LegacyTenantAdminFlag(
    private val tenantDsl: TenantDsl,
) {
    /** Accounts flagged as tenant admins. */
    fun flagged(): List<UserId> =
        tenantDsl
            .current()
            .select(IDENTITY_USER.ID)
            .from(IDENTITY_USER)
            .where(IDENTITY_USER.TENANT_ADMIN.isTrue)
            .fetch { UserId(it.value1()!!) }

    fun set(
        id: UserId,
        tenantAdmin: Boolean,
    ) {
        tenantDsl
            .current()
            .update(IDENTITY_USER)
            .set(IDENTITY_USER.TENANT_ADMIN, tenantAdmin)
            .where(IDENTITY_USER.ID.eq(id.value))
            .execute()
    }
}
