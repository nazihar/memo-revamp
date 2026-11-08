package com.memo.platform

import com.memo.common.authz.AccessAdministration
import com.memo.common.authz.BuiltInRole
import com.memo.common.tenant.TenantId
import com.memo.common.tenant.TenantTransactions
import com.memo.identity.Invitations
import com.memo.identity.UserAccounts
import com.memo.identity.UserId
import com.memo.identity.UserStatus
import com.memo.tenancy.TenantSettings
import org.springframework.stereotype.Service
import java.util.UUID

/** The tenant already has an active administrator: the platform no longer invites anyone into it (ADR-0031). */
class TenantHasAdminException : RuntimeException("the tenant already has an active administrator")

/**
 * The one place where platform code enters a tenant (ADR-0031, ArchUnit): the first administrator's invitation and the
 * defaults chosen at creation. It refuses once the tenant has an active tenant admin and returns nothing from the tenant.
 */
@Service
class TenantProvisioning(
    private val transactions: TenantTransactions,
    private val settings: TenantSettings,
    private val accounts: UserAccounts,
    private val invitations: Invitations,
    private val access: AccessAdministration,
) {
    /** Default language and time zone, set once when the tenant is created. */
    fun defaults(
        tenant: TenantId,
        locale: String,
        timeZone: String,
    ) = transactions.inTenant(tenant) {
        settings.put(LOCALE_SETTING, locale)
        settings.put(TIME_ZONE_SETTING, timeZone)
    }

    /**
     * Invites [email] as tenant admin, or re-sends the invitation if that account is still INVITED. Audited in the
     * tenant's chain by the invitation itself, with the operator as actor.
     */
    fun inviteFirstAdmin(
        tenant: TenantId,
        email: String,
        displayName: String,
    ) = transactions.inTenant(tenant) {
        if (hasActiveAdmin()) throw TenantHasAdminException()
        val existing = accounts.findByEmail(email)
        when {
            existing == null -> {
                invitations.invite(email, displayName, tenantAdmin = true, inviterName = INVITER)
            }

            existing.status == UserStatus.INVITED -> {
                access.grantBuiltIn(existing.id.value, BuiltInRole.TENANT_ADMIN)
                invitations.resend(existing.id, INVITER)
            }

            else -> {
                throw TenantHasAdminException()
            }
        }
    }

    /** Whether the tenant has an ACTIVE tenant admin: metadata only, for the console (read-write: the check locks the role). */
    fun hasAdmin(tenant: TenantId): Boolean = transactions.inTenant(tenant) { hasActiveAdmin() }

    private fun hasActiveAdmin(): Boolean =
        access
            .otherTenantAdminsLocked(except = NOBODY)
            .any { accounts.find(UserId(it))?.status == UserStatus.ACTIVE }

    companion object {
        const val LOCALE_SETTING = "locale.default"
        const val TIME_ZONE_SETTING = "default.timezone"
        private const val INVITER = "memo platform"
        private val NOBODY = UUID(0, 0)
    }
}
