package com.memo.memo.internal

import com.memo.common.web.FieldProblem
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDate

/** The parts a memo number is made of (ADR-0044). */
enum class NumberPartKind {
    /** Fixed text, including separators. */
    TEXT,

    /** The memo type's code. */
    TYPE_CODE,

    /** The code of the memo's origin unit. */
    UNIT_CODE,

    /** The calendar year: 2 or 4 digits. */
    YEAR,

    /** The financial year, as `2026-27` or `FY27`. */
    FINANCIAL_YEAR,

    /** The sequence within the series, period and scope, padded to a minimum of digits. */
    SEQUENCE,
}

/** How a financial year is written: `2026-27` (LONG) or `FY27` (SHORT). */
enum class FinancialYearStyle { LONG, SHORT }

/** When the sequence starts again at 1 (ADR-0044). */
enum class NumberReset { NEVER, YEARLY, FINANCIAL_YEAR }

/** One sequence for the whole series, or one per origin unit. */
enum class NumberCountPer { SERIES, UNIT }

/** When a memo takes its number: its first submit, or its final approval. */
enum class NumberMoment { ON_SUBMIT, ON_APPROVAL }

data class NumberPart(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val kind: NumberPartKind,
    /** TEXT: the text. */
    val text: String? = null,
    /** YEAR: 2 or 4; SEQUENCE: the minimum number of digits, 1 to 9. */
    val digits: Int? = null,
    /** FINANCIAL_YEAR: how it is written. */
    val style: FinancialYearStyle? = null,
)

/**
 * How a memo type numbers its memos (ADR-0044): the parts of the number, the [series] whose counters it shares with every
 * type naming the same series, when the sequence restarts, whether it counts per unit, and when a memo is numbered.
 */
data class Numbering(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val series: String = GENERAL,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val parts: List<NumberPart> = DEFAULT_PARTS,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val reset: NumberReset = NumberReset.YEARLY,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val countPer: NumberCountPer = NumberCountPer.SERIES,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val moment: NumberMoment = NumberMoment.ON_SUBMIT,
    /** The month the financial year starts in, 1 to 12. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val fyStartMonth: Int = DEFAULT_FY_START,
) {
    /** Whether a number needs the memo's origin unit (its code, or a sequence per unit). */
    val needsUnit: Boolean get() = countPer == NumberCountPer.UNIT || parts.any { it.kind == NumberPartKind.UNIT_CODE }

    companion object {
        const val GENERAL = "general"
        const val DEFAULT_FY_START = 7

        /** `M-2026-00042`: the Phase 2 format, kept by every type until an administrator changes it. */
        val DEFAULT_PARTS =
            listOf(
                NumberPart(NumberPartKind.TEXT, text = "M-"),
                NumberPart(NumberPartKind.YEAR, digits = 4),
                NumberPart(NumberPartKind.TEXT, text = "-"),
                NumberPart(NumberPartKind.SEQUENCE, digits = 5),
            )
    }
}

/** Periods, rendering and the checks of a [Numbering] (ADR-0044). Pure. */
object NumberFormats {
    const val MAX_LENGTH = 100
    const val MAX_PARTS = 12
    const val MAX_TEXT = 20
    private const val TYPE_CODE_MAX = 10
    private const val UNIT_CODE_MAX = 30
    private const val LONG_FY = 7
    private const val SHORT_FY = 4
    private const val CENTURY = 100
    private const val MAX_SEQUENCE_DIGITS = 9
    private const val MONTHS = 12
    private const val LONG_YEAR = 4
    private val YEAR_DIGITS = setOf(2, LONG_YEAR)
    val SERIES = Regex("^[a-z][a-z0-9-]{0,49}$")
    private val TEXT = Regex("^[A-Za-z0-9 /._()-]{1,20}$")

    /** The financial year [date] falls in, by its start and end calendar years (equal when it starts in January). */
    fun financialYear(
        numbering: Numbering,
        date: LocalDate,
    ): Pair<Int, Int> {
        val start = if (date.monthValue >= numbering.fyStartMonth) date.year else date.year - 1
        return start to if (numbering.fyStartMonth == 1) start else start + 1
    }

    /** The counter period of [date]: `ALL`, the year (`2026`) or the financial year (`FY2026-27`). */
    fun period(
        numbering: Numbering,
        date: LocalDate,
    ): String =
        when (numbering.reset) {
            NumberReset.NEVER -> {
                "ALL"
            }

            NumberReset.YEARLY -> {
                date.year.toString()
            }

            NumberReset.FINANCIAL_YEAR -> {
                val (start, end) = financialYear(numbering, date)
                "FY$start-${two(end)}"
            }
        }

