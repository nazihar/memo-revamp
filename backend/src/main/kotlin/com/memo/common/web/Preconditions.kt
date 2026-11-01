package com.memo.common.web

/** `If-Match: "<version>"` (a weak `W/` prefix is accepted); missing or unreadable is 428 (AGENTS.md §4). */
fun ifMatchVersion(header: String?): Long =
    header
        ?.removePrefix("W/")
        ?.trim('"', ' ')
        ?.toLongOrNull() ?: throw PreconditionRequiredException()

/** The `ETag` of a row at [version], as clients send it back in `If-Match`. */
fun etagOf(version: Long) = "\"$version\""

/**
 * The change is not allowed in the current state (409 `conflict`); [reason] is a stable code for the client (for example
 * `CYCLE`, `NOT_EMPTY`, `OVERLAP`), the message an English fallback.
 */
class RefusedException(
    val reason: String,
    message: String,
) : RuntimeException(message)

/** Throws [RefusedException] with [reason] when [condition] holds. */
inline fun refuseIf(
    condition: Boolean,
    reason: String,
    message: () -> String,
) {
    if (condition) throw RefusedException(reason, message())
}
