package com.memo.identity

import com.memo.audit.AuditLog
import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantDsl
import com.memo.identity.password.PasswordPolicy
import com.memo.identity.password.PasswordViolation
import com.memo.jooq.tables.records.IdentityUserRecord
import com.memo.jooq.tables.references.IDENTITY_USER
import com.memo.tenancy.TenantSettings
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID

@JvmInline
value class UserId(
    val value: UUID,
) {
    override fun toString(): String = value.toString()
}

/** Account lifecycle (design.md §3): INVITED → ACTIVE ⇄ LOCKED; any → DEACTIVATED → ACTIVE. */
enum class UserStatus {
    INVITED,
    ACTIVE,
    LOCKED,
    DEACTIVATED,
    ;

    /** May continue a sign-in already past the password (MFA step, enrolment). */
    val canSignIn: Boolean
        get() = this == ACTIVE
}

/** A local account of one tenant (ADR-0022). Never carries the password hash. */
data class UserAccount(
    val id: UserId,
    val email: String,
    val displayName: String,
    val status: UserStatus,
    val tenantAdmin: Boolean,
    val hasPassword: Boolean,
    val lockedUntil: Instant?,
    val lastLoginAt: Instant?,
    val locale: String?,
    val version: Long,
)

/** The proposed password breaks the policy; [violations] say why (mapped to messages by the client). */
class PasswordRejectedException(
    val violations: List<PasswordViolation>,
) : IllegalArgumentException("Password rejected: $violations")

/** An account with this email already exists in the tenant. */
class DuplicateEmailException : IllegalArgumentException("An account with this email already exists")

/**
 * Local accounts (ADR-0022, ADR-0023). Every method runs inside `TenantTransactions.inTenant { }`; RLS scopes all
 * queries to that tenant, and every change is audited in the same transaction. Hashes never leave this class.
 */
@Service
class UserAccounts(
    private val tenantDsl: TenantDsl,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val audit: AuditLog,
    private val encoder: PasswordEncoder,
    private val policy: PasswordPolicy,
    private val settings: TenantSettings,
) {
    fun find(id: UserId): UserAccount? = record(id)?.toAccount()

    fun findByEmail(email: String): UserAccount? =
        tenantDsl
            .current()
            .selectFrom(IDENTITY_USER)
            .where(IDENTITY_USER.EMAIL.eq(normaliseEmail(email)))
            .fetchOne()
            ?.toAccount()

    /** Creates an account without a password: INVITED until the invitation is accepted (T7) or the seeder sets one. */
    fun create(
        email: String,
        displayName: String,
        tenantAdmin: Boolean = false,
    ): UserAccount {
        val normalised = normaliseEmail(email)
        require(EMAIL.matches(normalised)) { "invalid email" }
        require(displayName.isNotBlank() && displayName.length <= MAX_NAME) { "invalid display name" }
        if (findByEmail(normalised) != null) throw DuplicateEmailException()
        val now = now()
        val created =
            tenantDsl
                .current()
                .insertInto(IDENTITY_USER)
                .set(IDENTITY_USER.ID, ids.next())
                .set(IDENTITY_USER.TENANT_ID, tenantDsl.tenant().value)
                .set(IDENTITY_USER.EMAIL, normalised)
                .set(IDENTITY_USER.DISPLAY_NAME, displayName.trim())
                .set(IDENTITY_USER.STATUS, UserStatus.INVITED.name)
                .set(IDENTITY_USER.TENANT_ADMIN, tenantAdmin)
                .set(IDENTITY_USER.CREATED_AT, now)
                .set(IDENTITY_USER.UPDATED_AT, now)
                .returning()
                .fetchSingle()
                .toAccount()
        audit.record(
            "identity.user.created",
            "identity.user",
            created.id.toString(),
            after = mapOf("email" to created.email, "displayName" to created.displayName, "tenantAdmin" to tenantAdmin),
        )
        return created
    }

    /** Policy violations for [password] as this user's new password (for live feedback; [setPassword] re-checks). */
    fun passwordViolations(
        account: UserAccount,
        password: String,
    ): List<PasswordViolation> = policy.violations(password, account.email, account.displayName, minLength())

    /** Sets the password (activating an INVITED account) after checking the policy. */
    fun setPassword(
        id: UserId,
        password: String,
    ): UserAccount {
        val account = requireNotNull(find(id)) { "unknown user" }
        val violations = passwordViolations(account, password)
        if (violations.isNotEmpty()) throw PasswordRejectedException(violations)
        val status = if (account.status == UserStatus.INVITED) UserStatus.ACTIVE else account.status
        val updated =
            tenantDsl
                .current()
                .update(IDENTITY_USER)
                .set(IDENTITY_USER.PASSWORD_HASH, encoder.encode(PasswordPolicy.normalise(password)))
                .set(IDENTITY_USER.STATUS, status.name)
                .set(IDENTITY_USER.VERSION, IDENTITY_USER.VERSION.plus(1))
                .set(IDENTITY_USER.UPDATED_AT, now())
                .where(IDENTITY_USER.ID.eq(id.value))
                .returning()
                .fetchSingle()
                .toAccount()
        audit.record(
            "identity.user.password.set",
            "identity.user",
            id.toString(),
            before = mapOf("status" to account.status.name),
            after = mapOf("status" to status.name),
        )
        return updated
    }

    /**
     * Checks [password] against the stored hash; re-hashes with the configured default encoding when the stored one is
     * outdated (ADR-0023). Lockout and audit of login attempts belong to the login flow (T3/T4), not here.
     */
    fun passwordMatches(
        id: UserId,
        password: String,
    ): Boolean {
        val hash = record(id)?.passwordHash
        val normalised = PasswordPolicy.normalise(password)
        val matches = hash != null && encoder.matches(normalised, hash)
        if (matches && encoder.upgradeEncoding(hash)) {
            tenantDsl
                .current()
                .update(IDENTITY_USER)
                .set(IDENTITY_USER.PASSWORD_HASH, encoder.encode(normalised))
                .where(IDENTITY_USER.ID.eq(id.value))
                .execute()
        }
        return matches
    }

    private fun minLength(): Int =
        settings
            .get(MIN_LENGTH_SETTING)
            ?.value
            ?.toIntOrNull()
            ?.coerceAtLeast(PasswordPolicy.ABSOLUTE_MIN) ?: PasswordPolicy.DEFAULT_MIN

    private fun record(id: UserId): IdentityUserRecord? =
        tenantDsl
            .current()
            .selectFrom(IDENTITY_USER)
            .where(IDENTITY_USER.ID.eq(id.value))
            .fetchOne()

    private fun now() = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    private fun IdentityUserRecord.toAccount() =
        UserAccount(
            id = UserId(id),
            email = email,
            displayName = displayName,
            status = UserStatus.valueOf(status),
            tenantAdmin = tenantAdmin!!,
            hasPassword = passwordHash != null,
            lockedUntil = lockedUntil?.toInstant(),
            lastLoginAt = lastLoginAt?.toInstant(),
            locale = locale,
            version = version!!,
        )

    companion object {
        /** Tenant setting: minimum password length (default 12, never below 8). */
        const val MIN_LENGTH_SETTING = "security.password.min.length"
        private const val MAX_NAME = 200

        // Deliberately loose: the invitation email proves the address; this only rejects obvious garbage.
        private val EMAIL = Regex("^[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]+$")

        fun normaliseEmail(email: String): String = email.trim().lowercase(Locale.ROOT)
    }
}
