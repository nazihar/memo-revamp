package com.memo.identity

import com.memo.tenancy.TenantSettings
import org.springframework.stereotype.Component

/**
 * A tenant security setting a tenant admin may change (design E07 §2): its key, default and allowed range. Values out of
 * range are refused when saved and clamped when read, so a hand-edited row can never weaken the floor.
 */
@Suppress("MagicNumber")
enum class SecurityLimit(
    val key: String,
    val default: Int,
    val range: IntRange,
) {
    IDLE_MINUTES("security.session.idle.minutes", 30, 5..480),
    ABSOLUTE_HOURS("security.session.absolute.hours", 12, 1..24),
    STEP_UP_MINUTES("security.stepup.minutes", 5, 1..30),

    /** Step-up before approving a memo (ADR-0036, overview Q5): longer, so a run of approvals needs one confirmation. */
    APPROVAL_STEP_UP_MINUTES("security.stepup.approval.minutes", 30, 5..240),
    PASSWORD_MIN_LENGTH("security.password.min.length", 12, 12..128),
    THROTTLE_AFTER("security.lockout.throttle.after", 5, 3..10),
    LOCK_AFTER("security.lockout.lock.after", 20, 5..50),
    LOCK_MINUTES("security.lockout.lock.minutes", 30, 5..1440),
}

/** Typed reads of the tenant's security settings. Inside a tenant transaction. */
@Component
class SecuritySettings(
    private val settings: TenantSettings,
) {
    fun int(limit: SecurityLimit): Int =
        settings
            .get(limit.key)
            ?.value
            ?.toIntOrNull()
            ?.coerceIn(limit.range) ?: limit.default

    /** `all` (default) or `admins` (ADR-0024; admins always need MFA). */
    fun mfaPolicy(): String = if (settings.get(MFA_REQUIRED)?.value == MFA_ADMINS_ONLY) MFA_ADMINS_ONLY else MFA_ALL

    /**
     * A version of the whole set, for `If-Match`: the sum of the stored rows' versions plus their count, which grows with
     * every change (a change bumps a row's version or adds a row).
     */
    fun version(): Long =
        settings
            .list()
            .filter { row -> row.key == MFA_REQUIRED || SecurityLimit.entries.any { it.key == row.key } }
            .sumOf { it.version + 1 }

    companion object {
        const val MFA_REQUIRED = "security.mfa.required"
        const val MFA_ALL = "all"
        const val MFA_ADMINS_ONLY = "admins"
    }
}