    /** The number of [sequence] for a memo of type [typeCode] from unit [unitCode] numbered on [date]. */
    fun render(
        numbering: Numbering,
        typeCode: String,
        unitCode: String?,
        date: LocalDate,
        sequence: Long,
    ): String =
        numbering.parts.joinToString("") { part ->
            when (part.kind) {
                NumberPartKind.TEXT -> {
                    part.text.orEmpty()
                }

                NumberPartKind.TYPE_CODE -> {
                    typeCode
                }

                NumberPartKind.UNIT_CODE -> {
                    checkNotNull(unitCode) { "the number needs the memo's unit" }
                }

                NumberPartKind.YEAR -> {
                    if (part.digits == 2) two(date.year) else date.year.toString()
                }

                NumberPartKind.FINANCIAL_YEAR -> {
                    val (start, end) = financialYear(numbering, date)
                    when {
                        part.style == FinancialYearStyle.SHORT -> "FY${two(end)}"
                        start == end -> start.toString()
                        else -> "$start-${two(end)}"
                    }
                }

                NumberPartKind.SEQUENCE -> {
                    sequence.toString().padStart(part.digits ?: 1, '0')
                }
            }
        }

    /**
     * What a number of [typeCode] looks like apart from the values that vary (ADR-0044): two series overlap when these are
     * equal, because their numbers could then be the same.
     */
    fun skeleton(
        numbering: Numbering,
        typeCode: String,
    ): String =
        numbering.parts.joinToString("") { part ->
            when (part.kind) {
                NumberPartKind.TEXT -> part.text.orEmpty()
                NumberPartKind.TYPE_CODE -> typeCode
                NumberPartKind.UNIT_CODE -> "{unit}"
                NumberPartKind.YEAR -> "{year${part.digits}}"
                NumberPartKind.FINANCIAL_YEAR -> "{fy-${part.style}}"
                NumberPartKind.SEQUENCE -> "{sequence}"
            }
        }

    /** What is wrong with [numbering], each problem at `numbering/...`. */
    fun problems(numbering: Numbering): List<FieldProblem> {
        val kinds = numbering.parts.map { it.kind }
        return listOfNotNull(
            FieldProblem("numbering/series", "FORMAT", "Lower-case letters, digits and hyphens, starting with a letter.").takeUnless {
                SERIES.matches(numbering.series)
            },
            FieldProblem("numbering/parts", "COUNT", "1 to $MAX_PARTS parts.").takeIf { numbering.parts.size !in 1..MAX_PARTS },
            FieldProblem("numbering/parts", "SEQUENCE", "Exactly one sequence part.").takeIf {
                kinds.count { it == NumberPartKind.SEQUENCE } !=
                    1
            },
            FieldProblem("numbering/reset", "YEAR_MISSING", "A number that restarts every year needs the year in it.").takeIf {
                numbering.reset == NumberReset.YEARLY && NumberPartKind.YEAR !in kinds
            },
            FieldProblem(
                "numbering/reset",
                "FINANCIAL_YEAR_MISSING",
                "A number that restarts every financial year needs it in it.",
            ).takeIf {
                numbering.reset == NumberReset.FINANCIAL_YEAR && NumberPartKind.FINANCIAL_YEAR !in kinds
            },
            FieldProblem("numbering/countPer", "UNIT_MISSING", "Counting per unit needs the unit code in the number.").takeIf {
                numbering.countPer == NumberCountPer.UNIT && NumberPartKind.UNIT_CODE !in kinds
            },
            FieldProblem("numbering/fyStartMonth", "RANGE", "A month from 1 to 12.").takeIf { numbering.fyStartMonth !in 1..MONTHS },
            FieldProblem("numbering/parts", "TOO_LONG", "A number can be at most $MAX_LENGTH characters.").takeIf {
                longest(numbering) > MAX_LENGTH
            },
        ) + numbering.parts.flatMapIndexed { i, part -> part("numbering/parts/$i", part) }
    }

    private fun part(
        at: String,
        part: NumberPart,
    ): List<FieldProblem> =
        listOfNotNull(
            FieldProblem("$at/text", "FORMAT", "1 to $MAX_TEXT letters, digits, spaces or / . _ ( ) -").takeIf {
                part.kind == NumberPartKind.TEXT && !TEXT.matches(part.text.orEmpty())
            },
            FieldProblem(
                "$at/digits",
                "RANGE",
                "2 or 4 digits.",
            ).takeIf { part.kind == NumberPartKind.YEAR && part.digits !in YEAR_DIGITS },
            FieldProblem("$at/digits", "RANGE", "1 to $MAX_SEQUENCE_DIGITS digits.").takeIf {
                part.kind == NumberPartKind.SEQUENCE && (part.digits ?: 0) !in 1..MAX_SEQUENCE_DIGITS
            },
            FieldProblem("$at/style", "REQUIRED", "Choose how the financial year is written.").takeIf {
                part.kind == NumberPartKind.FINANCIAL_YEAR && part.style == null
            },
        )

    /** The longest number [numbering] can make with the longest codes and a sequence at its padding. */
    private fun longest(numbering: Numbering): Int =
        numbering.parts.sumOf { part ->
            when (part.kind) {
                NumberPartKind.TEXT -> part.text.orEmpty().length
                NumberPartKind.TYPE_CODE -> TYPE_CODE_MAX
                NumberPartKind.UNIT_CODE -> UNIT_CODE_MAX
                NumberPartKind.YEAR -> part.digits ?: LONG_YEAR
                NumberPartKind.FINANCIAL_YEAR -> if (part.style == FinancialYearStyle.SHORT) SHORT_FY else LONG_FY
                NumberPartKind.SEQUENCE -> part.digits ?: 1
            }
        }

    private fun two(year: Int) = (year % CENTURY).toString().padStart(2, '0')
}
