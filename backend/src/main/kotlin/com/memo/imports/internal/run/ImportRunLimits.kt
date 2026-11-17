package com.memo.imports.internal.run

import java.time.Duration

/** The import job's bounds (design E06 §5.3, §13, §15). */
object ImportRunLimits {
    /** Memos rows taken per step of the job (`imports.chunk-rows`). */
    const val CHUNK_ROWS = 50

    /** Pending `memo.legacy` renditions in the tenant at which the job waits (`imports.rendition-backlog`). */
    const val RENDITION_BACKLOG = 200

    /** Attempts a row gets before it is FAILED `IMPORT_ERROR`. */
    const val ATTEMPTS = 3

    /** Retries of one step of the job (infrastructure failures; a row's own failures stay with the row). */
    const val STEP_RETRIES = 10

    /** How long the job waits while the renditions catch up. */
    val THROTTLE_WAIT: Duration = Duration.ofSeconds(30)

    /** How long the job waits while rows are held by another execution (they finish, or turn stale). */
    val HELD_WAIT: Duration = Duration.ofMinutes(1)

    /** A row IMPORTING for longer was held by a job that died: it goes back to READY. */
    val STALE: Duration = Duration.ofMinutes(15)

    /** The window the screen's rate is measured over. */
    val RATE_WINDOW: Duration = Duration.ofMinutes(10)
}
