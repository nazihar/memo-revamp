package com.memo.audit

import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The canonical form of an audit entry and its hash (ADR-0019). The canonical text is produced once, stored, and hashed as
 * stored, so verification never depends on re-serialising columns.
 *
 * Canonical JSON: object keys sorted (by UTF-16 code units, as RFC 8785), no insignificant whitespace, UTF-8, strings
 * escaped minimally, integers only (no floating point), `null` written explicitly, timestamps as UTC with microseconds.
 */
object AuditCanonicalForm {
    const val VERSION = 1
    const val HASH_BYTES = 32

    private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC)

    fun timestamp(instant: Instant): String = TIMESTAMP.format(instant.truncatedTo(ChronoUnit.MICROS))

    fun encode(entry: AuditEntry): String =
        json(
            mapOf(
                "v" to VERSION.toLong(),
                "tenant" to entry.tenantId.toString(),
                "seq" to entry.seq,
                "at" to timestamp(entry.occurredAt),
                "actor" to mapOf("type" to entry.actorType, "id" to entry.actorId, "actingAs" to entry.actingAsId),
                "client" to mapOf("ip" to entry.clientIp, "ua" to entry.clientUserAgent),
                "corr" to entry.correlationId,
                "action" to entry.action,
                "object" to mapOf("type" to entry.objectType, "id" to entry.objectId),
                "before" to entry.before,
                "after" to entry.after,
            ),
        )

    /** `SHA-256(prevHash ‖ canonical UTF-8 bytes)`. */
    fun hash(
        prevHash: ByteArray,
        canonical: String,
    ): ByteArray {
        require(prevHash.size == HASH_BYTES) { "previous hash must be $HASH_BYTES bytes" }
        return MessageDigest.getInstance("SHA-256").run {
            update(prevHash)
            digest(canonical.toByteArray(Charsets.UTF_8))
        }
    }

    /** The `prev_hash` of a chain's first entry. */
    fun genesis(chain: UUID): ByteArray =
        MessageDigest.getInstance("SHA-256").digest("memo-audit-genesis:$chain".toByteArray(Charsets.UTF_8))

    fun json(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    private fun write(
        out: StringBuilder,
        value: Any?,
    ) {
        when (value) {
            null -> out.append("null")

            is String -> string(out, value)

            is Boolean -> out.append(value)

            is Long, is Int, is Short -> out.append(value.toString())

            is Map<*, *> -> obj(out, value)

            is List<*> -> list(out, value)

            else -> throw IllegalArgumentException(
                "audit values must be strings, integers, booleans, maps or lists, not ${value::class.simpleName}",
            )
        }
    }

    private fun obj(
        out: StringBuilder,
        map: Map<*, *>,
    ) {
        out.append('{')
        map.entries
            .map { (k, v) -> (k as? String ?: throw IllegalArgumentException("audit map keys must be strings")) to v }
            .sortedWith { a, b -> a.first.compareTo(b.first) }
            .forEachIndexed { i, (k, v) ->
                if (i > 0) out.append(',')
                string(out, k)
                out.append(':')
                write(out, v)
            }
        out.append('}')
    }

    private fun list(
        out: StringBuilder,
        list: List<*>,
    ) {
        out.append('[')
        list.forEachIndexed { i, v ->
            if (i > 0) out.append(',')
            write(out, v)
        }
        out.append(']')
    }

    private fun string(
        out: StringBuilder,
        s: String,
    ) {
        out.append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
            }
        }
        out.append('"')
    }
}
