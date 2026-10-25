package com.memo.identity

import com.memo.common.tenant.TenantId
import com.memo.common.tenant.TenantTransactions
import com.memo.identity.password.PasswordEncoding
import com.memo.identity.password.PasswordViolation
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.Audit
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.aTenant
import com.memo.testsupport.query
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserAccountsIT : IntegrationTest() {
    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var accounts: UserAccounts

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    private val tenant: TenantId by lazy { registry.aTenant("accounts") }

    private val password = "violet harbour lantern sixteen"

    private fun <T> inTenant(block: () -> T) = transactions.inTenant(tenant, block)

    private fun storedHash(user: UserAccount): String =
        AppRoleJdbc
            .inTransaction(
                tenant,
            ) { c -> c.query("select password_hash from identity_user where id = ?", user.id.value) { it.getString(1) } }
            .single()

    private fun aUser(name: String = "Nadia Karim"): UserAccount = inTenant { accounts.create("${UUID.randomUUID()}@Bank.Example", name) }

    @Test
    fun `a new account is INVITED, without a password, with a normalised email, and audited`() {
        val user = aUser()

        assertThat(user.status).isEqualTo(UserStatus.INVITED)
        assertThat(user.hasPassword).isFalse()
        assertThat(user.email).endsWith("@bank.example")
        assertThat(inTenant { accounts.findByEmail(user.email.uppercase()) }).isEqualTo(user)
        Audit.assertAudited(tenant, "identity.user.created", user.id.toString())
    }

    @Test
    fun `email is unique per tenant, not globally`() {
        val user = aUser()

        assertThatThrownBy { inTenant { accounts.create(user.email.uppercase(), "Someone Else") } }
            .isInstanceOf(DuplicateEmailException::class.java)
        val other = registry.aTenant("accounts-other")
        val sameEmailElsewhere = transactions.inTenant(other) { accounts.create(user.email, "Other Tenant User") }
        assertThat(sameEmailElsewhere.id).isNotEqualTo(user.id)
        assertThat(transactions.inTenant(other) { accounts.find(user.id) }).isNull()
    }

    @Test
    fun `setting a password activates the account and stores only an argon2id hash`() {
        val user = aUser()

        val active = inTenant { accounts.setPassword(user.id, password) }

        assertThat(active.status).isEqualTo(UserStatus.ACTIVE)
        assertThat(active.hasPassword).isTrue()
        assertThat(storedHash(user)).startsWith("{argon2}\$argon2id\$").doesNotContain(password)
        assertThat(inTenant { accounts.passwordMatches(user.id, password) }).isTrue()
        assertThat(inTenant { accounts.passwordMatches(user.id, "violet harbour lantern seventeen") }).isFalse()
        val audited = Audit.assertAudited(tenant, "identity.user.password.set", user.id.toString())
        assertThat(audited.canonical).doesNotContain(password).doesNotContain("argon2")
    }

    @Test
    fun `a password that breaks the policy is rejected with every violation`() {
        val user = aUser("Nadia Karim")

        assertThatThrownBy { inTenant { accounts.setPassword(user.id, "qwerty123456") } }
            .isInstanceOfSatisfying(PasswordRejectedException::class.java) {
                assertThat(it.violations).containsExactly(PasswordViolation.BREACHED)
            }
        assertThatThrownBy { inTenant { accounts.setPassword(user.id, "nadia karim") } }
            .isInstanceOfSatisfying(PasswordRejectedException::class.java) {
                assertThat(it.violations).contains(PasswordViolation.TOO_SHORT, PasswordViolation.CONTAINS_IDENTITY)
            }
        assertThat(inTenant { accounts.find(user.id) }!!.status).isEqualTo(UserStatus.INVITED)
    }

    @Test
    fun `the tenant minimum length applies`() {
        val strict = registry.aTenant("accounts-strict")
        val user =
            transactions.inTenant(strict) {
                settings.put(UserAccounts.MIN_LENGTH_SETTING, "40")
                accounts.create("strict@bank.example", "Strict User")
            }

        assertThat(transactions.inTenant(strict) { accounts.passwordViolations(user, password) })
            .containsExactly(PasswordViolation.TOO_SHORT)
    }

    @Test
    fun `a hash in an outdated encoding is upgraded on a successful match`() {
        val user = inTenant { accounts.setPassword(aUser().id, password) }
        val pbkdf2 = PasswordEncoding().passwordEncoder(PasswordEncoding.PBKDF2).encode(password)!!
        AppRoleJdbc.connect { c ->
            c.autoCommit = false
            c.prepareStatement("select set_config('app.tenant_id', ?, true)").use {
                it.setString(1, tenant.toString())
                it.execute()
            }
            c.prepareStatement("update identity_user set password_hash = ? where id = ?").use {
                it.setString(1, pbkdf2)
                it.setObject(2, user.id.value)
                it.executeUpdate()
            }
            c.commit()
        }

        assertThat(inTenant { accounts.passwordMatches(user.id, password) }).isTrue()

        assertThat(storedHash(user)).startsWith("{argon2}")
    }
}
