package com.memo.common.concurrency

/**
 * A guarded update matched zero rows because the aggregate changed since it was read (optimistic locking, AGENTS.md §4).
 * Mapped to HTTP 409/412 by the API layer once mutating endpoints exist.
 */
class ConcurrentModificationException(
    val aggregate: String,
    val expectedVersion: Long,
) : RuntimeException("$aggregate was modified concurrently (expected version $expectedVersion)")
