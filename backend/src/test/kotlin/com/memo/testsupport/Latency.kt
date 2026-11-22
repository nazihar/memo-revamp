package com.memo.testsupport

/**
 * The latency measure of the performance tests in the normal suite: the 95th percentile of [runs] timed calls after
 * [warmUp] untimed ones, measured in [rounds] rounds, and the best round kept.
 *
 * The suite shares one JVM with every cached test context, and their schedulers (renditions, indexing, mail) keep
 * working in the background; with 20 samples the 95th percentile is the second slowest call, so one collector pause or
 * one burst of another context's jobs was enough to fail a budget (master at 310 ms against 300, #235 at 658 against
 * 600). A real regression is slow in every round, so the best round still fails it; noise in one round no longer does.
 * The weekly `load` workflow measures the budgets themselves at volume.
 */
object Latency {
    const val ROUNDS = 3
    private const val NANOS_PER_MILLI = 1_000_000.0

    /** The best round's 95th percentile, in milliseconds. */
    fun p95Millis(
        warmUp: Int,
        runs: Int,
        rounds: Int = ROUNDS,
        call: () -> Unit,
    ): Double {
        require(runs > 0 && rounds > 0) { "runs and rounds must be positive" }
        repeat(warmUp) { call() }
        return (1..rounds).minOf {
            val samples =
                (1..runs)
                    .map {
                        val started = System.nanoTime()
                        call()
                        (System.nanoTime() - started) / NANOS_PER_MILLI
                    }.sorted()
            samples[(runs * 95 / 100 - 1).coerceIn(0, runs - 1)]
        }
    }

    /** [p95Millis] in whole milliseconds, as most budgets are written. */
    fun p95(
        warmUp: Int,
        runs: Int,
        rounds: Int = ROUNDS,
        call: () -> Unit,
    ): Long = p95Millis(warmUp, runs, rounds, call).toLong()
}
