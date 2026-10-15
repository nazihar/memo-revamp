package com.memo.common.id

import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.constraints.IntRange
import org.assertj.core.api.Assertions.assertThat

class IdGeneratorProperties {
    private val generator = UuidV7Generator()

    @Suppress("ktlint:standard:function-naming") // jqwik properties are tests: descriptive names, like @Test methods
    @Property(tries = 50)
    fun `ids are version 7, unique and strictly increasing`(
        @ForAll @IntRange(min = 2, max = 2_000) count: Int,
    ) {
        val ids = List(count) { generator.next() }
        assertThat(ids).allSatisfy { assertThat(it.version()).isEqualTo(7) }
        assertThat(ids.toSet()).hasSize(count)
        // UUIDv7 sorts by time in its textual/byte order; JUG's TimeBasedEpochGenerator is monotonic within a millisecond.
        assertThat(ids.map { it.toString() }).isSorted()
    }
}
