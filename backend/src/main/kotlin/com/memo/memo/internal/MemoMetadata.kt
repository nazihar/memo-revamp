package com.memo.memo.internal

import com.memo.common.web.FieldProblem
import com.memo.identity.UserSummaries
import com.memo.org.OrgDirectory
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

/** Metadata as checked: the values in their stored form, and what is wrong with the input. */
data class CheckedMetadata(
    val values: Map<String, Any>,
    val problems: List<FieldProblem>,
)

/**
 * Checks and normalises a memo's metadata against its type's fields (ADR-0043): each value must have its field's type and
 * fit its rules; with [requireAll] (submit), every required field must have a value. Stored forms: text, numbers (a plain
 * decimal string with the field's decimals), dates (`YYYY-MM-DD`), unit and person ids, and choice keys as strings; an
 * amount as `{value, currency}`; a multiple choice as a list of keys. Empty values are left out. Problems point at
 * `metadata.<key>`. Inside a tenant transaction (units and people are looked up).
 */
@Component
class MemoMetadata(
    private val directory: OrgDirectory,
    private val users: UserSummaries,
) {
    fun check(
        fields: List<MemoField>,
        input: Map<String, Any?>,
        requireAll: Boolean,
    ): CheckedMetadata {
        val byKey = fields.associateBy { it.key }
        val problems = mutableListOf<FieldProblem>()
        input.keys.filterNot { it in byKey }.forEach {
            problems += FieldProblem("metadata.$it", "UNKNOWN_FIELD", "This memo type has no such field.")
        }
        val values = mutableMapOf<String, Any>()
        fields.forEach { field ->
            val raw = input[field.key].takeUnless(MetadataValues::empty)
            when {
                raw == null -> {
                    if (requireAll && field.required) problems += problem(field, "FIELD_REQUIRED", "Fill in ${field.label}.")
                }

                else -> {
                    when (val result = value(field, raw)) {
                        is MetadataValues.Outcome.Ok -> values[field.key] = result.value
                        is MetadataValues.Outcome.Bad -> problems += problem(field, "FIELD_INVALID", result.message)
                    }
                }
            }
        }
        return CheckedMetadata(values, problems)
    }

    private fun value(
        field: MemoField,
        raw: Any,
    ): MetadataValues.Outcome =
        when (field.type) {
            FieldType.TEXT, FieldType.LONG_TEXT -> MetadataValues.text(field, raw)
            FieldType.NUMBER -> MetadataValues.number(field, raw)
            FieldType.AMOUNT -> MetadataValues.amount(field, raw)
            FieldType.DATE -> MetadataValues.date(raw)
            FieldType.UNIT -> unit(raw)
            FieldType.PERSON -> person(raw)
            FieldType.CHOICE -> MetadataValues.choice(field, raw)
            FieldType.MULTI_CHOICE -> MetadataValues.choices(field, raw)
        }

    private fun unit(raw: Any): MetadataValues.Outcome {
        val id = MetadataValues.uuid(raw)
        return when {
            id == null -> MetadataValues.Outcome.Bad("Choose a unit.")
            directory.unitExists(id) -> MetadataValues.Outcome.Ok(id.toString())
            else -> MetadataValues.Outcome.Bad("No such unit.")
        }
    }

    private fun person(raw: Any): MetadataValues.Outcome {
        val id = MetadataValues.uuid(raw)
        return when {
            id == null -> MetadataValues.Outcome.Bad("Choose a person.")
            users.of(setOf(id)).containsKey(id) -> MetadataValues.Outcome.Ok(id.toString())
            else -> MetadataValues.Outcome.Bad("No such person.")
        }
    }

    private fun problem(
        field: MemoField,
        code: String,
        message: String,
    ) = FieldProblem("metadata.${field.key}", code, message)
}

/** The checks of single values that need no lookups ([MemoMetadata]): pure. */
internal object MetadataValues {
    sealed interface Outcome {
        data class Ok(
            val value: Any,
        ) : Outcome

