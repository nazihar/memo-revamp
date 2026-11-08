package com.memo.identity.auth

import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.identity.UserAccount
import com.memo.identity.recipient
import com.memo.identity.session.SessionPrincipal
import com.memo.identity.session.SessionRevocation
import com.memo.notification.NotificationKind
import com.memo.notification.Notifications
import com.memo.tenancy.TenantRegistry
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * "New sign-in" notice (design E03 §6; ADR-0030: in-app and email, mandatory): sent when a returning user signs in
 * from a browser none of their current sessions uses. A first sign-in sends nothing. Call it before the new session
 * starts.
 */
@Component
class SignInAlerts(
    private val sessions: SessionRevocation,
    private val transactions: TenantTransactions,
    private val notifications: Notifications,
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
                notifications.notify(
                    account.recipient(),
                    NotificationKind.SIGN_IN_ALERT,
                    mapOf(
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
