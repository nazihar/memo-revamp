package com.memo.identity.operator

import com.memo.audit.AuditLog
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.PlatformTransactions
import com.memo.identity.auth.SessionState
import com.memo.identity.session.Factor
import com.memo.identity.session.SessionFactor
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import java.util.UUID

/**
 * Operator password sign-in (ADR-0031), with the same defences as tenant sign-in: unknown or blocked accounts still pay
 * for one hash comparison, failures are counted and lock the account, every outcome is in the platform chain.
 */
@Service
class OperatorSignIn(
    private val accounts: OperatorAccounts,
    private val platform: PlatformTransactions,
    private val encoder: PasswordEncoder,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    private val dummyHash: String by lazy {
        requireNotNull(encoder.encode(Base64.getEncoder().encodeToString(ByteArray(DUMMY_BYTES).also { RANDOM.nextBytes(it) })))
    }

    /** A partial principal (password only), or null for wrong, unknown, inactive or locked; indistinguishable to callers. */
    fun withPassword(
        email: String,
        password: String,
    ): OperatorPrincipal? {
        val now = clock.instant()
        // Unknown, invited, disabled and locked operators compare against a dummy hash: same cost, always false.
        val operator =
            accounts.findByEmail(email)?.takeIf {
                it.status == OperatorStatus.ACTIVE && it.hasPassword && it.lockedUntil?.isAfter(now) != true
            }
        val matches =
            if (operator != null) {
                accounts.passwordMatches(operator.id, password)
            } else {
                encoder.matches(password, dummyHash)
                false
            }
        if (operator == null || !matches) {
            operator?.let { accounts.recordAttempt(it.id, succeeded = false) }
            platform.write { audit.record("platform.operator.sign_in_failed", OperatorAccounts.OBJECT_TYPE, "unknown") }
            return null
        }
        accounts.recordAttempt(operator.id, succeeded = true)
        record(operator.id, "platform.operator.signed_in", mapOf("factor" to "PASSWORD"))
        return OperatorPrincipal(
            operator.id,
            listOf(SessionFactor(Factor.PASSWORD, now)),
            now,
            now.plus(OperatorSessionStore.ABSOLUTE_TIMEOUT),
        )
    }

    /** The principal of a just-activated operator (enrolment link used): password proven, TOTP enrolment next. */
    fun afterEnrolment(operator: Operator): OperatorPrincipal {
        val now = clock.instant()
        record(operator.id, "platform.operator.signed_in", mapOf("factor" to "PASSWORD"))
        return OperatorPrincipal(
            operator.id,
            listOf(SessionFactor(Factor.PASSWORD, now)),
            now,
            now.plus(OperatorSessionStore.ABSOLUTE_TIMEOUT),
        )
    }

    /** Adds a fresh TOTP factor (second step, enrolment or step-up). */
    fun withTotp(
        principal: OperatorPrincipal,
        action: String,
    ): OperatorPrincipal {
        record(principal.operatorId, action, mapOf("factor" to "TOTP"))
        return principal.withFactor(Factor.TOTP, clock.instant())
    }

    fun state(
        principal: OperatorPrincipal?,
        operator: Operator?,
    ): SessionState =
        when {
            principal == null || operator == null -> SessionState.ANONYMOUS
            principal.sessionComplete -> SessionState.AUTHENTICATED
            operator.enrolled -> SessionState.MFA_REQUIRED
            else -> SessionState.MFA_ENROLMENT_REQUIRED
        }

    private fun record(
        operatorId: UUID,
        action: String,
        after: Map<String, Any?>,
    ) = ActorContext.call(ActorScope(Actor.PlatformOperator(operatorId), ActorContext.currentOrNull()?.client)) {
        platform.write { audit.record(action, OperatorAccounts.OBJECT_TYPE, operatorId.toString(), after = after) }
    }

    private companion object {
        const val DUMMY_BYTES = 24
        val RANDOM = SecureRandom()
    }
}
