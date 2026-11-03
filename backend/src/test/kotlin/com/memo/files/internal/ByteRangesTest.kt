package com.memo.files.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** RFC 9110 §14 as ADR-0029 applies it: single ranges only; malformed or multiple ranges mean the whole file. */
class ByteRangesTest {
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        nullValues = ["null"],
        value = [
            "null|WHOLE",
            "bytes=0-99|0-99",
            "bytes=900-|900-999",
            "bytes=-100|900-999",
            "bytes=-5000|0-999",
            "bytes=990-5000|990-999",
            "bytes=1000-|UNSATISFIABLE",
            "bytes=-0|UNSATISFIABLE",
            "bytes=0-9,20-29|WHOLE",
            "bytes=9-0|WHOLE",
            "bytes=-|WHOLE",
            "items=0-9|WHOLE",
            "bytes= 0-9|WHOLE",
            "bytes=99999999999999999999-|WHOLE",
        ],
    )
    fun `ranges of a 1000-byte file`(
        header: String?,
        expected: String,
    ) {
        val parsed = ByteRanges.parse(header, 1000)
        val shown =
            when (parsed) {
                is RangeRequest.Whole -> "WHOLE"
                is RangeRequest.Unsatisfiable -> "UNSATISFIABLE"
                is RangeRequest.Partial -> "${parsed.range.first}-${parsed.range.last}"
            }
        assertThat(shown).isEqualTo(expected)
    }
}
