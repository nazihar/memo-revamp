package com.memo.memo.internal

import com.fasterxml.jackson.annotation.JsonIgnore
import com.memo.common.authz.RoleHolders
import com.memo.common.web.FieldProblem
import com.memo.org.OrgDirectory
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.util.Currency
import java.util.UUID

/** The kinds of field a memo type can ask for (ADR-0043). */
enum class FieldType {
    TEXT,
    LONG_TEXT,
    NUMBER,
    AMOUNT,
    DATE,
    UNIT,
    PERSON,
    CHOICE,
    MULTI_CHOICE,
    ;

    /** Whether a field of this type can be read by workflow conditions and DoA rules. */
    val attributable: Boolean get() = this != LONG_TEXT && this != MULTI_CHOICE

    val numeric: Boolean get() = this == NUMBER || this == AMOUNT

    val chosen: Boolean get() = this == CHOICE || this == MULTI_CHOICE
}

/** One option of a choice field: a stable key (used by rules) and a label (shown). */
data class FieldChoice(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val key: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val label: String,
)

/** The rules of a field; only those that fit its type are kept. */
data class FieldRules(
    /** Text: the fewest characters. */
    val minLength: Int? = null,
    /** Text: the most characters (default 200, long text 4000). */
    val maxLength: Int? = null,
    /** Number, amount: the smallest value. */
    val min: BigDecimal? = null,
    /** Number, amount: the largest value. */
    val max: BigDecimal? = null,
    /** Number, amount: digits after the decimal point (default 0, amount 2). */
    val decimals: Int? = null,
    /** Amount: the currencies offered (ISO 4217), the first being the default. */
    val currencies: List<String> = emptyList(),
    /** Choice, multiple choice: the options, in order. */
    val choices: List<FieldChoice> = emptyList(),
)

/** A field of a memo type (ADR-0043): what the initiator fills in, in order. */
data class MemoField(
    /** Stable key, used in metadata and, for attribute fields, in workflow rules. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val key: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val label: String,
    val help: String? = null,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val type: FieldType,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val required: Boolean = false,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val rules: FieldRules = FieldRules(),
    /** Exposed to workflow conditions and DoA rules under [key] (E01 T3). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val attribute: Boolean = false,
    /** Shown in inbox lists (E05). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val inbox: Boolean = false,
) {
    @get:JsonIgnore
    val maxLength: Int get() = rules.maxLength ?: if (type == FieldType.LONG_TEXT) LONG_TEXT_DEFAULT else TEXT_DEFAULT

    @get:JsonIgnore
    val decimals: Int get() = rules.decimals ?: if (type == FieldType.AMOUNT) AMOUNT_DECIMALS else 0

    /** The field as stored: trimmed texts, and only the rules that apply to its type. */
    fun normalized(): MemoField {
        val text = type == FieldType.TEXT || type == FieldType.LONG_TEXT
        return copy(
            key = key.trim(),
            label = label.trim(),
            help = help?.trim()?.ifEmpty { null },
            rules =
                FieldRules(
                    minLength = rules.minLength.takeIf { text },
                    maxLength = rules.maxLength.takeIf { text },
                    min = rules.min.takeIf { type.numeric },
                    max = rules.max.takeIf { type.numeric },
                    decimals = rules.decimals.takeIf { type.numeric },
                    currencies = if (type == FieldType.AMOUNT) rules.currencies.map { it.trim().uppercase() } else emptyList(),
                    choices = if (type.chosen) rules.choices.map { FieldChoice(it.key.trim(), it.label.trim()) } else emptyList(),
                ),
            attribute = attribute && type.attributable,
        )
    }

    companion object {
        const val TEXT_DEFAULT = 200
        const val LONG_TEXT_DEFAULT = 4000
        const val AMOUNT_DECIMALS = 2
    }
}

/** A unit people of which may raise a type: the unit alone, or with the units below it. */
data class ScopeUnit(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val subtree: Boolean = true,
)

