package com.memo.identity

import com.memo.audit.AuditLog
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.identity.auth.LoginAttempts
import com.memo.identity.tokens.ActionTokens
import com.memo.identity.tokens.TokenPurpose
import com.memo.notification.Mail
import com.memo.notification.MailTemplate
import com.memo.tenancy.TenancyProperties
import com.memo.tenancy.TenantRegistry
import org.springframework.stereotype.Service

/**
 * Self-service password reset (design E03 §3, §6): a one-time link (30 minutes) to an existing, usable account; the
 * request answers the same whether or not the account exists. Completing it sets the password, lifts a lockout and
 * notifies the user; the caller revokes the sessions. Every method runs inside a tenant transaction.
 */
@Service
class PasswordReset(
    private val accounts: UserAccounts,
    private val tokens: ActionTokens,
    private val attempts: LoginAttempts,
    private val mail: Mail,
    private val registry: TenantRegistry,
    private val tenancy: TenancyProperties,
    private val audit: AuditLog,
) {
    fun request(email: String) {
        val account =
            accounts.findByEmail(email)?.takeIf {
                it.hasPassword &&
                    (it.status == UserStatus.ACTIVE || it.status == UserStatus.LOCKED)
            }
        if (account != null) {
            val token = tokens.issue(account.id, TokenPurpose.PASSWORD_RESET)
            val tenant = requireNotNull(registry.current()) { "no tenant" }
            mail.enqueue(
                MailTemplate.PASSWORD_RESET,
                account.email,
                mapOf(
                    "name" to account.displayName,
                    "organisation" to tenant.name,
                    "link" to "${tenancy.publicUrl(tenant.slug)}/reset-password#$token",
                ),
            )
            // In the tenant chain without the email (design E03 §5).
            audit.record("identity.user.password_reset_requested", "identity.user", account.id.toString())
        }
    }

    /**
     * Returns the account whose password was reset, or null for an unknown, used or expired link. Throws
     * [PasswordRejectedException] for a weak password, before the link is used up.
     */
    fun complete(
        token: String,
        password: String,
    ): UserAccount? {
        val pending = tokens.peek(token, TokenPurpose.PASSWORD_RESET)?.let { accounts.find(it) }
        pending?.let { accounts.passwordViolations(it, password) }?.takeIf { it.isNotEmpty() }?.let { throw PasswordRejectedException(it) }
        val account = pending?.takeIf { tokens.consume(token, TokenPurpose.PASSWORD_RESET) != null }
        account?.let { reset ->
            ActorContext.call(ActorScope(Actor.User(reset.id.value), ActorContext.currentOrNull()?.client)) {
                accounts.setPassword(reset.id, password)
                attempts.clear(reset)
                audit.record("identity.user.password_reset_completed", "identity.user", reset.id.toString())
                notifyPasswordChanged(reset)
            }
        }
        return account
    }

    /** "Your password was changed" (after a reset or a change in My security). */
    fun notifyPasswordChanged(account: UserAccount) {
        mail.enqueue(
            MailTemplate.PASSWORD_CHANGED,
            account.email,
            mapOf(
                "name" to account.displayName,
                "organisation" to registry.current()?.name.orEmpty(),
            ),
        )
    }
}
