package com.memo.common.numbering

import com.memo.common.web.FieldProblem
import java.time.LocalDate

/** When a sequence starts again at 1 (ADR-0044). */
enum class NumberReset { NEVER, YEARLY, FINANCIAL_YEAR }

/** How a financial year is written: `2026-27` (LONG) or `FY27` (SHORT). */
enum class FinancialYearStyle { LONG, SHORT }

/**
 * One part of a business number (ADR-0044, shared with resolution numbers by ADR-0065). The parts that come from the
 * numbered thing (a memo type's code, a unit's code, a committee's code, a meeting's number) are [Code] parts, resolved
 * by the caller.
 */
sealed interface FormatPart {
    /** Fixed text, including separators. */
    data class Text(
        val text: String?,
    ) : FormatPart

    /**
     * A value the caller resolves by [name]. A code that [varies] between numbers of one format (a unit, a meeting number)
     * is a placeholder in the skeleton; one that does not (a type's or a committee's own code) is written out.
     */
    data class Code(
        val name: String,
        val varies: Boolean,
        val maxLength: Int,
    ) : FormatPart

    /** The calendar year: 2 or 4 digits. */
    data class Year(
        val digits: Int?,
    ) : FormatPart

    /** The financial year, as `2026-27` or `FY27`. */
    data class FinancialYear(
        val style: FinancialYearStyle?,
    ) : FormatPart

    /** The sequence within its counter, padded to a minimum of digits. */
    data class Sequence(
        val digits: Int?,
    ) : FormatPart
}

/** A number format: its parts, when the sequence restarts, and the month the financial year starts in (1 to 12). */
data class NumberFormat(
    val parts: List<FormatPart>,
    val reset: NumberReset,
    val fyStartMonth: Int,
)

/**
 * Periods, rendering, skeletons and the checks of a [NumberFormat] (ADR-0044). Pure. Memo numbers (`memo.internal`) and
 * resolution numbers (E06) are both written by it; each keeps its own counters.
 */
object NumberFormats {
    const val MAX_LENGTH = 100
    const val MAX_PARTS = 12
    const val MAX_TEXT = 20
    private const val LONG_FY = 7
    private const val SHORT_FY = 4
    private const val CENTURY = 100
    private const val MAX_SEQUENCE_DIGITS = 9
    private const val MONTHS = 12
    private const val LONG_YEAR = 4
    private val YEAR_DIGITS = setOf(2, LONG_YEAR)
    private val TEXT = Regex("^[A-Za-z0-9 /._()-]{1,20}$")

    /** The financial year [date] falls in, by its start and end calendar years (equal when it starts in January). */
    fun financialYear(
        fyStartMonth: Int,
        date: LocalDate,
    ): Pair<Int, Int> {
        val start = if (date.monthValue >= fyStartMonth) date.year else date.year - 1
        return start to if (fyStartMonth == 1) start else start + 1
    }

    /** The counter period of [date]: `ALL`, the year (`2026`) or the financial year (`FY2026-27`). */
    fun period(
        format: NumberFormat,
        date: LocalDate,
    ): String =
        when (format.reset) {
            NumberReset.NEVER -> {
                "ALL"
            }

            NumberReset.YEARLY -> {
                date.year.toString()
            }

            NumberReset.FINANCIAL_YEAR -> {
                val (start, end) = financialYear(format.fyStartMonth, date)
                "FY$start-${two(end)}"
            }
        }

    /** The number [sequence] of [format] on [date], with each [FormatPart.Code] given by [codes]. */
    fun render(
        format: NumberFormat,
        date: LocalDate,
        sequence: Long,
        codes: (String) -> String,
    ): String =
        format.parts.joinToString("") { part ->
            when (part) {
                is FormatPart.Text -> {
                    part.text.orEmpty()
                }

                is FormatPart.Code -> {
                    codes(part.name)
                }

                is FormatPart.Year -> {
                    if (part.digits == 2) two(date.year) else date.year.toString()
                }

                is FormatPart.FinancialYear -> {
                    val (start, end) = financialYear(format.fyStartMonth, date)
                    when {
                        part.style == FinancialYearStyle.SHORT -> "FY${two(end)}"
                        start == end -> start.toString()
                        else -> "$start-${two(end)}"
                    }
                }

                is FormatPart.Sequence -> {
                    sequence.toString().padStart(part.digits ?: 1, '0')
                }
            }
        }

