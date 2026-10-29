package com.memo.authz.internal

import com.memo.common.authz.AccessAdministration
import com.memo.common.authz.BuiltInRole
import com.memo.common.context.ActorContext
import com.memo.common.tenant.TenantId
import com.memo.common.tenant.TenantTransactions
import com.memo.identity.LegacyTenantAdminFlag
import com.memo.tenancy.TenantCreated
import com.memo.tenancy.TenantRegistry
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.event.EventListener
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * Gives every tenant its built-in roles and default classification levels (design E04 §10), and carries E03's
 * `tenant_admin` flag over into role grants (expand → migrate → contract). Idempotent.
 */
@Component
class AuthzBootstrap(
    private val roles: RoleStore,
    private val levels: ClassificationStore,
    private val legacyFlag: LegacyTenantAdminFlag,
    private val grants: AccessAdministration,
    private val transactions: TenantTransactions,
) {
    /** For a new tenant, in its creating transaction. */
    @EventListener
    fun onTenantCreated(event: TenantCreated) = transactions.inTenant(TenantId(event.tenantId)) { ensure() }

    /** Inside a tenant transaction. */
    fun ensure() {
        roles.ensureBuiltInRoles()
        levels.ensureSeeded()
        legacyFlag.flagged().forEach { grants.grantBuiltIn(it.value, BuiltInRole.TENANT_ADMIN) }
    }
}

/** Brings tenants created before this release up to date at start-up, before the demo seeder runs. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class AuthzBootstrapOnStartup(
    private val bootstrap: AuthzBootstrap,
    private val registry: TenantRegistry,
    private val transactions: TenantTransactions,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        ActorContext.asSystem("authz-bootstrap") {
            registry.listActiveIds().forEach { tenant -> transactions.inTenant(tenant) { bootstrap.ensure() } }
        }
    }
}
