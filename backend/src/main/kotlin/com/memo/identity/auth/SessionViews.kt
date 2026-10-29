package com.memo.identity.auth

import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.identity.UserAccounts
import com.memo.identity.UserId
import com.memo.identity.mfa.MfaService
import com.memo.identity.session.SessionPrincipal
import com.memo.tenancy.TenantRegistry
import org.springframework.stereotype.Component

/** Builds the session state the SPA acts on (`/auth/session` and every sign-in step). */
@Component
class SessionViews(
    private val transactions: TenantTransactions,
    private val registry: TenantRegistry,
    private val accounts: UserAccounts,
    private val mfa: MfaService,
) {
    fun of(principal: SessionPrincipal?): SessionResponse =
        transactions.readInTenant(TenantContext.current()) {
            val organisation = registry.current()?.name.orEmpty()
            val account = principal?.let { accounts.find(UserId(it.userId)) }
            if (principal == null || account == null) {
                SessionResponse(SessionState.ANONYMOUS, organisation)
            } else {
                val state =
                    when {
                        principal.sessionComplete -> SessionState.AUTHENTICATED
                        mfa.isEnrolled(account.id) -> SessionState.MFA_REQUIRED
                        else -> SessionState.MFA_ENROLMENT_REQUIRED
                    }
                SessionResponse(
                    state,
                    organisation,
                    SessionUserResponse(account.id.value, account.email, account.displayName, account.tenantAdmin),
                )
            }
        }
}
