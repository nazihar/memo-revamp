package com.memo.files.internal

/** Why an upload was refused (ADR-0029); the `reason` of the Problem Details and of the audit entry. */
internal enum class RejectionReason {
    TOO_LARGE,
    MALWARE,
    TYPE_NOT_ALLOWED,
    TYPE_MISMATCH,
    MALFORMED,
    ENCRYPTED,
    LIMIT_EXCEEDED,
    SCAN_UNAVAILABLE,
}

/** An upload is refused for [reason]; [detail] (ids, types, sizes, signature names; never content) goes to the audit. */
internal class UploadRejected(
    val reason: RejectionReason,
    val detail: Map<String, Any?> = emptyMap(),
    cause: Throwable? = null,
) : RuntimeException(reason.name, cause)

/** Refuses the upload: throws [UploadRejected] with [detail], keeping [cause] for the logs. */
internal fun reject(
    reason: RejectionReason,
    vararg detail: Pair<String, Any?>,
    cause: Throwable? = null,
): Nothing = throw UploadRejected(reason, mapOf(*detail), cause)
