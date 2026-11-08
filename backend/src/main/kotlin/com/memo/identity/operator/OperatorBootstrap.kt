package com.memo.identity.operator

import com.memo.common.context.ActorContext
import com.memo.tenancy.PlatformHost
import org.springframework.stereotype.Component

/** The `operator-invite` command's work (ADR-0031): invite or reset, and build the link to the enrolment page. */
@Component
class OperatorBootstrap(
    private val accounts: OperatorAccounts,
    private val platformHost: PlatformHost,
) {
    fun invite(
        email: String,
        displayName: String,
    ): String {
        val url = checkNotNull(platformHost.publicUrl) { "The operator console is switched off (memo.platform)" }
        val token = ActorContext.asSystem("operator-invite") { accounts.invite(email, displayName) }
        // The token travels in the fragment, so it never reaches a server log.
        return "$url/platform/enrol#$token"
    }
}
