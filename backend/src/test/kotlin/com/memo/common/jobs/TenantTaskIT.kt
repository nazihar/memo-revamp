package com.memo.common.jobs

import com.github.kagkarlsson.scheduler.SchedulerClient
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask
import com.memo.tenancy.TenantRegistry
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.ProbeObservation
import com.memo.testsupport.ProbePayload
import com.memo.testsupport.TestJobs
import com.memo.testsupport.aTenant
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import java.util.UUID

/** ADR-0018: tenant tasks run in their tenant as `System(<task>)`; tasks for unknown tenants are dropped, not retried. */
class TenantTaskIT : IntegrationTest() {
    @Autowired lateinit var scheduler: SchedulerClient

    @Autowired lateinit var probeTask: OneTimeTask<ProbePayload>

    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var dsl: DSLContext

    private fun schedule(payload: ProbePayload) =
        scheduler.scheduleIfNotExists(probeTask.schedulableInstance(payload.runId.toString(), payload))

    private fun waitFor(runId: UUID): ProbeObservation {
        await().atMost(Duration.ofSeconds(20)).until { TestJobs.OBSERVED.containsKey(runId) }
        return TestJobs.OBSERVED.getValue(runId)
    }

    @Test
    fun `a tenant task runs in its tenant as the task's system actor`() {
        val a = registry.aTenant("job-a")
        val b = registry.aTenant("job-b")
        val runA = ProbePayload(a.value, UUID.randomUUID())
        val runB = ProbePayload(b.value, UUID.randomUUID())
        schedule(runA)
        schedule(runB)
        assertThat(waitFor(runA.runId)).isEqualTo(ProbeObservation(a.toString(), a.toString(), TestJobs.PROBE))
        assertThat(waitFor(runB.runId)).isEqualTo(ProbeObservation(b.toString(), b.toString(), TestJobs.PROBE))
    }

    @Test
    fun `a task for an unknown tenant fails permanently and is not retried`() {
        val run = ProbePayload(UUID.randomUUID(), UUID.randomUUID())
        schedule(run)
        // Any node (test context) may run it; a permanent failure removes the execution instead of rescheduling it.
        await().atMost(Duration.ofSeconds(20)).until {
            dsl.fetchCount(
                dsl.selectFrom("system_scheduled_task").where("task_name = ? and task_instance = ?", TestJobs.PROBE, run.runId.toString()),
            ) == 0
        }
        assertThat(TestJobs.OBSERVED).doesNotContainKey(run.runId)
    }
}
