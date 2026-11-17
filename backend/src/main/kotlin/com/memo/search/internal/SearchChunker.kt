package com.memo.search.internal

/**
 * Splits extracted text into search chunks (ADR-0054): at most [maxWords] words and [maxChars] characters each, cut
 * between words, so that a vector never reaches PostgreSQL's 1 MB limit and a phrase near the end of a long document is
 * still found. At most [maxChunks] chunks; text beyond them is not searchable.
 */
class SearchChunker(
    private val maxWords: Int = MAX_WORDS,
    private val maxChars: Int = MAX_CHARS,
    private val maxChunks: Int = MAX_CHUNKS,
) {
    fun split(text: String): List<String> {
        val chunks = mutableListOf<String>()
        var start = -1
        var end = 0
        var words = 0
        for (word in WORD.findAll(text)) {
            if (start >= 0 && (words == maxWords || word.range.last + 1 - start > maxChars)) {
                chunks += text.substring(start, end)
                if (chunks.size == maxChunks) return chunks
                start = -1
                words = 0
            }
            if (start < 0) start = word.range.first
            end = word.range.last + 1
            words++
        }
        if (start >= 0) chunks += text.substring(start, end)
        return chunks.map { it.take(maxChars) }
    }

    /** A chunker with half the limits, after PostgreSQL found a chunk too long for a vector. */
    fun smaller(): SearchChunker = SearchChunker(maxOf(1, maxWords / 2), maxOf(1, maxChars / 2), maxChunks * 2)

    companion object {
        const val MAX_WORDS = 2000
        const val MAX_CHARS = 200_000
        const val MAX_CHUNKS = 500
        private val WORD = Regex("\\S+")
    }
}
