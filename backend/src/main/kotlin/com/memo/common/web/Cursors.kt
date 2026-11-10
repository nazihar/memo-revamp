package com.memo.common.web

import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Opaque keyset cursors on `(timestamp desc, id)` (AGENTS.md §4: cursor pagination): the position of the last row of a
 * page, Base64url so clients treat it as a token. A malformed cursor is a 400.
 */
object Cursors {
    fun encode(
        at: Instant,
        id: UUID,
    ): String = Base64.getUrlEncoder().withoutPadding().encodeToString("$at|$id".toByteArray())

    fun decode(cursor: String): Pair<Instant, UUID> {
        val parts = runCatching { String(Base64.getUrlDecoder().decode(cursor)) }.getOrNull()?.split('|')
        require(parts != null && parts.size == 2) { "invalid cursor" }
        return runCatching {
            Instant.parse(
                parts[0],
            ) to UUID.fromString(parts[1])
        }.getOrElse { throw IllegalArgumentException("invalid cursor") }
    }
}
