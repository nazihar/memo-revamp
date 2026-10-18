package com.memo.common.jobs

import com.github.kagkarlsson.scheduler.Scheduler
import com.github.kagkarlsson.scheduler.SchedulerClient
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.TestJobs
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource

/** ADR-0018: with two scheduler instances on one database (two nodes), each execution runs exactly once. */
class SchedulerSingleExecutionIT : IntegrationTest() {
    @Autowired lateinit var scheduler: SchedulerClient

    @Autowired
    @Qualifier("countingTask")
    lateinit var countingTask: OneTimeTask<Void>

    @Autowired lateinit var dataSource: DataSource

    @Test
    fun `two nodes run each execution once`() {
        val secondNode =
            Scheduler
                .create(dataSource, countingTask)
                .tableName("system_scheduled_task")
                .threads(4)
                .pollingInterval(Duration.ofMillis(200))
                .schedulerName { "second-node" }
                .build()
        secondNode.start()
        try {
            val instances = (1..20).map { "run-${UUID.randomUUID()}" }
            instances.forEach { scheduler.scheduleIfNotExists(countingTask.schedulableInstance(it)) }
            await().atMost(Duration.ofSeconds(30)).until { instances.all { TestJobs.counter(it).get() >= 1 } }
            // Give both nodes time to (wrongly) pick an execution twice, then check exactly-once.
            await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).until { true }
            assertThat(instances.map { TestJobs.counter(it).get() }).containsOnly(1)
        } finally {
            secondNode.stop()
        }
    }
}
