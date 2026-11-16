package com.memo.memo.internal.legacy

import com.memo.memo.internal.FieldType
import com.memo.memo.internal.MemoField
import java.util.Locale

/**
 * Field values of a legacy row as a sheet holds them (design E06 §6.1): every value is text there, while an amount is
 * `{value, currency}` and a multiple choice a list of keys in the memo's metadata. Text of such fields is read as
 * `1500.50 BDT`, `BDT 1500.50` or `1500.50` (the field's first currency), and `a; b` (or `a, b`); anything else is
 * passed on unchanged, so the metadata check refuses what it cannot read. Values that are not text are left as they are.
 */
internal object LegacyFieldText {
    private val SEPARATORS = Regex("[;,]")
    private val SPACES = Regex("\\s+")
    private val CURRENCY = Regex("^[A-Za-z]{3}$")

    fun typed(
        fields: List<MemoField>,
        metadata: Map<String, Any?>,
    ): Map<String, Any?> {
        val byKey = fields.associateBy { it.key }
        return metadata.mapValues { (key, value) ->
            val type = byKey[key]?.type
            when {
                value !is String -> value
                type == FieldType.AMOUNT -> amount(value)
                type == FieldType.MULTI_CHOICE -> value.split(SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }
                else -> value
            }
        }
    }

    private fun amount(text: String): Any {
        val parts = text.trim().split(SPACES)
        val currency = parts.firstOrNull { CURRENCY.matches(it) }
        val number = parts.filterNot { it == currency }
        return when {
            number.size != 1 -> text
            currency == null -> mapOf("value" to number.single())
            else -> mapOf("value" to number.single(), "currency" to currency.uppercase(Locale.ROOT))
        }
    }
}
