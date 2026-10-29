package com.memo.identity

import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.StepUpRequiredException
import com.memo.identity.auth.AuthSettings
import com.memo.identity.session.Factor
import com.memo.identity.session.SessionPrincipal
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Step-up for sensitive actions (ADR-0024): call [require] at the start of the action. It passes when the session's
 * strongest factor was proven within the tenant's window (TOTP for users with MFA, the password otherwise); else it
 * throws [StepUpRequiredException] (401 `step-up-required`) and the client confirms through `/auth/step-up`.
 */
@Component
class StepUp(
    private val settings: AuthSettings,
    private val transactions: TenantTransactions,
    private val clock: Clock,
) {
    fun require() {
        val principal = SecurityContextHolder.getContext().authentication?.principal as? SessionPrincipal
        val window = transactions.readInTenant(TenantContext.current()) { settings.stepUpWindow() }
        val proven = principal?.let { it.factor(strongest(it))?.issuedAt }
        if (proven == null || proven.plus(window).isBefore(clock.instant())) throw StepUpRequiredException(window)
    }

    companion object {
        /** The factor a step-up must refresh: TOTP when the session has one, the password otherwise. */
        fun strongest(principal: SessionPrincipal): Factor = if (principal.has(Factor.TOTP)) Factor.TOTP else Factor.PASSWORD
    }
}
