package com.memo.identity

import com.memo.audit.AuditLog
import com.memo.common.authz.GrantsChanged
import com.memo.common.authz.Policy
import com.memo.identity.auth.AuthSettings
import com.memo.identity.session.SessionRevocation
import com.memo.tenancy.TenantSettings
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Sessions carry the MFA requirement fixed at sign-in (ADR-0023). Where the tenant requires MFA only for administrators,
 * a user who gains a privileged permission may hold a session without a second factor: they are signed out, and the next
 * sign-in requires MFA (enrolment first if needed). Runs in the granting transaction.
 */
@Component
class PrivilegeChanges(
    private val policy: Policy,
    private val settings: TenantSettings,
    private val sessions: SessionRevocation,
    private val audit: AuditLog,
) {
    @EventListener
    fun onGrantsChanged(event: GrantsChanged) {
        val adminsOnly = settings.get(AuthSettings.MFA_REQUIRED)?.value == AuthSettings.MFA_ADMINS_ONLY
        if (!event.gained || !adminsOnly || !policy.isPrivileged(event.userId)) return
        val revoked = sessions.revokeAll("${event.tenantId}:${event.userId}")
        if (revoked > 0) {
            audit.record(
                "identity.session.revoked",
                "identity.user",
                event.userId.toString(),
                after = mapOf("reason" to "privileged", "sessions" to revoked),
            )
        }
    }
}
