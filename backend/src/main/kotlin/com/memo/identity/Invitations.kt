package com.memo.identity

import com.memo.audit.AuditLog
import com.memo.common.tenant.TenantId
import com.memo.identity.auth.SignIn
import com.memo.identity.auth.SignInResult
import com.memo.identity.tokens.ActionTokens
import com.memo.identity.tokens.TokenPurpose
import com.memo.notification.Mail
import com.memo.notification.MailTemplate
import com.memo.tenancy.TenancyProperties
import com.memo.tenancy.TenantRegistry
import org.springframework.stereotype.Service

/** What an invitee sees before accepting: the organisation and a masked email (the link may have been forwarded). */
data class InvitationDetails(
    val organisation: String,
    val maskedEmail: String,
    val displayName: String,
)

/**
 * Invitations (design E03 §1, §6): an INVITED account plus a one-time link (7 days) by email; accepting sets the
 * password, activates the account and signs the invitee in. Every method runs inside a tenant transaction.
 */
@Service
class Invitations(
    private val accounts: UserAccounts,
    private val tokens: ActionTokens,
    private val mail: Mail,
    private val registry: TenantRegistry,
    private val tenancy: TenancyProperties,
    private val audit: AuditLog,
    private val signIn: SignIn,
) {
    fun invite(
        email: String,
        displayName: String,
        tenantAdmin: Boolean,
        inviterName: String,
    ): UserAccount {
        val account = accounts.create(email, displayName, tenantAdmin)
        audit.record("identity.user.invited", "identity.user", account.id.toString(), after = mapOf("tenantAdmin" to tenantAdmin))
        send(account, inviterName)
        return account
    }

    /** A fresh link for an account that has not accepted yet; earlier links stop working. */
    fun resend(
        id: UserId,
        inviterName: String,
    ): Boolean {
        val account = accounts.find(id)?.takeIf { it.status == UserStatus.INVITED } ?: return false
        send(account, inviterName)
        audit.record("identity.user.invitation_resent", "identity.user", id.toString())
        return true
    }

    fun details(token: String): InvitationDetails? {
        val account = tokens.peek(token, TokenPurpose.INVITATION)?.let { accounts.find(it) }?.takeIf { it.status == UserStatus.INVITED }
        return account?.let { InvitationDetails(registry.current()?.name.orEmpty(), mask(it.email), it.displayName) }
    }

    /**
     * Sets the password (policy-checked before the link is used up), activates the account and signs it in. Null when the
     * link is unknown, used or expired; throws [PasswordRejectedException] for a weak password (the link stays valid).
     */
    fun accept(
        tenant: TenantId,
        token: String,
        password: String,
    ): SignInResult.SignedIn? {
        val pending = tokens.peek(token, TokenPurpose.INVITATION)?.let { accounts.find(it) }?.takeIf { it.status == UserStatus.INVITED }
        pending?.let { accounts.passwordViolations(it, password) }?.takeIf { it.isNotEmpty() }?.let { throw PasswordRejectedException(it) }
        val id = pending?.let { tokens.consume(token, TokenPurpose.INVITATION) }
        val active = id?.let { accounts.setPassword(it, password) }
        active?.let {
            audit.record(
                "identity.user.activated",
                "identity.user",
                it.id.toString(),
                mapOf("status" to "INVITED"),
                mapOf("status" to "ACTIVE"),
            )
        }
        return active?.let { signIn.establish(tenant, it) }
    }

    private fun send(
        account: UserAccount,
        inviterName: String,
    ) {
        val token = tokens.issue(account.id, TokenPurpose.INVITATION)
        val tenant = requireNotNull(registry.current()) { "no tenant" }
        mail.enqueue(
            MailTemplate.INVITATION,
            account.email,
            mapOf(
                "name" to account.displayName,
                "inviter" to inviterName,
                "organisation" to tenant.name,
                // The token travels in the fragment: browsers never send it to servers or proxies (no access-log leaks).
                "link" to "${tenancy.publicUrl(tenant.slug)}/invitation#$token",
            ),
        )
    }

    companion object {
        /** `nadia.karim@bank.example` → `n***@bank.example`. */
        fun mask(email: String): String = email.substringBefore('@').take(1) + "***@" + email.substringAfter('@')
    }
}
