package com.memo.authz

import com.memo.authz.internal.AuthzBootstrap
import com.memo.authz.internal.ClassificationStore
import com.memo.common.authz.BuiltInRole
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantTransactions
import com.memo.identity.LegacyTenantAdminFlag
import com.memo.identity.UserAccounts
import com.memo.jooq.tables.references.AUTHZ_CLASSIFICATION_LEVEL
import com.memo.jooq.tables.references.AUTHZ_GRANT
import com.memo.jooq.tables.references.AUTHZ_ROLE
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.aPassword
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.anActiveUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/** Built-in roles, default levels and the carry-over of E03's tenant admin flag (design E04 §10; test plan T1). */
class AuthzBootstrapIT : IntegrationTest() {
    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var tenantDsl: TenantDsl

    @Autowired lateinit var accounts: UserAccounts

    @Autowired lateinit var bootstrap: AuthzBootstrap

    @Autowired lateinit var legacyFlag: LegacyTenantAdminFlag

    @Test
    fun `a new tenant gets the built-in roles and the default classification levels`() {
        val tenant = registry.aTestTenant(transactions, settings)

        transactions.readInTenant(tenant.id) {
            val dsl = tenantDsl.current()
            assertThat(dsl.fetch(AUTHZ_ROLE).map { it.key }).containsExactlyInAnyOrderElementsOf(BuiltInRole.entries.map { it.key })
            assertThat(
                dsl
                    .selectFrom(AUTHZ_CLASSIFICATION_LEVEL)
                    .orderBy(AUTHZ_CLASSIFICATION_LEVEL.RANK)
                    .fetch()
                    .map { it.name },
            ).containsExactlyElementsOf(ClassificationStore.DEFAULT_LEVELS)
        }
    }

    @Test
    fun `an E03 tenant admin flag becomes a tenant-wide grant, and running again changes nothing`() {
        val tenant = registry.aTestTenant(transactions, settings)
        val legacy = accounts.anActiveUser(transactions, tenant, aPassword())
        transactions.inTenant(tenant.id) { legacyFlag.set(legacy.id, true) }
        assertThat(transactions.readInTenant(tenant.id) { accounts.isTenantAdmin(legacy.id) }).isFalse()

        repeat(2) { transactions.inTenant(tenant.id) { bootstrap.ensure() } }

        transactions.readInTenant(tenant.id) {
            assertThat(accounts.isTenantAdmin(legacy.id)).isTrue()
            assertThat(tenantDsl.current().fetchCount(AUTHZ_GRANT, AUTHZ_GRANT.USER_ID.eq(legacy.id.value))).isEqualTo(1)
            assertThat(tenantDsl.current().fetchCount(AUTHZ_ROLE)).isEqualTo(BuiltInRole.entries.size)
            assertThat(tenantDsl.current().fetchCount(AUTHZ_CLASSIFICATION_LEVEL)).isEqualTo(ClassificationStore.DEFAULT_LEVELS.size)
        }
    }

    @Test
    fun `creating a tenant admin grants the role and still writes the old flag`() {
        val tenant = registry.aTestTenant(transactions, settings)
        val admin = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)

        transactions.readInTenant(tenant.id) {
            assertThat(accounts.isTenantAdmin(admin.id)).isTrue()
            assertThat(legacyFlag.flagged()).containsExactly(admin.id)
        }
    }
}
