package com.memo.testsupport

import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.launcher.TestPlan
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Names a stalled suite (Phase 3 E05 log, "T2 in CI: stalls"): when no test or test class has started or finished for
 * [QUIET], it writes every thread's stack and the tests then running to `build/test-stalls/` and says so on stderr. CI
 * uploads that folder when the backend job fails or reaches its time limit. It changes nothing about how tests run
 * (the thread-bound test context rules out JUnit's separate-thread timeouts). Registered through the JUnit launcher's
 * service file; `memo.test.stall-minutes` changes the quiet period.
 */
class StallWatchdog : TestExecutionListener {
    private val running = ConcurrentHashMap<String, Instant>()

    @Volatile private var lastEvent = Instant.now()

    @Volatile private var lastDump = Instant.EPOCH
    private var dumps = 0
    private var timer: ScheduledExecutorService? = null
    private val quiet = Duration.ofMinutes(Integer.getInteger("memo.test.stall-minutes", QUIET_MINUTES).toLong())

    override fun testPlanExecutionStarted(testPlan: TestPlan) {
        timer =
            Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "stall-watchdog").apply { isDaemon = true } }.also {
                it.scheduleWithFixedDelay(::check, CHECK_SECONDS, CHECK_SECONDS, TimeUnit.SECONDS)
            }
    }

    override fun testPlanExecutionFinished(testPlan: TestPlan) {
        timer?.shutdownNow()
    }

    override fun executionStarted(testIdentifier: TestIdentifier) {
        running[testIdentifier.displayPath()] = Instant.now()
        lastEvent = Instant.now()
    }

    override fun executionFinished(
        testIdentifier: TestIdentifier,
        testExecutionResult: TestExecutionResult,
    ) {
        running.remove(testIdentifier.displayPath())
        lastEvent = Instant.now()
    }

    private fun check() {
        val now = Instant.now()
        val stalled = Duration.between(lastEvent, now) >= quiet && Duration.between(lastDump, now) >= quiet
        if (!stalled || dumps >= MAX_DUMPS) return
        lastDump = now
        dumps++
        val text =
            buildString {
                appendLine("No test event for ${Duration.between(lastEvent, now).toSeconds()} s at $now. Running:")
                running.entries.sortedBy { it.value }.forEach { (name, since) -> appendLine("  $name (since $since)") }
                appendLine()
                ManagementFactory.getThreadMXBean().dumpAllThreads(true, true).forEach { append(it.toString()) }
            }
        runCatching {
            val dir = Path.of("build", "test-stalls").also(Files::createDirectories)
            val file = dir.resolve("stall-${now.toEpochMilli()}.txt")
            Files.writeString(file, text)
            System.err.println("StallWatchdog: no test event for ${quiet.toMinutes()} minutes; thread dump in ${file.toAbsolutePath()}")
        }
    }

    /** The unique id (engine, class, method) and the display name. */
    private fun TestIdentifier.displayPath(): String = "$uniqueId ($displayName)"

    private companion object {
        const val QUIET_MINUTES = 6
        const val CHECK_SECONDS = 30L
        const val MAX_DUMPS = 3
    }
}
