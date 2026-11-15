package com.memo.search

import com.memo.search.internal.SearchReferences
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SearchReferencesTest {
    @Test
    fun `every spelling of a number has one normal form`() {
        listOf("M-2026-00042", "m-2026-00042", "M/2026/00042", "M 2026 00042", " M--2026..00042 ").forEach {
            assertThat(SearchReferences.norm(it)).describedAs(it).isEqualTo("M-2026-00042")
        }
        assertThat(SearchReferences.norm("CR/2026/0001")).isEqualTo("CR-2026-0001")
    }

    @Test
    fun `the tail is the last digit group without leading zeros`() {
        assertThat(SearchReferences.tail("M-2026-00042")).isEqualTo("42")
        assertThat(SearchReferences.tail("CR/2026/0001")).isEqualTo("1")
        assertThat(SearchReferences.tail("FY2026-27/000")).isEqualTo("0")
        assertThat(SearchReferences.tail("GENERAL")).isNull()
    }

    @Test
    fun `a query is looked up as a number only when it looks like one`() {
        assertThat(SearchReferences.keys("M-2026-00042")).isEqualTo("M-2026-00042" to null)
        assertThat(SearchReferences.keys("cr/2026/0001")).isEqualTo("CR-2026-0001" to null)
        assertThat(SearchReferences.keys("00042")).isEqualTo("00042" to "42")
        assertThat(SearchReferences.keys("rent schedule")).isNull()
        assertThat(SearchReferences.keys("rent, 2026!")).isNull()
        assertThat(SearchReferences.keys("")).isNull()
        assertThat(SearchReferences.keys("1".repeat(101))).isNull()
    }
}