    /**
     * What a number of [format] looks like apart from the values that vary (ADR-0044): two formats overlap when these are
     * equal, because their numbers could then be the same. Codes that do not vary come from [codes].
     */
    fun skeleton(
        format: NumberFormat,
        codes: (String) -> String,
    ): String =
        format.parts.joinToString("") { part ->
            when (part) {
                is FormatPart.Text -> part.text.orEmpty()
                is FormatPart.Code -> if (part.varies) "{${part.name}}" else codes(part.name)
                is FormatPart.Year -> "{year${part.digits}}"
                is FormatPart.FinancialYear -> "{fy-${part.style}}"
                is FormatPart.Sequence -> "{sequence}"
            }
        }

    /**
     * What is wrong with [format], each problem at [at]`/parts`, [at]`/reset`, [at]`/fyStartMonth` or [at]`/parts/{i}/…`.
     * [own] are the caller's problems with its own settings (a memo series counting per unit); they are listed after the
     * reset checks, so the order stays the one memo types have always shown.
     */
    fun problems(
        format: NumberFormat,
        at: String,
        own: List<FieldProblem> = emptyList(),
    ): List<FieldProblem> {
        val parts = format.parts
        val reset =
            listOfNotNull(
                FieldProblem("$at/parts", "COUNT", "1 to $MAX_PARTS parts.").takeIf { parts.size !in 1..MAX_PARTS },
                FieldProblem("$at/parts", "SEQUENCE", "Exactly one sequence part.").takeIf {
                    parts.count { it is FormatPart.Sequence } != 1
                },
                FieldProblem("$at/reset", "YEAR_MISSING", "A number that restarts every year needs the year in it.").takeIf {
                    format.reset == NumberReset.YEARLY && parts.none { it is FormatPart.Year }
                },
                FieldProblem("$at/reset", "FINANCIAL_YEAR_MISSING", "A number that restarts every financial year needs it in it.")
                    .takeIf { format.reset == NumberReset.FINANCIAL_YEAR && parts.none { it is FormatPart.FinancialYear } },
            )
        val whole =
            listOfNotNull(
                FieldProblem("$at/fyStartMonth", "RANGE", "A month from 1 to 12.").takeIf { format.fyStartMonth !in 1..MONTHS },
                FieldProblem("$at/parts", "TOO_LONG", "A number can be at most $MAX_LENGTH characters.").takeIf {
                    longest(format) > MAX_LENGTH
                },
            )
        return reset + own + whole + parts.flatMapIndexed { i, part -> part("$at/parts/$i", part) }
    }

    private fun part(
        at: String,
        part: FormatPart,
    ): List<FieldProblem> =
        listOfNotNull(
            FieldProblem("$at/text", "FORMAT", "1 to $MAX_TEXT letters, digits, spaces or / . _ ( ) -").takeIf {
                part is FormatPart.Text && !TEXT.matches(part.text.orEmpty())
            },
            FieldProblem("$at/digits", "RANGE", "2 or 4 digits.").takeIf { part is FormatPart.Year && part.digits !in YEAR_DIGITS },
            FieldProblem("$at/digits", "RANGE", "1 to $MAX_SEQUENCE_DIGITS digits.").takeIf {
                part is FormatPart.Sequence && (part.digits ?: 0) !in 1..MAX_SEQUENCE_DIGITS
            },
            FieldProblem("$at/style", "REQUIRED", "Choose how the financial year is written.").takeIf {
                part is FormatPart.FinancialYear && part.style == null
            },
        )

    /** The longest number [format] can make with the longest codes and a sequence at its padding. */
    private fun longest(format: NumberFormat): Int =
        format.parts.sumOf { part ->
            when (part) {
                is FormatPart.Text -> part.text.orEmpty().length
                is FormatPart.Code -> part.maxLength
                is FormatPart.Year -> part.digits ?: LONG_YEAR
                is FormatPart.FinancialYear -> if (part.style == FinancialYearStyle.SHORT) SHORT_FY else LONG_FY
                is FormatPart.Sequence -> part.digits ?: 1
            }
        }

    private fun two(year: Int) = (year % CENTURY).toString().padStart(2, '0')
}
