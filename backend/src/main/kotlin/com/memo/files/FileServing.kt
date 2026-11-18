package com.memo.files

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.util.UUID

/** What [FileServing] streams of a version: the version itself, or the PDF to read it in (the original when it is a PDF). */
enum class ServedContent { ORIGINAL, PDF }

/** How [FileServing.serve] ended. Nothing is written to the response unless [SERVED]. */
enum class ServeOutcome {
    SERVED,

    /** The PDF to read the version in is not ready (being prepared), or cannot be built: nothing to stream. */
    NOT_READY,

    /** No such version in the tenant, or its content was purged. */
    MISSING,
}

/**
 * The open's audit record in the caller's terms (ADR-0061, design E03 §2): [action] on [objectType] [objectId], with the
 * caller's [details] (for a meeting paper: the paper and the revision); the file, version and content are added.
 */
data class OpenAudit(
    val action: String,
    val objectType: String,
    val objectId: String,
    val details: Map<String, Any?>,
)

/**
 * Streams a pinned file version for a module that has already decided who may read it (ADR-0061): the same headers,
 * ranges and once-per-open audit as the files endpoints, but without the purpose's `mayRead`, which cannot tell which
 * version a caller pinned. Only the meeting module calls it, after its paper rule (`PaperAccessArchTest`).
 */
interface FileServing {
    /**
     * Streams [content] of [versionId] to [response] (ranges, `If-None-Match`, `HEAD`), auditing [audit] at the first open
     * of the version in the session (not per range request). Call it outside any transaction: it opens a short one for
     * the lookup and the audit, then streams.
     */
    fun serve(
        versionId: UUID,
        content: ServedContent,
        request: HttpServletRequest,
        response: HttpServletResponse,
        audit: OpenAudit,
    ): ServeOutcome
}
