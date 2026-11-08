package com.memo.identity.operator

import com.memo.audit.AuditLog
import com.memo.common.crypto.DataKeys
import com.memo.common.tenant.PlatformTransactions
import com.memo.identity.mfa.Enrolment
import com.memo.identity.mfa.QrSvg
import com.memo.identity.mfa.Totp
import com.memo.jooq.tables.references.IDENTITY_OPERATOR
import org.jooq.DSLContext
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Operators' TOTP (ADR-0031), always required. Secrets are encrypted with [DataKeys], bound to the operator; each time
 * step is usable once. Platform scope, like [OperatorAccounts].
 */
@Service
class OperatorMfa(
    private val platform: PlatformTransactions,
    private val dsl: DSLContext,
    private val keys: DataKeys,
    private val audit: AuditLog,
    private val clock: Clock,
    private val accounts: OperatorAccounts,
) {
    /** Starts (or restarts) enrolment with a fresh secret; an active secret keeps working until [confirm]. */
    fun start(operator: Operator): Enrolment =
        platform.write {
            val secret = Totp.newSecret()
            dsl
                .update(IDENTITY_OPERATOR)
                .set(IDENTITY_OPERATOR.TOTP_PENDING_ENC, keys.encrypt(secret, aad(operator.id)))
                .set(IDENTITY_OPERATOR.UPDATED_AT, now())
                .where(IDENTITY_OPERATOR.ID.eq(operator.id))
                .execute()
            val uri = Totp.uri(secret, ISSUER, operator.email)
            Enrolment(Totp.base32(secret), uri, QrSvg.of(uri))
        }

    /** Confirms the pending enrolment with a code from it. */
    fun confirm(
        operator: Operator,
        code: String,
    ): Boolean =
        platform.write {
            val row = dsl.selectFrom(IDENTITY_OPERATOR).where(IDENTITY_OPERATOR.ID.eq(operator.id)).fetchOne()
            val pending = row?.totpPendingEnc
            val step = pending?.let { Totp.verify(keys.decrypt(it, aad(operator.id)), code.trim(), clock.instant(), row.totpLastStep ?: 0) }
            if (pending != null && step != null) {
                dsl
                    .update(IDENTITY_OPERATOR)
                    .set(IDENTITY_OPERATOR.TOTP_SECRET_ENC, pending)
                    .setNull(IDENTITY_OPERATOR.TOTP_PENDING_ENC)
                    .set(IDENTITY_OPERATOR.TOTP_LAST_STEP, step)
                    .set(IDENTITY_OPERATOR.UPDATED_AT, now())
                    .where(IDENTITY_OPERATOR.ID.eq(operator.id))
                    .execute()
                audit.record(
                    "platform.operator.mfa_enrolled",
                    OperatorAccounts.OBJECT_TYPE,
                    operator.id.toString(),
                    after =
                        mapOf("method" to "TOTP"),
                )
            }
            step != null
        }

    /** A TOTP code; guarded so that of two concurrent requests with the same code only one advances the step. */
    fun verify(
        id: UUID,
        code: String,
    ): Boolean =
        platform.write {
            val row = dsl.selectFrom(IDENTITY_OPERATOR).where(IDENTITY_OPERATOR.ID.eq(id)).fetchOne()
            val secret = row?.totpSecretEnc?.let { keys.decrypt(it, aad(id)) }
            val step = secret?.let { Totp.verify(it, code.trim(), clock.instant(), row.totpLastStep ?: 0) }
            step != null &&
                dsl
                    .update(IDENTITY_OPERATOR)
                    .set(IDENTITY_OPERATOR.TOTP_LAST_STEP, step)
                    .where(IDENTITY_OPERATOR.ID.eq(id))
                    .and(IDENTITY_OPERATOR.TOTP_LAST_STEP.lt(step))
                    .execute() == 1
        }

    /** Demo and test seeding: an ACTIVE, enrolled operator with [password] and [base32Secret]. Never under `prod`. */
    fun seed(
        email: String,
        displayName: String,
        password: String,
        base32Secret: String,
    ): Operator =
        platform.write {
            val current = accounts.findByEmail(email)
            if (current?.status != OperatorStatus.ACTIVE || !accounts.passwordMatches(current.id, password)) {
                accounts.acceptEnrolment(accounts.invite(email, displayName), password)
            }
            val id = requireNotNull(accounts.findByEmail(email)).id
            dsl
                .update(IDENTITY_OPERATOR)
                .set(IDENTITY_OPERATOR.TOTP_SECRET_ENC, keys.encrypt(Totp.decodeBase32(base32Secret), aad(id)))
                .where(IDENTITY_OPERATOR.ID.eq(id))
                .execute()
            requireNotNull(accounts.find(id))
        }

    private fun aad(id: UUID) = "identity.operator:$id"

    private fun now() = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    private companion object {
        const val ISSUER = "memo platform"
    }
}
