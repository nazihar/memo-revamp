package com.memo.files.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class ClamdReplyTest {
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "stream: OK|CLEAN|",
            "stream: Win.Test.EICAR_HDB-1 FOUND|INFECTED|Win.Test.EICAR_HDB-1",
            "stream: Heuristics.Limits.Exceeded.MaxFileSize FOUND|INFECTED|Heuristics.Limits.Exceeded.MaxFileSize",
            "INSTREAM size limit exceeded. ERROR|FAILED|",
            "stream: OK trailing|FAILED|",
            "stream:  FOUND|FAILED|",
            "stream: two words FOUND|FAILED|",
            "OK|FAILED|",
        ],
    )
    fun `only the documented replies count as a verdict`(
        reply: String,
        kind: String,
        signature: String?,
    ) {
        val verdict = ClamdReply.parse(reply)
        when (kind) {
            "CLEAN" -> assertThat(verdict).isEqualTo(ScanVerdict.Clean)
            "INFECTED" -> assertThat(verdict).isEqualTo(ScanVerdict.Infected(signature!!))
            else -> assertThat(verdict).isInstanceOf(ScanVerdict.Failed::class.java)
        }
    }

    @Test
    fun `an empty or binary reply is a failure, and the reason stays printable`() {
        assertThat(ClamdReply.parse("")).isInstanceOf(ScanVerdict.Failed::class.java)
        val verdict = ClamdReply.parse("stream: \u0001ÿ\nx")
        assertThat((verdict as ScanVerdict.Failed).reason).doesNotContain("\u0001", "\n")
    }
}
