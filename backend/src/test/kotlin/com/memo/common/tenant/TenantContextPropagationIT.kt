package com.memo.common.tenant

import com.memo.common.context.ActorContext
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.TransactionProbe
import com.memo.testsupport.aSetting
import com.memo.testsupport.aTenant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.task.AsyncTaskExecutor
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.TimeUnit

/** ADR-0017: the tenant reaches every transaction, whoever starts it, and nothing runs without one. */
class TenantContextPropagationIT : IntegrationTest() {
    @Autowired lateinit var probe: TransactionProbe

    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var transactionManager: PlatformTransactionManager

    @Autowired lateinit var applicationTaskExecutor: AsyncTaskExecutor

    private val a: TenantId by lazy { registry.aTenant("prop-a") }
    private val b: TenantId by lazy { registry.aTenant("prop-b") }

    @BeforeEach
    fun tenants() {
        transactions.aSetting(settings, a)
        transactions.aSetting(settings, b)
    }

    @Test
    fun `transactional code started in a tenant scope runs for that tenant`() {
        TenantContext.call(a) {
            assertThat(probe.databaseTenant()).isEqualTo(a.toString())
            assertThat(probe.tenantDslTenant()).isEqualTo(a)
            assertThat(probe.tenantRows("tenancy_setting")).isEqualTo(1)
        }
    }

    @Test
    fun `programmatic and nested new transactions get the tenant too`() {
        TenantContext.call(a) {
            val viaTemplate = TransactionTemplate(transactionManager).execute { probe.databaseTenant() }
            assertThat(viaTemplate).isEqualTo(a.toString())
            val (outer, inner) = probe.outerThenInner { probe.databaseTenantInNewTransaction() }
            assertThat(outer).isEqualTo(a.toString())
            assertThat(inner).isEqualTo(a.toString())
            // After the inner transaction ends, the outer one is still bound to the tenant.
            assertThat(probe.tenantDslTenant()).isEqualTo(a)
        }
    }

    @Test
    fun `without a tenant transactions are tenant-less and tenant access fails closed`() {
        assertThat(probe.databaseTenant()).isNull()
        assertThat(probe.tenantRows("tenancy_setting")).isZero()
        assertThatThrownBy { probe.tenantDslTenant() }.isInstanceOf(MissingTenantContextException::class.java)
    }

    @Test
    fun `a tenant scope cannot be switched to another tenant`() {
        TenantContext.call(a) {
            assertThatThrownBy { TenantContext.call(b) { } }.isInstanceOf(IllegalStateException::class.java)
            assertThatThrownBy { transactions.inTenant(b) { } }.isInstanceOf(IllegalStateException::class.java)
        }
    }

    @Test
    fun `a tenant transaction cannot join a transaction started without a tenant`() {
        TransactionTemplate(transactionManager).execute {
            assertThatThrownBy { transactions.inTenant(a) { settings.list() } }.isInstanceOf(IllegalStateException::class.java)
        }
    }

    @Test
    fun `tenant and platform scopes cannot be mixed`() {
        TenantContext.call(a) { assertThatThrownBy { PlatformScope.call { } }.isInstanceOf(IllegalStateException::class.java) }
        PlatformScope.call { assertThatThrownBy { TenantContext.call(a) { } }.isInstanceOf(IllegalStateException::class.java) }
    }

    @Test
    fun `executor workers inherit the submitter's tenant and actor`() {
        val futures =
            (1..20).map { i ->
                val tenant = if (i % 2 == 0) a else b
                TenantContext.call(tenant) {
                    ActorContext.asSystem("submitter-$i") {
                        applicationTaskExecutor.submit<Triple<TenantId, String?, String?>> {
                            Triple(tenant, probe.databaseTenant(), ActorContext.current().actor.id)
                        }
                    }
                }
            }
        futures.forEachIndexed { index, future ->
            val (expected, seen, actor) = future.get(10, TimeUnit.SECONDS)
            assertThat(seen).isEqualTo(expected.toString())
            assertThat(actor).isEqualTo("submitter-${index + 1}")
        }
    }

    @Test
    fun `executor work submitted without a tenant has none`() {
        val seen = applicationTaskExecutor.submit<String?> { probe.databaseTenant() }.get(10, TimeUnit.SECONDS)
        assertThat(seen).isNull()
    }
}
