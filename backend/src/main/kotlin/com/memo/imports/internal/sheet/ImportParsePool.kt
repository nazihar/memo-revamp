package com.memo.imports.internal.sheet

import com.memo.common.context.ContextSnapshot
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** The parse pool is full: the caller tries again later (the dry run's job retries). */
class ParsePoolBusyException : RuntimeException("the import parse pool is busy")

/**
 * The `imports-parse` pool (design E06 §7, §13): [THREADS] thread per node with a queue of [QUEUE], so an admin's
 * workbook never takes more than one core from live users. Each read gets [timeout] from when it starts; then its
 * deadline passes, the reader stops at the next row (`PARSE_TIMEOUT`), and the caller is answered at once. Any failure,
 * errors included, is a batch problem: the worker thread survives every input.
 */
@Component
class ImportParsePool(
    @param:Value("\${memo.imports.parse-timeout:PT120S}") private val timeout: Duration = DEFAULT_TIMEOUT,
) : DisposableBean {
    private val pool =
        ThreadPoolExecutor(
            THREADS,
            THREADS,
            0,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(QUEUE),
            object : ThreadFactory {
                private val count = AtomicInteger()

                override fun newThread(r: Runnable) = Thread(r, "imports-parse-${count.incrementAndGet()}").apply { isDaemon = true }
            },
        )

    /**
     * Runs [read] on the pool with the caller's tenant, actor and log context (a sink may write staging rows), and waits
     * for it. Throws [ParsePoolBusyException] when the queue is full.
     */
    fun parse(read: (ReadDeadline) -> SheetResult): SheetResult {
        val expired = AtomicBoolean(false)
        val started = CountDownLatch(1)
        val deadline = ReadDeadline { expired.get() || Thread.currentThread().isInterrupted }
        val snapshot = ContextSnapshot.capture()
        val task =
            try {
                pool.submit(
                    Callable {
                        started.countDown()
                        snapshot.call { SheetReads.guarded { read(deadline) } }
                    },
                )
            } catch (_: RejectedExecutionException) {
                throw ParsePoolBusyException()
            }
        return try {
            started.await()
            task.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            expired.set(true)
            task.cancel(true)
            SheetResult.failed(SheetProblem(SheetProblemCode.PARSE_TIMEOUT, params = mapOf("seconds" to timeout.toSeconds())))
        } catch (failure: ExecutionException) {
            SheetResult.failed(SheetReads.problemOf(failure.cause ?: failure))
        }
    }

    override fun destroy() {
        pool.shutdownNow()
    }

    private companion object {
        const val THREADS = 1
        const val QUEUE = 4
        val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(120)
    }
}
