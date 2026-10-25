package com.memo.identity.auth

import com.memo.audit.AuditLog
import com.memo.common.context.ActorContext
import com.memo.common.tenant.TenantDsl
import com.memo.identity.UserAccount
import com.memo.identity.UserId
import com.memo.identity.UserStatus
import com.memo.jooq.tables.references.IDENTITY_USER
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Per-account brute-force protection (ADR-0023, threat model S1/D1): after [THROTTLE_AFTER] consecutive failures each
 * further attempt waits 1 minute, doubling up to 15; after [LOCK_AFTER] the account is LOCKED for [LOCK_FOR]. Locks
 * expire on their own (evaluated at the next sign-in, no job) or are lifted by an admin. Attempts during a wait are not
 * evaluated and not counted. Runs inside the sign-in's tenant transaction.
 */
@Component
class LoginAttempts(
    private val tenantDsl: TenantDsl,
    private val clock: Clock,
    private val audit: AuditLog,
) {
    /** The account must wait (throttled or locked) at [now]. */
    fun blocked(
        account: UserAccount,
        now: Instant,
    ): Boolean = account.lockedUntil?.isAfter(now) == true

    /** A wrong password: counts it, then throttles or locks. */
    fun failed(account: UserAccount) {
        val now = clock.instant()
        val failures =
            tenantDsl
                .current()
                .update(IDENTITY_USER)
                .set(IDENTITY_USER.FAILED_LOGINS, IDENTITY_USER.FAILED_LOGINS.plus(1))
                .where(IDENTITY_USER.ID.eq(account.id.value))
                .returning(IDENTITY_USER.FAILED_LOGINS)
                .fetchSingle()
                .get(IDENTITY_USER.FAILED_LOGINS) ?: 0
        when {
            failures >= LOCK_AFTER -> lock(account, now)
            failures >= THROTTLE_AFTER -> waitUntil(account.id, now.plus(throttle(failures)))
        }
    }

    /** A successful sign-in: stamps `last_login_at`, clears failures and any expired lock. */
    fun succeeded(account: UserAccount) {
        tenantDsl
            .current()
            .update(IDENTITY_USER)
            .set(IDENTITY_USER.LAST_LOGIN_AT, utc(clock.instant()))
            .set(IDENTITY_USER.FAILED_LOGINS, 0)
            .setNull(IDENTITY_USER.LOCKED_UNTIL)
            .set(IDENTITY_USER.STATUS, UserStatus.ACTIVE.name)
            .where(IDENTITY_USER.ID.eq(account.id.value))
            .execute()
        if (account.status == UserStatus.LOCKED) {
            ActorContext.asSystem("lockout") {
                audit.record(
                    "identity.user.unlocked",
                    "identity.user",
                    account.id.toString(),
                    mapOf("status" to "LOCKED"),
                    mapOf("reason" to "expired"),
                )
            }
        }
    }

    private fun lock(
        account: UserAccount,
        now: Instant,
    ) {
        val locked =
            tenantDsl
                .current()
                .update(IDENTITY_USER)
                .set(IDENTITY_USER.STATUS, UserStatus.LOCKED.name)
                .set(IDENTITY_USER.LOCKED_UNTIL, utc(now.plus(LOCK_FOR)))
                .set(IDENTITY_USER.VERSION, IDENTITY_USER.VERSION.plus(1))
                .where(IDENTITY_USER.ID.eq(account.id.value))
                .and(IDENTITY_USER.STATUS.eq(UserStatus.ACTIVE.name))
                .execute()
        if (locked > 0) {
            ActorContext.asSystem("lockout") {
                audit.record(
                    "identity.user.locked",
                    "identity.user",
                    account.id.toString(),
                    mapOf("status" to "ACTIVE"),
                    mapOf("status" to "LOCKED"),
                )
            }
        }
    }

    private fun waitUntil(
        id: UserId,
        until: Instant,
    ) {
        tenantDsl
            .current()
            .update(IDENTITY_USER)
            .set(IDENTITY_USER.LOCKED_UNTIL, utc(until))
            .where(IDENTITY_USER.ID.eq(id.value))
            .execute()
    }

    private fun utc(instant: Instant) = OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)

    companion object {
        const val THROTTLE_AFTER = 5
        const val LOCK_AFTER = 20
        val LOCK_FOR: Duration = Duration.ofMinutes(30)
        private val FIRST_WAIT: Duration = Duration.ofMinutes(1)
        private val MAX_WAIT: Duration = Duration.ofMinutes(15)

        /** 1 minute after the 5th failure, doubling with each further one, at most 15 minutes. */
        fun throttle(failures: Int): Duration {
            val doublings = (failures - THROTTLE_AFTER).coerceIn(0, MAX_DOUBLINGS)
            return minOf(FIRST_WAIT.multipliedBy(1L shl doublings), MAX_WAIT)
        }

        private const val MAX_DOUBLINGS = 4
    }
}
