package com.memo.identity.mfa

import com.memo.audit.AuditLog
import com.memo.common.crypto.DataKeys
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantId
import com.memo.identity.UserAccount
import com.memo.identity.UserId
import com.memo.jooq.tables.records.IdentityMfaRecord
import com.memo.jooq.tables.references.IDENTITY_MFA
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** What the user needs to add memo to an authenticator app; the secret also as text for manual entry. */
data class Enrolment(
    val secret: String,
    val otpauthUri: String,
    val qrSvg: String,
)

/**
 * TOTP enrolment and verification (ADR-0024). Every method runs inside `TenantTransactions.inTenant { }`. Secrets are
 * encrypted with [DataKeys], bound to tenant and user; recovery codes live in [RecoveryCodeStore]; every change is
 * audited (never with a secret, code or hash).
 */
@Service
class MfaService(
    private val tenantDsl: TenantDsl,
    private val keys: DataKeys,
    private val recoveryCodes: RecoveryCodeStore,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    fun isEnrolled(id: UserId): Boolean =
        tenantDsl.current().fetchExists(IDENTITY_MFA, IDENTITY_MFA.USER_ID.eq(id.value).and(IDENTITY_MFA.SECRET_ENC.isNotNull))

    /** Starts (or restarts) an enrolment with a fresh secret; an active secret keeps working until [confirm]. */
    fun start(
        user: UserAccount,
        issuer: String,
    ): Enrolment {
        val secret = Totp.newSecret()
        val sealed = keys.encrypt(secret, aad(user.id))
        val now = now()
        tenantDsl
            .current()
            .insertInto(IDENTITY_MFA)
            .set(IDENTITY_MFA.USER_ID, user.id.value)
            .set(IDENTITY_MFA.TENANT_ID, tenantDsl.tenant().value)
            .set(IDENTITY_MFA.PENDING_SECRET_ENC, sealed)
            .set(IDENTITY_MFA.CREATED_AT, now)
            .set(IDENTITY_MFA.UPDATED_AT, now)
            .onConflict(IDENTITY_MFA.USER_ID)
            .doUpdate()
            .set(IDENTITY_MFA.PENDING_SECRET_ENC, sealed)
            .set(IDENTITY_MFA.UPDATED_AT, now)
            .execute()
        val uri = Totp.uri(secret, issuer, user.email)
        return Enrolment(Totp.base32(secret), uri, QrSvg.of(uri))
    }

    /** Confirms the pending enrolment with a code from it; returns the new recovery codes (shown once), or null. */
    fun confirm(
        user: UserAccount,
        code: String,
    ): List<String>? {
        val row = row(user.id)
        val pending = row?.pendingSecretEnc
        val step = pending?.let { Totp.verify(keys.decrypt(it, aad(user.id)), code.trim(), clock.instant(), row.lastStep ?: 0) }
        if (pending == null || step == null) return null
        val now = now()
        tenantDsl
            .current()
            .update(IDENTITY_MFA)
            .set(IDENTITY_MFA.SECRET_ENC, pending)
            .setNull(IDENTITY_MFA.PENDING_SECRET_ENC)
            .set(IDENTITY_MFA.LAST_STEP, step)
            .set(IDENTITY_MFA.ENROLLED_AT, now)
            .set(IDENTITY_MFA.UPDATED_AT, now)
            .where(IDENTITY_MFA.USER_ID.eq(user.id.value))
            .execute()
        audit.record("identity.user.mfa_enrolled", "identity.user", user.id.toString(), after = mapOf("method" to "TOTP"))
        return recoveryCodes.replace(user.id)
    }

    /** A TOTP code (each step usable once) or an unused recovery code. */
    fun verify(
        user: UserAccount,
        code: String,
    ): Boolean = if (RecoveryCodes.looksLikeRecoveryCode(code)) recoveryCodes.use(user.id, code) else verifyTotp(user.id, code.trim())

    /** New recovery codes; the old ones stop working. */
    fun regenerateRecoveryCodes(id: UserId): List<String> {
        val codes = recoveryCodes.replace(id)
        audit.record("identity.user.recovery_codes_regenerated", "identity.user", id.toString())
        return codes
    }

    /** Removes MFA and recovery codes (admin action); the user enrols again at the next sign-in. */
    fun reset(id: UserId) {
        recoveryCodes.deleteAll(id)
        val removed =
            tenantDsl
                .current()
                .deleteFrom(IDENTITY_MFA)
                .where(IDENTITY_MFA.USER_ID.eq(id.value))
                .execute()
        if (removed > 0) audit.record("identity.user.mfa_reset", "identity.user", id.toString())
    }

    private fun verifyTotp(
        id: UserId,
        code: String,
    ): Boolean {
        val row = row(id)
        val secret = row?.secretEnc?.let { keys.decrypt(it, aad(id)) }
        val step = secret?.let { Totp.verify(it, code, clock.instant(), row.lastStep ?: 0) }
        // Guarded: of two concurrent requests with the same code, only one advances the step.
        return step != null &&
            tenantDsl
                .current()
                .update(IDENTITY_MFA)
                .set(IDENTITY_MFA.LAST_STEP, step)
                .where(IDENTITY_MFA.USER_ID.eq(id.value))
                .and(IDENTITY_MFA.LAST_STEP.lt(step))
                .execute() == 1
    }

    private fun row(id: UserId): IdentityMfaRecord? =
        tenantDsl
            .current()
            .selectFrom(IDENTITY_MFA)
            .where(IDENTITY_MFA.USER_ID.eq(id.value))
            .fetchOne()

    private fun aad(id: UserId) = mfaAad(tenantDsl.tenant(), id)

    private fun now() = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
}

/** Associated data of a TOTP secret's ciphertext: it decrypts only on its own tenant and user (DataKeys). */
internal fun mfaAad(
    tenant: TenantId,
    id: UserId,
) = "identity.mfa:$tenant:$id"
