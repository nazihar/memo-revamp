package com.memo.platform

import com.github.kagkarlsson.scheduler.SchedulerClient
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask
import com.memo.common.context.ActorContext
import com.memo.common.tenant.TenantTransactions
import com.memo.identity.UserAccounts
import com.memo.tenancy.TenantLifecycle
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.ProbePayload
import com.memo.testsupport.TestJobs
import com.memo.testsupport.TestTenant
import com.memo.testsupport.aPassword
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.anActiveUser
import com.memo.testsupport.onHost
import com.memo.testsupport.sessionIdOf
import com.memo.testsupport.signIn
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Suspension (ADR-0031; test plan T2, threat model D2): every tenant endpoint refuses the host, sessions end, and the
 * tenant's tasks wait without failing; resuming lets them run.
 */
class SuspendedTenantIT : IntegrationTest() {
    @Autowired lateinit var mvc: MockMvcTester

    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var accounts: UserAccounts

    @Autowired lateinit var lifecycle: TenantLifecycle

    @Autowired lateinit var scheduler: SchedulerClient

    @Autowired lateinit var probeTask: OneTimeTask<ProbePayload>

    @Autowired lateinit var dsl: DSLContext

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    lateinit var mapping: RequestMappingHandlerMapping

    private fun suspend(tenant: TestTenant) =
        ActorContext.asSystem("test") {
            lifecycle.suspend(tenant.id, requireNotNull(registry.summary(tenant.id)).version)
        }

    private fun resume(tenant: TestTenant) =
        ActorContext.asSystem("test") {
            lifecycle.resume(tenant.id, requireNotNull(registry.summary(tenant.id)).version)
        }

    @Test
    fun `every tenant endpoint answers 403 tenant-suspended, and the tenant's sessions are gone`() {
        val tenant = registry.aTestTenant(transactions, settings, "susp", mfaRequired = false)
        val password = aPassword()
        val user = accounts.anActiveUser(transactions, tenant, password)
        val session = mvc.signIn(tenant, user.email, password)

        suspend(tenant)

        val endpoints =
            mapping.handlerMethods.keys.flatMap { info ->
                info.patternValues
                    .filter { it.startsWith("/api/") && !it.startsWith("/api/v1/platform") && !it.startsWith("/api/v1/health") }
                    .flatMap { path -> info.methodsCondition.methods.map { it.name to path } }
            }
        assertThat(endpoints).hasSizeGreaterThan(50)
        endpoints.forEach { (method, path) ->
            val uri = path.replace(Regex("\\{[^}]+}"), UUID.randomUUID().toString())
            val result =
                mvc
                    .method(HttpMethod.valueOf(method))
                    .uri(uri)
                    .with(onHost(tenant.slug))
                    .cookie(session)
                    .exchange()
            assertThat(result).describedAs("$method $path").hasStatus(HttpStatus.FORBIDDEN)
            assertThat(result.response.contentAsString).describedAs("$method $path").contains("tenant-suspended")
        }
        val remaining =
            dsl.fetchCount(dsl.selectFrom("identity_http_session").where("session_id = ?", sessionIdOf(session)))
        assertThat(remaining).isZero()

        resume(tenant)
        val again =
            mvc
                .get()
                .uri("/api/v1/auth/session")
                .with(onHost(tenant.slug))
                .cookie(session)
                .exchange()
        assertThat(again).hasStatus(HttpStatus.OK)
        assertThat(again.response.contentAsString).contains("ANONYMOUS")
    }

    @Test
    fun `a suspended tenant's task waits an hour without counting as a failure, and runs once resumed`() {
        val tenant = registry.aTestTenant(transactions, settings, "susp-job")
        suspend(tenant)
        val run = ProbePayload(tenant.id.value, UUID.randomUUID())
        scheduler.scheduleIfNotExists(probeTask.schedulableInstance(run.runId.toString(), run))

        val instance = run.runId.toString()
        val later = OffsetDateTime.now().plusMinutes(50)
        await().atMost(Duration.ofSeconds(20)).until {
            dsl
                .fetchOptional(
                    "select execution_time, consecutive_failures from system_scheduled_task where task_name = ? and task_instance = ?",
                    TestJobs.PROBE,
                    instance,
                ).map { it.get(0, OffsetDateTime::class.java).isAfter(later) && it.get(1, Int::class.java) == 0 }
                .orElse(false)
        }
        assertThat(TestJobs.OBSERVED).doesNotContainKey(run.runId)

        resume(tenant)
        dsl.execute(
            "update system_scheduled_task set execution_time = now() where task_name = ? and task_instance = ?",
            TestJobs.PROBE,
            instance,
        )
        await().atMost(Duration.ofSeconds(20)).until { TestJobs.OBSERVED.containsKey(run.runId) }
    }
}
