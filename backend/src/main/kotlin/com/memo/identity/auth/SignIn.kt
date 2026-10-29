package com.memo.identity.auth

import com.memo.audit.AuditLog
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantId
import com.memo.common.tenant.TenantTransactions
import com.memo.identity.UserAccount
import com.memo.identity.UserAccounts
import com.memo.identity.UserStatus
import com.memo.identity.mfa.MfaService
import com.memo.identity.session.Factor
import com.memo.identity.session.SessionFactor
import com.memo.identity.session.SessionPrincipal
import com.memo.tenancy.TenantSettings
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.Base64

/** Per-tenant session and MFA settings (ADR-0023, ADR-0024), with their defaults and bounds. */
@Component
class AuthSettings(
    private val settings: TenantSettings,
) {
    fun idleTimeout(): Duration = Duration.ofMinutes(int(IDLE_MINUTES, DEFAULT_IDLE_MINUTES, 1..MAX_IDLE_MINUTES).toLong())

    /** How long a fresh second factor (or password) counts for sensitive actions (ADR-0024). */
    fun stepUpWindow(): Duration = Duration.ofMinutes(int(STEP_UP_MINUTES, DEFAULT_STEP_UP_MINUTES, 1..MAX_STEP_UP_MINUTES).toLong())

    fun absoluteTimeout(): Duration = Duration.ofHours(int(ABSOLUTE_HOURS, DEFAULT_ABSOLUTE_HOURS, 1..MAX_ABSOLUTE_HOURS).toLong())

    /** Tenant admins always need MFA; other users unless the tenant relaxes it (overview Q5). */
    fun mfaRequired(account: UserAccount): Boolean = account.tenantAdmin || settings.get(MFA_REQUIRED)?.value != MFA_ADMINS_ONLY

    private fun int(
        key: String,
        default: Int,
        bounds: IntRange,
    ): Int =
        settings
            .get(key)
            ?.value
            ?.toIntOrNull()
            ?.coerceIn(bounds) ?: default

    companion object {
        const val IDLE_MINUTES = "security.session.idle.minutes"
        const val ABSOLUTE_HOURS = "security.session.absolute.hours"
        const val STEP_UP_MINUTES = "security.stepup.minutes"
        private const val DEFAULT_STEP_UP_MINUTES = 5
        private const val MAX_STEP_UP_MINUTES = 30

        /** `all` (default; any other value counts as `all`) or `admins` (ADR-0024). */
        const val MFA_REQUIRED = "security.mfa.required"
        const val MFA_ADMINS_ONLY = "admins"
        private const val DEFAULT_IDLE_MINUTES = 30
        private const val MAX_IDLE_MINUTES = 480
        private const val DEFAULT_ABSOLUTE_HOURS = 12
        private const val MAX_ABSOLUTE_HOURS = 24
    }
}

/** What the client must do next (ADR-0023). */
enum class SessionState { ANONYMOUS, MFA_REQUIRED, MFA_ENROLMENT_REQUIRED, AUTHENTICATED }

sealed interface SignInResult {
    data class SignedIn(
        val principal: SessionPrincipal,
        val account: UserAccount,
        val next: SessionState,
        val idleTimeout: Duration,
    ) : SignInResult

    /** Wrong password, unknown email, or an account that cannot sign in: indistinguishable to the caller. */
    data object Rejected : SignInResult
}

/**
 * Password sign-in (ADR-0023). Unknown accounts still pay for one hash comparison, so timing does not reveal whether an
 * email exists; the outcome is audited either way (failures without the email).
 */
@Service
class SignIn(
    private val transactions: TenantTransactions,
    private val accounts: UserAccounts,
    private val encoder: PasswordEncoder,
    private val settings: AuthSettings,
    private val audit: AuditLog,
    private val clock: Clock,
    private val attempts: LoginAttempts,
    private val mfa: MfaService,
) {
    private val dummyHash: String by lazy {
        requireNotNull(encoder.encode(Base64.getEncoder().encodeToString(ByteArray(DUMMY_BYTES).also { RANDOM.nextBytes(it) })))
    }

    fun withPassword(
        tenant: TenantId,
        email: String,
        password: String,
    ): SignInResult =
        transactions.inTenant(tenant) {
            val account = accounts.findByEmail(email)
            val blocked = account != null && attempts.blocked(account, clock.instant())
            // Unknown, invited, waiting or locked accounts compare against a dummy hash: same cost, always false.
            val matches =
                if (account?.hasPassword == true && !blocked) {
                    accounts.passwordMatches(account.id, password)
                } else {
                    encoder.matches(password, dummyHash)
                    false
                }
            when {
                account == null -> {
                    rejected(null, "bad_credentials")
                }

                blocked -> {
                    rejected(account, "locked")
                }

                // LOCKED with an expired lock may sign in again (the lock lifts on success).
                account.status != UserStatus.ACTIVE && account.status != UserStatus.LOCKED -> {
                    rejected(account, "inactive")
                }

                !matches -> {
                    attempts.failed(account)
                    rejected(account, "bad_credentials")
                }

                else -> {
                    establish(tenant, account)
                }
            }
        }

    /** Starts the session principal for [account] after its password was proven or just set. Inside the tenant transaction. */
    fun establish(
        tenant: TenantId,
        account: UserAccount,
    ): SignInResult.SignedIn {
        val now = clock.instant()
        val mfaRequired = settings.mfaRequired(account)
        val principal =
            SessionPrincipal(
                tenantId = tenant.value,
                userId = account.id.value,
                tenantAdmin = account.tenantAdmin,
                mfaRequired = mfaRequired,
                factors = listOf(SessionFactor(Factor.PASSWORD, now)),
                authenticatedAt = now,
                expiresAt = now.plus(settings.absoluteTimeout()),
            )
        val next =
            when {
                !mfaRequired -> SessionState.AUTHENTICATED
                mfa.isEnrolled(account.id) -> SessionState.MFA_REQUIRED
                else -> SessionState.MFA_ENROLMENT_REQUIRED
            }
        attempts.succeeded(account)
        ActorContext.call(ActorScope(Actor.User(account.id.value), ActorContext.currentOrNull()?.client)) {
            audit.record("identity.session.created", "identity.user", account.id.toString(), after = mapOf("factors" to listOf("PASSWORD")))
        }
        return SignInResult.SignedIn(principal, account, next, settings.idleTimeout())
    }

    private fun rejected(
        account: UserAccount?,
        reason: String,
    ): SignInResult {
        audit.record("identity.login.failed", "identity.user", account?.id?.toString(), after = mapOf("reason" to reason))
        return SignInResult.Rejected
    }

    private companion object {
        const val DUMMY_BYTES = 24
        val RANDOM = SecureRandom()
    }
}
