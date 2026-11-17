package com.memo.search

import com.memo.search.internal.SearchChunker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SearchChunkerTest {
    @Test
    fun `text is cut between words into chunks of at most the word limit`() {
        val text = (1..25).joinToString(" ") { "w$it" }
        val chunks = SearchChunker(maxWords = 10).split(text)
        assertThat(chunks).hasSize(3)
        assertThat(chunks.map { it.split(' ').size }).containsExactly(10, 10, 5)
        assertThat(chunks.joinToString(" ")).isEqualTo(text)
    }

    @Test
    fun `a chunk also stays under the character limit, and a giant word is cut`() {
        val chunks = SearchChunker(maxWords = 100, maxChars = 12).split("abcde fghij klmno ${"x".repeat(30)}")
        assertThat(chunks).allMatch { it.length <= 12 }
        assertThat(chunks.first()).isEqualTo("abcde fghij")
    }

    @Test
    fun `nothing to index gives no chunks, and the chunk count is capped`() {
        assertThat(SearchChunker().split("  \n\t ")).isEmpty()
        assertThat(SearchChunker(maxWords = 1, maxChunks = 3).split("a b c d e")).containsExactly("a", "b", "c")
    }

    @Test
    fun `a smaller chunker halves the limits`() {
        assertThat(SearchChunker(maxWords = 4).smaller().split("a b c d")).containsExactly("a b", "c d")
    }
}
