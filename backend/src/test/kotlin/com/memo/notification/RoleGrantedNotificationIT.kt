package com.memo.notification

import com.memo.authz.internal.GrantAdmin
import com.memo.authz.internal.RoleCatalog
import com.memo.common.authz.BuiltInRole
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.identity.UserAccounts
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.aPassword
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.anActiveUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/** Granting a role tells the person, in-app and by email (ADR-0030; test plan T4). */
class RoleGrantedNotificationIT : IntegrationTest() {
    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var accounts: UserAccounts

    @Autowired lateinit var grants: GrantAdmin

    @Autowired lateinit var roles: RoleCatalog

    @Test
    fun `the user who is given a role gets an item naming the role and an email`() {
        val tenant = registry.aTestTenant(transactions, settings, "granted")
        val admin = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
        val user = accounts.anActiveUser(transactions, tenant, aPassword())

        ActorContext.call(ActorScope(Actor.User(admin.id.value), null)) {
            TenantContext.call(tenant.id) {
                transactions.inTenant(tenant.id) {
                    val auditor = roles.all().single { it.builtIn == BuiltInRole.AUDITOR }
                    grants.grant(user.id.value, auditor.id, null)
                }
            }
        }

        val (kind, params) =
            AppRoleJdbc.inTransaction(tenant.id) { c ->
                c.prepareStatement("select kind, params::text from notification_item where user_id = ?").use { st ->
                    st.setObject(1, user.id.value)
                    st.executeQuery().use {
                        it.next()
                        it.getString(1) to it.getString(2)
                    }
                }
            }
        assertThat(kind).isEqualTo("ROLE_GRANTED")
        assertThat(params).contains("\"role\"")
        val mails =
            AppRoleJdbc.inTransaction(tenant.id) { c ->
                c.prepareStatement("select template from notification_mail_outbox where to_address = ?").use { st ->
                    st.setString(1, user.email)
                    st.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
                }
            }
        assertThat(mails).contains("ROLE_GRANTED")
    }
}
