package com.memo.testsupport

import com.memo.common.cache.PgNotificationListener
import org.awaitility.Awaitility.await
import java.time.Duration
import java.util.UUID

/**
 * Waits until [listener] has applied every cache notification committed so far (ADR-0020). Notifications are delivered in
 * commit order, so once a fresh sync notification has been processed, all earlier evictions have been applied as well.
 */
fun awaitCacheNotificationsApplied(listener: PgNotificationListener) {
    val payload = "test.sync\t${UUID.randomUUID()}\t${UUID.randomUUID()}"
    AppRoleJdbc.connect { c ->
        c.prepareStatement("select pg_notify('memo_cache', ?)").use {
            it.setString(1, payload)
            it.execute()
        }
    }
    await().atMost(Duration.ofSeconds(10)).until { listener.hasProcessed(payload) }
}
