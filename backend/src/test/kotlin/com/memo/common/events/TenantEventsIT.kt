package com.memo.common.events

import com.memo.common.tenant.TenantTransactions
import com.memo.system.jobs.OutboxMaintenance
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.SettingChangeRecorder
import com.memo.testsupport.SettingChangeRecorder.Seen
import com.memo.testsupport.aTenant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import java.time.Duration
import java.util.UUID

/** ADR-0017: tenant events reach listeners after commit only, in the event's tenant, and survive listener failures. */
@TestPropertySource(properties = ["memo.outbox.resubmit-min-age=PT0S"])
class TenantEventsIT : IntegrationTest() {
    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var outbox: OutboxMaintenance

    private fun uniqueKey() = "evt.k${UUID.randomUUID().toString().take(8)}"

    private fun seen(key: String) = SettingChangeRecorder.SEEN.filter { it.key == key }

    @Test
    fun `a listener runs after commit in the event's tenant as an event actor`() {
        val a = registry.aTenant("evt-a")
        val key = uniqueKey()
        transactions.inTenant(a) { settings.put(key, "v") }
        await().atMost(Duration.ofSeconds(10)).until { seen(key).isNotEmpty() }
        assertThat(seen(key)).containsExactly(Seen(key, a.toString(), a.toString(), "event:setting-recorder"))
    }

    @Test
    fun `a rolled-back change publishes nothing`() {
        val a = registry.aTenant("evt-rb")
        val key = uniqueKey()
        assertThatThrownBy {
            transactions.inTenant(a) {
                settings.put(key, "v")
                error("roll back")
            }
        }.hasMessage("roll back")
        // A committed marker change in the same tenant proves the listener is live; the rolled-back key never arrives.
        val marker = uniqueKey()
        transactions.inTenant(a) { settings.put(marker, "v") }
        await().atMost(Duration.ofSeconds(10)).until { seen(marker).isNotEmpty() }
        assertThat(seen(key)).isEmpty()
    }

    @Test
    fun `a failed listener is resubmitted once by the outbox job and then succeeds`() {
        val a = registry.aTenant("evt-retry")
        val key = uniqueKey()
        SettingChangeRecorder.FAILING += key
        transactions.inTenant(a) { settings.put(key, "v") }
        // The listener fails (publication marked failed); the job must not run it while it still fails.
        await().pollDelay(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(5)).until { seen(key).isEmpty() }
        SettingChangeRecorder.FAILING -= key
        outbox.resubmitFailed()
        await().atMost(Duration.ofSeconds(10)).until { seen(key).isNotEmpty() }
        assertThat(seen(key)).containsExactly(Seen(key, a.toString(), a.toString(), "event:setting-recorder"))
    }
}
