package com.memo.imports.internal

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Relative paths of originals (design E06 §5.1; test plan E06 T5 `PathRulesTest` and the path property): NFC and `/`, no
 * way out of the folder, no hidden characters, the length and depth caps, and paths that differ only in case.
 */
class PathRulesTest {
    @Test
    fun `valid paths are kept in NFC`() {
        assertThat(PathRules.normalise("2019/CR-0001/memo.pdf")).isEqualTo("2019/CR-0001/memo.pdf")
        assertThat(PathRules.normalise("a/Cafe\u0301.doc")).isEqualTo("a/Caf\u00e9.doc")
        assertThat(PathRules.normalise("أرشيف/مذكرة ١.pdf")).isEqualTo("أرشيف/مذكرة ١.pdf")
        assertThat(PathRules.normalise("...hidden/x")).isEqualTo("...hidden/x")
    }

    @Test
    fun `paths that leave the folder, hide characters or break the caps are refused`() {
        listOf(
            "",
            "/etc/passwd",
            "a//b",
            "a/",
            "./a",
            "a/../b",
            "..",
            "a\b",
            "a/\u0000b",
            "a/\u0007b",
            "a/b\u202Eexe.pdf",
            "a/\u200Bb",
            "a/\nb",
            "a/\u2028b",
            "a/\uD800b",
            "x".repeat(PathRules.MAX_LENGTH + 1),
            (1..PathRules.MAX_SEGMENTS + 1).joinToString("/") { "d" },
        ).forEach { assertThat(PathRules.normalise(it)).describedAs(it).isNull() }
        assertThat(PathRules.normalise("x".repeat(PathRules.MAX_LENGTH))).isNotNull()
        assertThat(PathRules.normalise((1..PathRules.MAX_SEGMENTS).joinToString("/") { "d" })).isNotNull()
    }

    @Test
    fun `paths that differ only in case are found, and fold alike`() {
        assertThat(PathRules.caseOnly(listOf("A/Memo.pdf", "a/memo.PDF", "a/other.pdf", "A/Memo.pdf"))).containsExactlyInAnyOrder(
            "A/Memo.pdf",
            "a/memo.PDF",
        )
        assertThat(PathRules.fold("Straße/X.PDF")).isEqualTo(PathRules.fold("STRASSE/x.pdf"))
    }

    @Property
    fun `any string normalises to a valid path that round-trips, or is refused`(
        @ForAll("paths") raw: String,
    ) {
        val path = PathRules.normalise(raw) ?: return
        assertThat(PathRules.normalise(path)).isEqualTo(path)
        val segments = path.split('/')
        assertThat(segments).noneMatch { it.isEmpty() || it == "." || it == ".." }
        assertThat(path).doesNotContain("\\").doesNotStartWith("/")
        assertThat(path.length).isLessThanOrEqualTo(PathRules.MAX_LENGTH)
    }

    @Provide
    fun paths(): Arbitrary<String> {
        val pieces = Arbitraries.of("a", "B", ".", "..", "/", "\\", "é", "e\u0301", "\u0000", "\u202E", " ", "x.pdf", "\uD800", "ß")
        return Arbitraries.oneOf(
            pieces.list().ofMaxSize(MAX_PIECES).map { it.joinToString("") },
            Arbitraries.strings().all().ofMaxLength(MAX_CHARS),
        )
    }

    private companion object {
        const val MAX_PIECES = 30
        const val MAX_CHARS = 200
    }
}
