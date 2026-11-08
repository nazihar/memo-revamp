package com.memo.notification

import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.identity.UserAccounts
import com.memo.notification.internal.MailAdmin
import com.memo.notification.internal.MailSettingsRequest
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.Audit
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.TestTenant
import com.memo.testsupport.aPassword
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.anActiveUser
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired

/** The tenant's relay settings (ADR-0030; threat model T1, T2, R1): password write-only and encrypted, TLS required. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MailSettingsIT : IntegrationTest() {
    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var accounts: UserAccounts

    @Autowired internal lateinit var admin: MailAdmin

    private fun <T> asAdmin(
        tenant: TestTenant,
        block: () -> T,
    ): T {
        val user = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
        return ActorContext.call(ActorScope(Actor.User(user.id.value), null)) {
            TenantContext.call(tenant.id) { transactions.inTenant(tenant.id, block) }
        }
    }

    private fun request(
        password: String? = null,
        tls: String = "STARTTLS",
    ) = MailSettingsRequest("smtp.bank.example", 587, tls, "memo", password, "board@bank.example", "Bank Board")

    @Test
    fun `the password is stored encrypted, never shown and never audited`() {
        val tenant = registry.aTestTenant(transactions, settings, "relay")
        val secret = "relay-" + aPassword()

        val view = asAdmin(tenant) { admin.save(request(password = secret), null) }

        assertThat(view.passwordSet).isTrue()
        assertThat(view.toString()).doesNotContain(secret)
        val stored =
            AppRoleJdbc.inTransaction(tenant.id) { c ->
                c.prepareStatement("select password_enc from notification_smtp").use { st ->
                    st.executeQuery().use {
                        it.next()
                        it.getBytes(1)
                    }
                }
            }
        assertThat(String(stored, Charsets.ISO_8859_1)).doesNotContain(secret)
        val audit = Audit.entries(tenant.id).single { it.action == "notification.smtp.changed" }
        assertThat(audit.canonical).doesNotContain(secret).contains("\"loginChanged\":true")

        val kept = asAdmin(tenant) { admin.save(request(password = null), view.version) }
        assertThat(kept.passwordSet).isTrue()
    }

    @Test
    fun `an unencrypted relay is refused where the installation does not allow it, and stale versions lose`() {
        val tenant = registry.aTestTenant(transactions, settings, "tls")

        assertThatThrownBy {
            asAdmin(
                tenant,
            ) { admin.save(request(tls = "NONE"), null) }
        }.isInstanceOf(IllegalArgumentException::class.java)

        val first = asAdmin(tenant) { admin.save(request(), null) }
        asAdmin(tenant) { admin.save(request(), first.version) }
        assertThatThrownBy { asAdmin(tenant) { admin.save(request(), first.version) } }
            .isInstanceOf(ConcurrentModificationException::class.java)
    }
}
