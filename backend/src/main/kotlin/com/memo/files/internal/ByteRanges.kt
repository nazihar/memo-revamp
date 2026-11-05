package com.memo.files.internal

/** An inclusive byte range `[first, last]` of a representation. */
internal data class ByteRange(
    val first: Long,
    val last: Long,
) {
    val length: Long get() = last - first + 1
}

/** What a `Range` header asks of a representation of a given size (RFC 9110 §14). */
internal sealed interface RangeRequest {
    /** No range, a range we ignore (invalid syntax, several ranges): send the whole representation with 200. */
    data object Whole : RangeRequest

    /** One satisfiable range: 206 with `Content-Range`. */
    data class Partial(
        val range: ByteRange,
    ) : RangeRequest

    /** A syntactically valid range that does not overlap the representation: 416. */
    data object Unsatisfiable : RangeRequest
}

/**
 * Strict parser of the `Range` header for one representation (ADR-0029): only the `bytes` unit and a single range.
 * Several ranges are served as the whole representation, which RFC 9110 allows; anything malformed is ignored.
 */
internal object ByteRanges {
    private val SINGLE = Regex("^bytes=(\\d{0,19})-(\\d{0,19})$")

    fun parse(
        header: String?,
        size: Long,
    ): RangeRequest {
        val match = header?.trim()?.let(SINGLE::matchEntire)
        val first = match?.groupValues?.get(1)?.toLongOrNull()
        val last = match?.groupValues?.get(2)?.toLongOrNull()
        return when {
            match == null || (first == null && last == null) -> RangeRequest.Whole
            first == null -> suffix(last!!, size)
            last != null && last < first -> RangeRequest.Whole
            first >= size -> RangeRequest.Unsatisfiable
            else -> RangeRequest.Partial(ByteRange(first, minOf(last ?: Long.MAX_VALUE, size - 1)))
        }
    }

    /** `bytes=-n`: the last n bytes. */
    private fun suffix(
        length: Long,
        size: Long,
    ): RangeRequest =
        if (length == 0L || size == 0L) {
            RangeRequest.Unsatisfiable
        } else {
            RangeRequest.Partial(ByteRange(maxOf(0, size - length), size - 1))
        }
}
