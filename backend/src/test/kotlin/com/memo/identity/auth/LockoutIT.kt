package com.memo.identity.auth

import com.memo.audit.AuditLog
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantTransactions
import com.memo.identity.UserAccount
import com.memo.identity.UserAccounts
import com.memo.identity.UserStatus
import com.memo.identity.mfa.MfaService
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.Audit
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.MutableClock
import com.memo.testsupport.TestTenant
import com.memo.testsupport.aPassword
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.anActiveUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.Duration

/** Throttling and temporary lock (ADR-0023, threat model S1/D1), driven through [SignIn] with a hand-moved clock. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LockoutIT : IntegrationTest() {
    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var accounts: UserAccounts

    @Autowired lateinit var encoder: PasswordEncoder

    @Autowired lateinit var authSettings: AuthSettings

    @Autowired lateinit var audit: AuditLog

    @Autowired lateinit var tenantDsl: TenantDsl

    @Autowired lateinit var mfa: MfaService

    private val password = aPassword()

    private val tenant: TestTenant by lazy { registry.aTestTenant(transactions, settings, "lockout", mfaRequired = false) }

    private val clock = MutableClock()

    private val signIn: SignIn by lazy {
        SignIn(transactions, accounts, encoder, authSettings, audit, clock, LoginAttempts(tenantDsl, clock, audit), mfa)
    }

    private fun attempt(
        user: UserAccount,
        pw: String,
    ) = signIn.withPassword(tenant.id, user.email, pw)

    private fun reload(user: UserAccount) = transactions.inTenant(tenant.id) { accounts.find(user.id)!! }

    @Test
    fun `the wait doubles from one minute after the fifth failure, up to fifteen`() {
        assertThat((5..10).map { LoginAttempts.throttle(it).toMinutes() }).containsExactly(1, 2, 4, 8, 15, 15)
    }

    @Test
    fun `after five failures even the right password waits, without counting, then works`() {
        val user = accounts.anActiveUser(transactions, tenant, password)
        repeat(LoginAttempts.THROTTLE_AFTER) { assertThat(attempt(user, "$password-wrong")).isEqualTo(SignInResult.Rejected) }

        assertThat(attempt(user, password)).isEqualTo(SignInResult.Rejected)
        assertThat(attempt(user, "$password-wrong")).isEqualTo(SignInResult.Rejected)
        clock.advance(Duration.ofMinutes(1).plusSeconds(1))

        assertThat(attempt(user, password)).isInstanceOf(SignInResult.SignedIn::class.java)
        val after = reload(user)
        assertThat(after.lockedUntil).isNull()
        assertThat(after.status).isEqualTo(UserStatus.ACTIVE)
    }

    @Test
    fun `twenty failures lock the account for thirty minutes, then the lock lifts on its own`() {
        val user = accounts.anActiveUser(transactions, tenant, password)
        repeat(LoginAttempts.LOCK_AFTER) {
            assertThat(attempt(user, "$password-wrong")).isEqualTo(SignInResult.Rejected)
            clock.advance(Duration.ofMinutes(16))
        }
        clock.advance(Duration.ofMinutes(-16))

        assertThat(reload(user).status).isEqualTo(UserStatus.LOCKED)
        Audit.assertAudited(tenant.id, "identity.user.locked", user.id.toString(), actorId = "lockout")
        assertThat(attempt(user, password)).isEqualTo(SignInResult.Rejected)

        clock.advance(LoginAttempts.LOCK_FOR)
        assertThat(attempt(user, password)).isInstanceOf(SignInResult.SignedIn::class.java)
        assertThat(reload(user).status).isEqualTo(UserStatus.ACTIVE)
        Audit.assertAudited(tenant.id, "identity.user.unlocked", user.id.toString(), actorId = "lockout")
        val failures = Audit.entries(tenant.id).filter { it.action == "identity.login.failed" && it.objectId == user.id.toString() }
        assertThat(failures.last().canonical).contains("locked")
    }
}
