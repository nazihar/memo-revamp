package com.memo.demo

import com.memo.common.context.ActorContext
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantTransactions
import com.memo.jooq.tables.references.WORKFLOW_TASK
import com.memo.jooq.tables.references.WORKFLOW_TIMER
import com.memo.tenancy.TenantRegistry
import com.memo.testsupport.FilesIntegrationTest
import com.memo.testsupport.aPassword
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.DefaultApplicationArguments
import java.util.concurrent.atomic.AtomicReference

/**
 * Synthetic volume V2 (test plan T6), small: memos in review through the real services, spread over the approvers,
 * each decision due with its timers; a second run does nothing. The first run goes through the start-up runner with no
 * actor bound, as in the weekly load stage (its first run crashed there: tenant creation is audited).
 */
class WorkflowVolumeIT : FilesIntegrationTest() {
    @Autowired lateinit var volume: WorkflowVolume

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var tenantDsl: TenantDsl

    @Autowired lateinit var registry: TenantRegistry

    @Test
    fun `memos in review with due times and timers, generated once`() {
        // As at start-up: a thread of its own, so no actor is bound (the test extension binds one on the test thread).
        val failure = AtomicReference<Throwable>()
        val runner = WorkflowVolumeOnStartup(volume, 60, aPassword())
        val startup = Thread { runCatching { runner.run(DefaultApplicationArguments()) }.onFailure(failure::set) }
        startup.start()
        startup.join()
        assertThat(failure.get()).isNull()
        val tenant = checkNotNull(registry.findBySlug(WorkflowVolume.SLUG)).id
        assertThat(volume.generate(instances = 60, password = aPassword())).isNull()

        TenantContext.call(tenant) {
            ActorContext.asSystem("test") {
                transactions.inTenant(tenant) {
                    val dsl = tenantDsl.current()
                    val open = dsl.fetch(WORKFLOW_TASK, WORKFLOW_TASK.STATE.eq("OPEN").and(WORKFLOW_TASK.KIND.eq("DECIDE")))
                    assertThat(open).hasSize(60)
                    assertThat(open).allSatisfy { assertThat(it.dueAt).isNotNull() }
                    assertThat(open.map { it.assigneeId }.toSet()).hasSize(WorkflowVolume.APPROVERS)
                    assertThat(dsl.fetchCount(WORKFLOW_TIMER, WORKFLOW_TIMER.STATE.eq("PENDING"))).isEqualTo(60 * 4)
                }
            }
        }
    }
}
