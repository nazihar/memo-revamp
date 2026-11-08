package com.memo.identity.operator

import com.memo.audit.AuditLog
import com.memo.common.id.IdGenerator
import com.memo.common.tenant.PlatformTransactions
import com.memo.identity.PasswordRejectedException
import com.memo.identity.UserAccounts
import com.memo.identity.password.PasswordPolicy
import com.memo.jooq.tables.records.IdentityOperatorRecord
import com.memo.jooq.tables.references.IDENTITY_HTTP_SESSION
import com.memo.jooq.tables.references.IDENTITY_OPERATOR
import org.jooq.DSLContext
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

enum class OperatorStatus { INVITED, ACTIVE, DISABLED }

/** A platform operator (ADR-0031). Not a tenant user: no tenant, no roles, TOTP always required. */
data class Operator(
    val id: UUID,
    val email: String,
    val displayName: String,
    val status: OperatorStatus,
    val hasPassword: Boolean,
    val enrolled: Boolean,
    val lockedUntil: Instant?,
)

/**
 * Operator accounts (ADR-0031): enrolment link, password, lockout. TOTP lives in [OperatorMfa]. Every method runs in
 * platform scope, the only scope whose RLS policy shows the table; changes are recorded in the platform audit chain
 * (never with a password, secret, code or link).
 */
