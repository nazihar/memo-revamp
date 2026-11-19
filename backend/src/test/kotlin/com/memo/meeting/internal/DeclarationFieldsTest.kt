package com.memo.meeting.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** What people write on a declaration (test plan E04, T1; design E04 §1.1, §1.2): lengths after trimming, and `how`. */
class DeclarationFieldsTest {
    @Test
    fun `the nature is 1 to 1,000 characters after trimming`() {
        assertThat(DeclarationRules.nature(null).second?.code).isEqualTo("NATURE_REQUIRED")
        assertThat(DeclarationRules.nature("   ").second?.code).isEqualTo("NATURE_REQUIRED")
        assertThat(DeclarationRules.nature("  a  ")).isEqualTo("a" to null)
        assertThat(DeclarationRules.nature(" ${"n".repeat(1000)} ").second).isNull()
        assertThat(DeclarationRules.nature("n".repeat(1001)).second?.let { it.field to it.code }).isEqualTo("nature" to "LENGTH")
    }

    @Test
    fun `a reason is 1 to 500 characters`() {
        assertThat(DeclarationRules.reason(" ").second?.code).isEqualTo("REASON_REQUIRED")
        assertThat(DeclarationRules.reason("r".repeat(500))).isEqualTo("r".repeat(500) to null)
        assertThat(DeclarationRules.reason("r".repeat(501)).second?.code).isEqualTo("LENGTH")
    }

    @Test
    fun `how is required on someone's behalf, and is never in the app or the system there`() {
        assertThat(DeclarationRules.how(null)?.code).isEqualTo("HOW_REQUIRED")
        assertThat(DeclarationRules.how(DeclarationHow.IN_APP)?.code).isEqualTo("HOW_REQUIRED")
        assertThat(DeclarationRules.how(DeclarationHow.SYSTEM)?.code).isEqualTo("HOW_REQUIRED")
        listOf(DeclarationHow.AT_MEETING, DeclarationHow.IN_PERSON, DeclarationHow.EMAIL, DeclarationHow.PHONE).forEach {
            assertThat(DeclarationRules.how(it)).describedAs(it.name).isNull()
        }
    }

    @Test
    fun `the note of how it was given is 0 to 200 characters, and a ruling's note 0 to 500`() {
        assertThat(DeclarationRules.optional("howNote", "  ", DeclarationRules.MAX_HOW_NOTE)).isEqualTo(null to null)
        assertThat(DeclarationRules.optional("howNote", "h".repeat(200), DeclarationRules.MAX_HOW_NOTE).second).isNull()
        assertThat(DeclarationRules.optional("howNote", "h".repeat(201), DeclarationRules.MAX_HOW_NOTE).second?.code).isEqualTo("LENGTH")
        assertThat(DeclarationRules.optional("note", "n".repeat(501), DeclarationRules.MAX_RULING_NOTE).second?.field).isEqualTo("note")
    }

    @Test
    fun `the audit carries the SHA-256 of the text, never the text`() {
        assertThat(DeclarationRules.sha256("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }
}
