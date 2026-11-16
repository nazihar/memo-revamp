package com.memo.memo.internal.legacy

import com.memo.memo.internal.FieldType
import com.memo.memo.internal.MemoField
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Field values as a legacy sheet holds them (design E06 §6.6): amounts and multiple choices read from text. */
class LegacyFieldTextTest {
    private val fields =
        listOf(
            MemoField("amount", "Amount", type = FieldType.AMOUNT),
            MemoField("tags", "Tags", type = FieldType.MULTI_CHOICE),
            MemoField("note", "Note", type = FieldType.TEXT),
        )

    @Test
    fun `an amount reads with its currency before or after, or alone, and anything else is passed on`() {
        fun amount(text: String) = LegacyFieldText.typed(fields, mapOf("amount" to text))["amount"]
        assertThat(amount("1500.50 bdt")).isEqualTo(mapOf("value" to "1500.50", "currency" to "BDT"))
        assertThat(amount("BDT 1500.50")).isEqualTo(mapOf("value" to "1500.50", "currency" to "BDT"))
        assertThat(amount(" 1500 ")).isEqualTo(mapOf("value" to "1500"))
        assertThat(amount("fifteen hundred taka")).isEqualTo("fifteen hundred taka")
    }

    @Test
    fun `a multiple choice splits on semicolons or commas, and other fields and non-text values stay as they are`() {
        val typed = LegacyFieldText.typed(fields, mapOf("tags" to "wc; capex,  ", "note" to "a; b", "unknown" to "x", "amount" to 5))
        assertThat(typed["tags"]).isEqualTo(listOf("wc", "capex"))
        assertThat(typed["note"]).isEqualTo("a; b")
        assertThat(typed["unknown"]).isEqualTo("x")
        assertThat(typed["amount"]).isEqualTo(5)
    }
}