@Service
class OperatorAccounts(
    private val platform: PlatformTransactions,
    private val dsl: DSLContext,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val encoder: PasswordEncoder,
    private val policy: PasswordPolicy,
    private val audit: AuditLog,
) {
    private val random = SecureRandom()

    /**
     * Creates the operator, or resets an existing one to INVITED (password and TOTP cleared, sessions refused at the next
     * request), and returns a one-time enrolment token valid for [ENROLMENT_LIFETIME].
     */
    fun invite(
        email: String,
        displayName: String,
    ): String =
        platform.write {
            val normalised = UserAccounts.normaliseEmail(email)
            require(displayName.isNotBlank() && displayName.length <= MAX_NAME) { "invalid display name" }
            val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(TOKEN_BYTES).also { random.nextBytes(it) })
            val now = now()
            val id =
                dsl
                    .insertInto(IDENTITY_OPERATOR)
                    .set(IDENTITY_OPERATOR.ID, ids.next())
                    .set(IDENTITY_OPERATOR.EMAIL, normalised)
                    .set(IDENTITY_OPERATOR.DISPLAY_NAME, displayName.trim())
                    .set(IDENTITY_OPERATOR.STATUS, OperatorStatus.INVITED.name)
                    .set(IDENTITY_OPERATOR.ENROLMENT_TOKEN_HASH, hash(token))
                    .set(IDENTITY_OPERATOR.ENROLMENT_EXPIRES_AT, now.plus(ENROLMENT_LIFETIME))
                    .set(IDENTITY_OPERATOR.CREATED_AT, now)
                    .set(IDENTITY_OPERATOR.UPDATED_AT, now)
                    .onConflict(IDENTITY_OPERATOR.EMAIL)
                    .doUpdate()
                    .set(IDENTITY_OPERATOR.DISPLAY_NAME, displayName.trim())
                    .set(IDENTITY_OPERATOR.STATUS, OperatorStatus.INVITED.name)
                    .setNull(IDENTITY_OPERATOR.PASSWORD_HASH)
                    .setNull(IDENTITY_OPERATOR.TOTP_SECRET_ENC)
                    .setNull(IDENTITY_OPERATOR.TOTP_PENDING_ENC)
                    .set(IDENTITY_OPERATOR.ENROLMENT_TOKEN_HASH, hash(token))
                    .set(IDENTITY_OPERATOR.ENROLMENT_EXPIRES_AT, now.plus(ENROLMENT_LIFETIME))
                    .set(IDENTITY_OPERATOR.FAILED_LOGINS, 0)
                    .setNull(IDENTITY_OPERATOR.LOCKED_UNTIL)
                    .set(IDENTITY_OPERATOR.VERSION, IDENTITY_OPERATOR.VERSION.plus(1))
                    .set(IDENTITY_OPERATOR.UPDATED_AT, now)
                    .returning(IDENTITY_OPERATOR.ID)
                    .fetchSingle(IDENTITY_OPERATOR.ID)!!
            // A reset ends every session at once (the session store is a platform table; Spring Session's index name).
            dsl.deleteFrom(IDENTITY_HTTP_SESSION).where(IDENTITY_HTTP_SESSION.PRINCIPAL_NAME.eq("platform:$id")).execute()
            audit.record("platform.operator.invited", OBJECT_TYPE, id.toString(), after = mapOf("status" to OperatorStatus.INVITED.name))
            token
        }

    fun find(id: UUID): Operator? = platform.read { record(id)?.toOperator() }

    fun findByEmail(email: String): Operator? =
        platform.read {
            dsl
                .selectFrom(IDENTITY_OPERATOR)
                .where(IDENTITY_OPERATOR.EMAIL.eq(UserAccounts.normaliseEmail(email)))
                .fetchOne()
                ?.toOperator()
        }

    /** The INVITED operator a valid, unexpired enrolment token belongs to, without using it up. */
    fun byEnrolmentToken(token: String): Operator? = platform.read { enrolling(token)?.toOperator() }

    /** Sets the first password and activates the operator; uses the token up. Null for an unknown or expired token. */
    fun acceptEnrolment(
        token: String,
        password: String,
    ): Operator? =
        platform.write {
            val row = enrolling(token) ?: return@write null
            val violations = policy.violations(password, row.email, row.displayName, MIN_PASSWORD)
            if (violations.isNotEmpty()) throw PasswordRejectedException(violations)
            val updated =
                dsl
                    .update(IDENTITY_OPERATOR)
                    .set(IDENTITY_OPERATOR.PASSWORD_HASH, encoder.encode(PasswordPolicy.normalise(password)))
                    .set(IDENTITY_OPERATOR.STATUS, OperatorStatus.ACTIVE.name)
                    .setNull(IDENTITY_OPERATOR.ENROLMENT_TOKEN_HASH)
                    .setNull(IDENTITY_OPERATOR.ENROLMENT_EXPIRES_AT)
                    .set(IDENTITY_OPERATOR.VERSION, IDENTITY_OPERATOR.VERSION.plus(1))
                    .set(IDENTITY_OPERATOR.UPDATED_AT, now())
                    .where(IDENTITY_OPERATOR.ID.eq(row.id))
                    .and(IDENTITY_OPERATOR.ENROLMENT_TOKEN_HASH.eq(hash(token)))
                    .returning()
                    .fetchOne() ?: return@write null
            audit.record(
                "platform.operator.activated",
                OBJECT_TYPE,
                row.id.toString(),
                mapOf("status" to OperatorStatus.INVITED.name),
                mapOf("status" to OperatorStatus.ACTIVE.name),
            )
            updated.toOperator()
        }

    fun passwordMatches(
        id: UUID,
        password: String,
    ): Boolean =
        platform.read {
            val hash = record(id)?.passwordHash
            hash != null && encoder.matches(PasswordPolicy.normalise(password), hash)
        }

    /**
     * Records a password attempt: a success clears failures and stamps the sign-in; a failure counts, and at [LOCK_AFTER]
     * in a row locks the operator for [LOCK_FOR].
     */
    fun recordAttempt(
        id: UUID,
        succeeded: Boolean,
    ) = platform.write {
        if (succeeded) {
            dsl
                .update(IDENTITY_OPERATOR)
                .set(IDENTITY_OPERATOR.FAILED_LOGINS, 0)
                .setNull(IDENTITY_OPERATOR.LOCKED_UNTIL)
                .set(IDENTITY_OPERATOR.LAST_LOGIN_AT, now())
                .where(IDENTITY_OPERATOR.ID.eq(id))
                .execute()
            return@write
        }
        val failures =
            dsl
                .update(IDENTITY_OPERATOR)
                .set(IDENTITY_OPERATOR.FAILED_LOGINS, IDENTITY_OPERATOR.FAILED_LOGINS.plus(1))
                .where(IDENTITY_OPERATOR.ID.eq(id))
                .returning(IDENTITY_OPERATOR.FAILED_LOGINS)
                .fetchOne(IDENTITY_OPERATOR.FAILED_LOGINS) ?: 0
        if (failures >= LOCK_AFTER) {
            dsl
                .update(IDENTITY_OPERATOR)
                .set(IDENTITY_OPERATOR.LOCKED_UNTIL, now().plus(LOCK_FOR))
                .set(IDENTITY_OPERATOR.FAILED_LOGINS, 0)
                .where(IDENTITY_OPERATOR.ID.eq(id))
                .execute()
            audit.record("platform.operator.locked", OBJECT_TYPE, id.toString(), after = mapOf("minutes" to LOCK_FOR.toMinutes()))
        }
    }

    private fun enrolling(token: String): IdentityOperatorRecord? =
        dsl
            .selectFrom(IDENTITY_OPERATOR)
            .where(IDENTITY_OPERATOR.ENROLMENT_TOKEN_HASH.eq(hash(token)))
            .and(IDENTITY_OPERATOR.ENROLMENT_EXPIRES_AT.gt(now()))
            .and(IDENTITY_OPERATOR.STATUS.eq(OperatorStatus.INVITED.name))
            .fetchOne()

    private fun record(id: UUID): IdentityOperatorRecord? = dsl.selectFrom(IDENTITY_OPERATOR).where(IDENTITY_OPERATOR.ID.eq(id)).fetchOne()

    private fun now() = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    companion object {
        const val OBJECT_TYPE = "platform.operator"
        val ENROLMENT_LIFETIME: Duration = Duration.ofHours(24)
        const val LOCK_AFTER = 5
        val LOCK_FOR: Duration = Duration.ofMinutes(30)

        /** Operators hold the keys to every tenant's registry entry: a longer minimum than the tenant default. */
        const val MIN_PASSWORD = 15
        private const val TOKEN_BYTES = 32
        private const val MAX_NAME = 200
    }
}

internal fun IdentityOperatorRecord.toOperator() =
    Operator(
        id = id,
        email = email,
        displayName = displayName,
        status = OperatorStatus.valueOf(status),
        hasPassword = passwordHash != null,
        enrolled = totpSecretEnc != null,
        lockedUntil = lockedUntil?.toInstant(),
    )

private fun hash(token: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
