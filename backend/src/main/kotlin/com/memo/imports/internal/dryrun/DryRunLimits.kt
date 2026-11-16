package com.memo.imports.internal.dryrun

/** The dry run's bounds (design E06 §9, §13, §15). */
object DryRunLimits {
    /** Problems a batch may record; past it the batch stops with `TOO_MANY_PROBLEMS`. */
    const val MAX_PROBLEMS = 250_000

    /** Problems recorded per row; the row's counts still count them all. */
    const val MAX_PER_ROW = 20

    /** Memos rows validated per read-only transaction. */
    const val CHUNK = 500

    /** Attempts of the job before the batch goes back to OPEN with `DRY_RUN_FAILED`. */
    const val ATTEMPTS = 3

    /** The longest text a problem's parameter keeps (values the admin typed, such as a path). */
    const val MAX_PARAM = 300

    fun tooManyProblems(): Finding =
        Finding(ProblemLevel.BATCH, "TOO_MANY_PROBLEMS", ProblemSheet.BATCH, params = mapOf("limit" to MAX_PROBLEMS))
}
