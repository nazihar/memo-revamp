package com.memo.identity.auth

import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.identity.UserAccount
import com.memo.identity.session.SessionPrincipal
import com.memo.identity.session.SessionRevocation
import com.memo.notification.Mail
import com.memo.notification.MailTemplate
import com.memo.tenancy.TenantRegistry
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * "New sign-in" email (design E03 §6): sent when a returning user signs in from a browser none of their current sessions
 * uses. A first sign-in sends nothing. Call it before the new session starts.
 */
@Component
class SignInAlerts(
    private val sessions: SessionRevocation,
    private val transactions: TenantTransactions,
    private val mail: Mail,
    private val registry: TenantRegistry,
    private val clock: Clock,
) {
    fun onSignIn(
        principal: SessionPrincipal,
        account: UserAccount,
        userAgent: String?,
    ) {
        val knownDevice = sessions.list(principal.indexName).any { it.client?.userAgent == userAgent }
        if (account.lastLoginAt != null && !knownDevice) {
            transactions.inTenant(TenantContext.current()) {
                mail.enqueue(
                    MailTemplate.SIGN_IN_ALERT,
                    account.email,
                    mapOf(
                        "name" to account.displayName,
                        "organisation" to registry.current()?.name.orEmpty(),
                        "device" to (userAgent?.take(MAX_USER_AGENT) ?: "an unknown device"),
                        "time" to TIME.format(clock.instant()),
                    ),
                )
            }
        }
    }

    private companion object {
        const val MAX_USER_AGENT = 200
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC)
    }
}
