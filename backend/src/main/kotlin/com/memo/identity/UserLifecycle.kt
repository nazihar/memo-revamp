package com.memo.identity

import com.memo.audit.AuditLog
import com.memo.common.authz.AccessAdministration
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.tenant.TenantDsl
import com.memo.identity.auth.LoginAttempts
import com.memo.identity.mfa.MfaService
import com.memo.identity.tokens.ActionTokens
import com.memo.identity.tokens.TokenPurpose
import com.memo.jooq.tables.references.IDENTITY_USER
import com.memo.notification.Mail
import com.memo.notification.MailTemplate
import com.memo.tenancy.TenantRegistry
import org.jooq.impl.DSL
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** The change is not allowed in the account's current situation (409); [reason] is a stable code for the client. */
class LifecycleRefusedException(
    val reason: String,
    message: String,
) : RuntimeException(message)

/**
 * Admin lifecycle of accounts (design E03 §1). Every change is a guarded update on id, allowed current statuses and the
 * version the admin last saw ([ConcurrentModificationException] otherwise), and is audited. The caller revokes sessions
 * where the change requires it. Every method runs inside a tenant transaction.
 */
@Service
class UserLifecycle(
    private val tenantDsl: TenantDsl,
    private val accounts: UserAccounts,
    private val attempts: LoginAttempts,
    private val tokens: ActionTokens,
    private val mfa: MfaService,
    private val mail: Mail,
    private val registry: TenantRegistry,
    private val audit: AuditLog,
    private val clock: Clock,
    private val access: AccessAdministration,
) {
    /** Not oneself, and never the last active tenant admin (threat model E2). Open links stop working. */
    fun deactivate(
        id: UserId,
        expectedVersion: Long,
        actor: UserId,
    ): UserAccount {
        if (id == actor) throw LifecycleRefusedException("SELF", "You cannot deactivate your own account.")
        val target = requireNotNull(accounts.find(id)) { "unknown user" }
        if (accounts.isTenantAdmin(id) && otherActiveAdmins(id) == 0) {
            throw LifecycleRefusedException("LAST_ADMIN", "The organisation needs at least one active administrator.")
        }
        val updated =
            transition(id, expectedVersion, setOf(UserStatus.INVITED, UserStatus.ACTIVE, UserStatus.LOCKED), UserStatus.DEACTIVATED)
        TokenPurpose.entries.forEach { tokens.retire(id, it) }
        audit.record(
            "identity.user.deactivated",
            "identity.user",
            id.toString(),
            mapOf("status" to target.status.name),
            mapOf("status" to "DEACTIVATED"),
        )
        return updated
    }

    /** Back to ACTIVE, or to INVITED for an account that never set a password. */
    fun reactivate(
        id: UserId,
        expectedVersion: Long,
    ): UserAccount {
        val target = requireNotNull(accounts.find(id)) { "unknown user" }
        val to = if (target.hasPassword) UserStatus.ACTIVE else UserStatus.INVITED
        val updated = transition(id, expectedVersion, setOf(UserStatus.DEACTIVATED), to)
        audit.record(
            "identity.user.reactivated",
            "identity.user",
            id.toString(),
            mapOf("status" to "DEACTIVATED"),
            mapOf("status" to to.name),
        )
        return updated
    }

    fun unlock(
        id: UserId,
        expectedVersion: Long,
    ): UserAccount {
        val updated = transition(id, expectedVersion, setOf(UserStatus.LOCKED), UserStatus.ACTIVE)
        attempts.clear(updated)
        audit.record("identity.user.unlocked", "identity.user", id.toString(), mapOf("status" to "LOCKED"), mapOf("reason" to "admin"))
        return updated
    }

    /** Removes MFA (the user enrols again) and tells the user by email. */
    fun resetMfa(
        id: UserId,
        expectedVersion: Long,
    ): UserAccount {
        val updated = transition(id, expectedVersion, UserStatus.entries.toSet(), null)
        mfa.reset(id)
        mail.enqueue(
            MailTemplate.MFA_RESET,
            updated.email,
            mapOf(
                "name" to updated.displayName,
                "organisation" to registry.current()?.name.orEmpty(),
            ),
        )
        return updated
    }

    /** Bumps the version (and status when [to] is set) if the row still has [expectedVersion] and one of [from]. */
    private fun transition(
        id: UserId,
        expectedVersion: Long,
        from: Set<UserStatus>,
        to: UserStatus?,
    ): UserAccount {
        val updated =
            tenantDsl
                .current()
                .update(IDENTITY_USER)
                // No target status (MFA reset): the status stays, only the version moves.
                .set(IDENTITY_USER.STATUS, to?.let { DSL.value(it.name) } ?: IDENTITY_USER.STATUS)
                .set(IDENTITY_USER.VERSION, IDENTITY_USER.VERSION.plus(1))
                .set(IDENTITY_USER.UPDATED_AT, OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC))
                .where(IDENTITY_USER.ID.eq(id.value))
                .and(IDENTITY_USER.VERSION.eq(expectedVersion))
                .and(IDENTITY_USER.STATUS.`in`(from.map { it.name }))
                .execute()
        if (updated == 0) {
            val current = accounts.find(id)
            if (current != null && current.version == expectedVersion) {
                throw LifecycleRefusedException("STATUS", "Not possible while the account is ${current.status.name.lowercase()}.")
            }
            throw ConcurrentModificationException("user $id", expectedVersion)
        }
        return requireNotNull(accounts.find(id))
    }

    /**
     * Active tenant admins other than [id]. The tenant admin role row stays locked until commit, so two admins cannot
     * deactivate each other (or revoke each other's grant, E04) at once.
     */
    private fun otherActiveAdmins(id: UserId): Int {
        val others = access.otherTenantAdminsLocked(id.value)
        if (others.isEmpty()) return 0
        return tenantDsl
            .current()
            .fetchCount(
                IDENTITY_USER,
                IDENTITY_USER.ID.`in`(others).and(IDENTITY_USER.STATUS.eq(UserStatus.ACTIVE.name)),
            )
    }
}
