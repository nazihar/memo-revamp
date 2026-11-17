package com.memo.imports.internal.sheet

import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantId
import com.memo.testsupport.ImportSheets
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The `imports-parse` pool (design E06 §7, §13; test plan E06 T5): a read past its time is answered as a timeout and its
 * reader stops at the next row; any failure, errors included, is a batch problem and the worker survives it; the queue is
 * bounded; the caller's tenant travels to the worker.
 */
class ImportParsePoolTest {
    private val pool = ImportParsePool(Duration.ofMillis(TIMEOUT_MS))

    @AfterEach
    fun stop() = pool.destroy()

    private fun goodRead(): SheetResult =
        pool.parse { deadline -> XlsxSheetReader(SheetLimits(), deadline).read(ImportSheets.good().inputStream(), {}) }

    @Test
    fun `errors in a read are batch problems, and the worker reads the next source`() {
        val overflow = pool.parse { throw StackOverflowError() }
        val oom = pool.parse { throw OutOfMemoryError("Java heap space") }

        assertThat(overflow.problems.single().code).isEqualTo(SheetProblemCode.MALFORMED)
        assertThat(oom.problems.single().code).isEqualTo(SheetProblemCode.MALFORMED)
        assertThat(goodRead().problems).isEmpty()
    }

    @Test
    fun `a read past its time is answered at once as a timeout, and its reader stops`() {
        val stopped = CountDownLatch(1)
        val result =
            pool.parse { deadline ->
                while (!deadline.passed()) Thread.onSpinWait()
                stopped.countDown()
                SheetResult(emptyList(), emptyMap())
            }

        assertThat(result.problems.single().code).isEqualTo(SheetProblemCode.PARSE_TIMEOUT)
        assertThat(stopped.await(WAIT_S, TimeUnit.SECONDS)).isTrue()
        assertThat(goodRead().problems).isEmpty()
    }

    @Test
    fun `the queue is bounded`() {
        val release = CountDownLatch(1)
        val callers = Executors.newFixedThreadPool(QUEUED + 1)
        try {
            val started = CountDownLatch(1)
            callers.submit {
                pool.parse {
                    started.countDown()
                    release.await(WAIT_S, TimeUnit.SECONDS)
                    SheetResult(emptyList(), emptyMap())
                }
            }
            started.await(WAIT_S, TimeUnit.SECONDS)
            repeat(QUEUED) { callers.submit { pool.parse { SheetResult(emptyList(), emptyMap()) } } }
            // Give the queued callers time to submit before the next one finds the queue full.
            Thread.sleep(SETTLE_MS)
            assertThatThrownBy { pool.parse { SheetResult(emptyList(), emptyMap()) } }.isInstanceOf(ParsePoolBusyException::class.java)
        } finally {
            release.countDown()
            callers.shutdown()
            callers.awaitTermination(WAIT_S, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `the caller's tenant travels to the worker`() {
        val tenant = TenantId(UUID.randomUUID())
        var seen: TenantId? = null
        TenantContext.call(tenant) {
            pool.parse {
                seen = TenantContext.currentOrNull()
                SheetResult(emptyList(), emptyMap())
            }
        }
        assertThat(seen).isEqualTo(tenant)
    }

    private companion object {
        const val TIMEOUT_MS = 1_500L
        const val WAIT_S = 10L
        const val QUEUED = 4
        const val SETTLE_MS = 300L
    }
}