/** Who may raise memos of a type (ADR-0043): people in these units or holding these roles; both empty: everyone. */
data class RaiseScope(
    val units: List<ScopeUnit> = emptyList(),
    val roles: List<UUID> = emptyList(),
) {
    @get:JsonIgnore
    val everyone: Boolean get() = units.isEmpty() && roles.isEmpty()
}

/**
 * What a memo type's field list and raise scope must satisfy (ADR-0043, design E01 §3): at most [MAX_FIELDS] fields with
 * unique, well-formed keys that are not built-in names; bounded labels and help; rules that fit together; known
 * currencies; unique choice keys; known units and roles. Problems point at `fields/<i>/<property>` and `raiseScope/...`.
 */
@Component
class MemoFieldChecks(
    private val directory: OrgDirectory,
    private val roles: RoleHolders,
) {
    fun problems(
        fields: List<MemoField>,
        scope: RaiseScope,
    ): List<FieldProblem> {
        val duplicates = fields.groupBy { it.key }.filterValues { it.size > 1 }.keys
        return listOfNotNull(
            FieldProblem("fields", "TOO_MANY", "At most $MAX_FIELDS fields.").takeIf { fields.size > MAX_FIELDS },
            FieldProblem("raiseScope", "TOO_MANY", "At most $MAX_SCOPE units and roles each.").takeIf {
                scope.units.size > MAX_SCOPE || scope.roles.size > MAX_SCOPE
            },
        ) +
            fields.flatMapIndexed { i, field -> field("fields/$i", field, field.key in duplicates) } +
            scope.units.mapIndexedNotNull { i, unit ->
                FieldProblem("raiseScope/units/$i", "UNKNOWN_UNIT", "No unit ${unit.id}.").takeUnless { directory.unitExists(unit.id) }
            } +
            scope.roles.mapIndexedNotNull { i, role ->
                FieldProblem("raiseScope/roles/$i", "UNKNOWN_ROLE", "No role $role.").takeUnless { roles.exists(role) }
            }
    }

    private fun field(
        at: String,
        field: MemoField,
        duplicate: Boolean,
    ): List<FieldProblem> {
        val r = field.rules
        return listOfNotNull(
            FieldProblem("$at/key", "FORMAT", "A letter, then up to 39 letters or digits (for example amount or loanTerm).").takeUnless {
                KEY.matches(field.key)
            },
            FieldProblem("$at/key", "RESERVED", "This key is used by the memo itself.").takeIf { field.key in RESERVED },
            FieldProblem("$at/key", "DUPLICATE", "Another field has this key.").takeIf { duplicate },
            FieldProblem("$at/label", "LENGTH", "A label of 1 to $MAX_LABEL characters.").takeIf {
                field.label.isEmpty() || field.label.length > MAX_LABEL
            },
            FieldProblem("$at/help", "LENGTH", "At most $MAX_HELP characters.").takeIf { (field.help?.length ?: 0) > MAX_HELP },
        ) + rules(at, field, r)
    }

    private fun rules(
        at: String,
        field: MemoField,
        r: FieldRules,
    ): List<FieldProblem> =
        when (field.type) {
            FieldType.TEXT, FieldType.LONG_TEXT -> {
                val limit = if (field.type == FieldType.LONG_TEXT) MAX_LONG_TEXT else MAX_TEXT
                listOfNotNull(
                    FieldProblem("$at/rules/maxLength", "RANGE", "1 to $limit characters.").takeIf { field.maxLength !in 1..limit },
                    FieldProblem("$at/rules/minLength", "RANGE", "0 up to the maximum length.").takeIf {
                        r.minLength.let { it != null && it !in 0..field.maxLength }
                    },
                )
            }

            FieldType.NUMBER, FieldType.AMOUNT -> {
                listOfNotNull(
                    FieldProblem(
                        "$at/rules/decimals",
                        "RANGE",
                        "0 to $MAX_DECIMALS decimals.",
                    ).takeIf { field.decimals !in 0..MAX_DECIMALS },
                    FieldProblem("$at/rules/min", "RANGE", "The minimum is above the maximum.").takeIf {
                        val min = r.min
                        val max = r.max
                        min != null && max != null && min > max
                    },
                    FieldProblem("$at/rules", "RANGE", "Limits within ±$LIMIT_TEXT.").takeIf {
                        listOfNotNull(r.min, r.max).any { it.abs() >= LIMIT }
                    },
                ) + if (field.type == FieldType.AMOUNT) currencies(at, r.currencies) else emptyList()
            }

            FieldType.CHOICE, FieldType.MULTI_CHOICE -> {
                choices(at, r.choices)
            }

            FieldType.DATE, FieldType.UNIT, FieldType.PERSON -> {
                emptyList()
            }
        }

    private fun currencies(
        at: String,
        codes: List<String>,
    ): List<FieldProblem> =
        listOfNotNull(
            FieldProblem("$at/rules/currencies", "COUNT", "1 to $MAX_CURRENCIES currencies.").takeIf { codes.size !in 1..MAX_CURRENCIES },
            FieldProblem("$at/rules/currencies", "DUPLICATE", "Each currency once.").takeIf { codes.toSet().size != codes.size },
        ) +
            codes.mapIndexedNotNull { i, code ->
                FieldProblem("$at/rules/currencies/$i", "UNKNOWN_CURRENCY", "No currency $code (ISO 4217).").takeUnless { currency(code) }
            }

    private fun choices(
        at: String,
        choices: List<FieldChoice>,
    ): List<FieldProblem> {
        val duplicates = choices.groupBy { it.key }.filterValues { it.size > 1 }.keys
        return listOfNotNull(
            FieldProblem("$at/rules/choices", "COUNT", "1 to $MAX_CHOICES options.").takeIf { choices.size !in 1..MAX_CHOICES },
        ) +
            choices.flatMapIndexed { i, choice ->
                listOfNotNull(
                    FieldProblem("$at/rules/choices/$i/key", "FORMAT", "Lower-case letters, digits and hyphens.").takeUnless {
                        CHOICE_KEY.matches(choice.key)
                    },
                    FieldProblem(
                        "$at/rules/choices/$i/key",
                        "DUPLICATE",
                        "Another option has this key.",
                    ).takeIf { choice.key in duplicates },
                    FieldProblem("$at/rules/choices/$i/label", "LENGTH", "A label of 1 to $MAX_LABEL characters.").takeIf {
                        choice.label.isEmpty() || choice.label.length > MAX_LABEL
                    },
                )
            }
    }

    private fun currency(code: String): Boolean = runCatching { Currency.getInstance(code) }.isSuccess && CURRENCY.matches(code)

    companion object {
        const val MAX_FIELDS = 40
        const val MAX_SCOPE = 200
        const val MAX_LABEL = 100
        const val MAX_HELP = 300
        const val MAX_TEXT = 1000
        const val MAX_LONG_TEXT = 10000
        const val MAX_DECIMALS = 6
        const val MAX_CURRENCIES = 20
        const val MAX_CHOICES = 100
        const val LIMIT_TEXT = "10^15"

        /** Values and limits stay below this, so amounts compare exactly as doubles (ADR-0043). */
        val LIMIT: BigDecimal = BigDecimal.TEN.pow(15)
        val KEY = Regex("^[a-z][a-zA-Z0-9]{0,39}$")
        val CHOICE_KEY = Regex("^[a-z0-9][a-z0-9-]{0,49}$")
        private val CURRENCY = Regex("^[A-Z]{3}$")

        /** The memo's own names and the built-in workflow attributes (and their currency companions). */
        val RESERVED = setOf("subject", "classification", "classificationRank", "memoTypeId", "reference", "status", "type")
    }
}
