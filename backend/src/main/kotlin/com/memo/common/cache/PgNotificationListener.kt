package com.memo.common.cache

import io.micrometer.core.instrument.MeterRegistry
import org.postgresql.PGConnection
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger

/**
 * One dedicated PostgreSQL connection per node that `LISTEN`s on the cache channel (ADR-0020). It is not from the pool
 * (pooled connections are shared; a listening one must stay put) and, behind PgBouncer, must point directly at PostgreSQL
 * (`memo.db.listen-url`). Every (re)connect flushes all caches, because notifications sent while disconnected are lost.
 */
@Component
class PgNotificationListener(
    private val caches: TenantCaches,
    meters: MeterRegistry,
    @param:Value("\${memo.db.listen-url:\${spring.datasource.url}}") private val url: String,
    @param:Value("\${spring.datasource.username}") private val username: String,
    @param:Value("\${spring.datasource.password}") private val password: String,
    @param:Value("\${memo.cache.listener.enabled:true}") private val enabled: Boolean,
    @param:Value("\${memo.cache.listener.name:memo-cache-listener}") private val applicationName: String,
) : SmartLifecycle {
    private val log = LoggerFactory.getLogger(javaClass)
    private val connected = AtomicInteger(0).also { meters.gauge("memo.cache.listener.connected", it) }

    @Volatile private var running = false

    @Volatile private var thread: Thread? = null

    @Volatile private var connection: Connection? = null

    private val connectCount = AtomicInteger(0)

    /** Number of successful (re)connects; each one flushed all caches. */
    val connects: Int get() = connectCount.get()

    val isConnected: Boolean get() = connected.get() == 1

    private val recent = ArrayDeque<String>()

    /** Whether this node has processed a notification with [payload] recently (diagnostics; tests sync on it). */
    internal fun hasProcessed(payload: String): Boolean = synchronized(recent) { payload in recent }

    override fun start() {
        if (!enabled || running) return
        running = true
        thread = Thread.ofVirtual().name(applicationName).start { loop() }
    }

    override fun stop() {
        running = false
        runCatching { connection?.close() }
        thread?.join(STOP_WAIT.toMillis())
    }

    override fun isRunning(): Boolean = running

    private fun loop() {
        var backoff = MIN_BACKOFF
        while (running) {
            try {
                connect().use { c ->
                    backoff = MIN_BACKOFF
                    listen(c)
                }
            } catch (e: java.sql.SQLException) {
                if (running) log.warn("Cache listener connection lost ({}); reconnecting in {}", e.message, backoff)
            } finally {
                connected.set(0)
                connection = null
            }
            if (running) {
                Thread.sleep(backoff)
                backoff = minOf(backoff.multipliedBy(2), MAX_BACKOFF)
            }
        }
    }

    private fun connect(): Connection {
        val props =
            Properties().apply {
                setProperty("user", username)
                setProperty("password", password)
                setProperty("ApplicationName", applicationName)
            }
        val c = DriverManager.getConnection(url, props)
        c.createStatement().use { it.execute("LISTEN ${CacheInvalidation.CHANNEL}") }
        connection = c
        // Anything sent while we were not listening is lost: start from empty caches, then report connected.
        caches.evictAll()
        connectCount.incrementAndGet()
        connected.set(1)
        return c
    }

    private fun listen(c: Connection) {
        val pg = c.unwrap(PGConnection::class.java)
        while (running) {
            pg.getNotifications(POLL_MILLIS)?.forEach { n ->
                CacheMessage.decode(n.parameter)?.let { caches.evict(it.cache, it.tenant, it.key) }
                synchronized(recent) {
                    recent.addLast(n.parameter)
                    if (recent.size > RECENT) recent.removeFirst()
                }
            }
        }
    }

    private companion object {
        const val POLL_MILLIS = 1_000
        const val RECENT = 256
        val MIN_BACKOFF: Duration = Duration.ofSeconds(1)
        val MAX_BACKOFF: Duration = Duration.ofSeconds(30)
        val STOP_WAIT: Duration = Duration.ofSeconds(5)
    }
}