        data class Bad(
            val message: String,
        ) : Outcome
    }

    fun text(
        field: MemoField,
        raw: Any,
    ): Outcome {
        val text = (raw as? String)?.trim()
        val min = field.rules.minLength ?: 0
        return when {
            text == null -> Outcome.Bad("Enter text.")
            text.length > field.maxLength -> Outcome.Bad("At most ${field.maxLength} characters.")
            text.length < min -> Outcome.Bad("At least $min characters.")
            else -> Outcome.Ok(text)
        }
    }

    fun number(
        field: MemoField,
        raw: Any,
    ): Outcome {
        val number =
            when (raw) {
                is String -> raw.trim().toBigDecimalOrNull()
                is Number -> raw.toString().toBigDecimalOrNull()
                else -> null
            }
        val min = field.rules.min
        val max = field.rules.max
        return when {
            number == null -> Outcome.Bad("Enter a number.")
            number.stripTrailingZeros().scale() > field.decimals -> Outcome.Bad("At most ${field.decimals} decimals.")
            number.abs() >= MemoFieldChecks.LIMIT -> Outcome.Bad("Too large.")
            min != null && number < min -> Outcome.Bad("At least ${min.toPlainString()}.")
            max != null && number > max -> Outcome.Bad("At most ${max.toPlainString()}.")
            else -> Outcome.Ok(number.setScale(field.decimals).toPlainString())
        }
    }

    /** An amount: `{value, currency}`, the currency one of the field's (the first when left out). */
    fun amount(
        field: MemoField,
        raw: Any,
    ): Outcome {
        val amount = raw as? Map<*, *>
        val currencies = field.rules.currencies
        val currency = (amount?.get("currency") as? String)?.trim()?.uppercase() ?: currencies.firstOrNull()
        val value = amount?.get("value")?.takeUnless(::empty)
        val number = value?.let { number(field, it) }
        return when {
            amount == null -> Outcome.Bad("Enter an amount and its currency.")
            currency == null || currency !in currencies -> Outcome.Bad("Choose one of ${currencies.joinToString()}.")
            number == null -> Outcome.Bad("Enter an amount.")
            number is Outcome.Ok -> Outcome.Ok(mapOf("value" to number.value, "currency" to currency))
            else -> number
        }
    }

    fun date(raw: Any): Outcome {
        val text = raw as? String ?: return Outcome.Bad("Enter a date.")
        return try {
            Outcome.Ok(LocalDate.parse(text.trim()).toString())
        } catch (_: DateTimeParseException) {
            Outcome.Bad("Enter a date as YYYY-MM-DD.")
        }
    }

    fun choice(
        field: MemoField,
        raw: Any,
    ): Outcome {
        val key = (raw as? String)?.trim()
        return if (key != null && field.rules.choices.any { it.key == key }) Outcome.Ok(key) else Outcome.Bad("Choose one of the options.")
    }

    fun choices(
        field: MemoField,
        raw: Any,
    ): Outcome {
        val keys = (raw as? List<*>)?.map { (it as? String)?.trim() }
        val known = field.rules.choices.map { it.key }
        return when {
            keys == null || keys.any { it == null || it !in known } -> Outcome.Bad("Choose options from the list.")

            keys.toSet().size != keys.size -> Outcome.Bad("Choose each option once.")

            // Kept in the order of the options, so the same choice always reads the same.
            else -> Outcome.Ok(known.filter { it in keys })
        }
    }

    fun uuid(raw: Any?): UUID? = (raw as? String)?.let { runCatching { UUID.fromString(it.trim()) }.getOrNull() }

    /** No value: null, blank text, an empty list, or an amount without a value. */
    fun empty(raw: Any?): Boolean =
        raw == null ||
            (raw is String && raw.isBlank()) ||
            (raw is List<*> && raw.isEmpty()) ||
            (raw is Map<*, *> && raw["value"].let { it == null || (it is String && it.isBlank()) })
}
