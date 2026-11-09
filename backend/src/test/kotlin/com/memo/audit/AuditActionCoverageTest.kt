package com.memo.audit

import com.memo.testsupport.repoRoot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.walk

/**
 * Phase 1a exit criterion: every state change is audited, and the tests prove it. Each action named literally in an
 * audit `record(...)` call must be asserted by some test (as `"action"` or `'action'` in SQL). Actions built from a
 * template (`"org.unit.$action"`) are covered by the tests of the module that builds them. The matrix test adds the
 * runtime half: an allowed change that succeeds must add an audit entry.
 */
class AuditActionCoverageTest {
    private fun sources(dir: String): List<Path> =
        repoRoot
            .resolve(dir)
            .walk()
            .filter { it.isRegularFile() && it.extension == "kt" }
            .toList()

    @Test
    fun `every audit action recorded in the code is asserted by a test`() {
        val recorded =
            sources("backend/src/main/kotlin")
                .flatMap { file -> RECORD.findAll(file.readText()).map { it.groupValues[1] } }
                .filterNot { '$' in it }
                .toSortedSet()
        val tests = sources("backend/src/test/kotlin").joinToString("\n") { it.readText() }

        assertThat(recorded).describedAs("audit actions found").hasSizeGreaterThan(MIN_ACTIONS)
        assertThat(recorded.filterNot { "\"$it\"" in tests || "'$it'" in tests })
            .describedAs("audit actions no test asserts")
            .isEmpty()
    }

    private companion object {
        /** `audit.record("a.b.c", ...)`, also with the action on the next line. */
        val RECORD = Regex("""\brecord\(\s*"([a-z_]+(?:\.[a-z_${'$'}{}]+)+)"""")
        const val MIN_ACTIONS = 40
    }
}
