package com.memo.memo.internal

import com.fasterxml.jackson.annotation.JsonIgnore
import com.memo.common.numbering.FormatPart
import com.memo.common.numbering.NumberFormat
import com.memo.common.web.FieldProblem
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDate
import com.memo.common.numbering.NumberFormats as SharedFormats

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

/** How a financial year is written: `2026-27` (LONG) or `FY27` (SHORT). Shared with resolution numbers (E06). */
typealias FinancialYearStyle = com.memo.common.numbering.FinancialYearStyle

/** When the sequence starts again at 1 (ADR-0044). Shared with resolution numbers (E06). */
typealias NumberReset = com.memo.common.numbering.NumberReset

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
    @get:JsonIgnore
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

/**
 * Periods, rendering and the checks of a [Numbering] (ADR-0044). Pure. The format code is shared
 * ([com.memo.common.numbering.NumberFormats], E06 T1); this object keeps memo's types and names and only maps them.
 */
object NumberFormats {
    const val MAX_LENGTH = SharedFormats.MAX_LENGTH
    const val MAX_PARTS = SharedFormats.MAX_PARTS
    const val MAX_TEXT = SharedFormats.MAX_TEXT
    private const val TYPE_CODE_MAX = 10
    private const val UNIT_CODE_MAX = 30
    private const val TYPE = "type"
    private const val UNIT = "unit"
    val SERIES = Regex("^[a-z][a-z0-9-]{0,49}$")

    /** The shared format of [numbering]: the type's code is written out, the unit's code varies. */
    fun format(numbering: Numbering): NumberFormat =
        NumberFormat(
            numbering.parts.map { part ->
                when (part.kind) {
                    NumberPartKind.TEXT -> FormatPart.Text(part.text)
                    NumberPartKind.TYPE_CODE -> FormatPart.Code(TYPE, varies = false, maxLength = TYPE_CODE_MAX)
                    NumberPartKind.UNIT_CODE -> FormatPart.Code(UNIT, varies = true, maxLength = UNIT_CODE_MAX)
                    NumberPartKind.YEAR -> FormatPart.Year(part.digits)
                    NumberPartKind.FINANCIAL_YEAR -> FormatPart.FinancialYear(part.style)
                    NumberPartKind.SEQUENCE -> FormatPart.Sequence(part.digits)
                }
            },
            numbering.reset,
            numbering.fyStartMonth,
        )

    /** The financial year [date] falls in, by its start and end calendar years (equal when it starts in January). */
    fun financialYear(
        numbering: Numbering,
        date: LocalDate,
    ): Pair<Int, Int> = SharedFormats.financialYear(numbering.fyStartMonth, date)

    /** The counter period of [date]: `ALL`, the year (`2026`) or the financial year (`FY2026-27`). */
    fun period(
        numbering: Numbering,
        date: LocalDate,
    ): String = SharedFormats.period(format(numbering), date)

    /** The number of [sequence] for a memo of type [typeCode] from unit [unitCode] numbered on [date]. */
    fun render(
        numbering: Numbering,
        typeCode: String,
        unitCode: String?,
        date: LocalDate,
        sequence: Long,
    ): String =
        SharedFormats.render(format(numbering), date, sequence) { code ->
            if (code == TYPE) typeCode else checkNotNull(unitCode) { "the number needs the memo's unit" }
        }

    /**
     * What a number of [typeCode] looks like apart from the values that vary (ADR-0044): two series overlap when these are
     * equal, because their numbers could then be the same.
     */
    fun skeleton(
        numbering: Numbering,
        typeCode: String,
    ): String = SharedFormats.skeleton(format(numbering)) { typeCode }

    /** What is wrong with [numbering], each problem at `numbering/...`. */
    fun problems(numbering: Numbering): List<FieldProblem> {
        val series =
            FieldProblem("numbering/series", "FORMAT", "Lower-case letters, digits and hyphens, starting with a letter.").takeUnless {
                SERIES.matches(numbering.series)
            }
        val unit =
            FieldProblem("numbering/countPer", "UNIT_MISSING", "Counting per unit needs the unit code in the number.").takeIf {
                numbering.countPer == NumberCountPer.UNIT && numbering.parts.none { it.kind == NumberPartKind.UNIT_CODE }
            }
        return listOfNotNull(series) + SharedFormats.problems(format(numbering), "numbering", listOfNotNull(unit))
    }
}
