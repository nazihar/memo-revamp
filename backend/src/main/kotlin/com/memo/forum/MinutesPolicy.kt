package com.memo.forum

import com.memo.common.numbering.FinancialYearStyle
import com.memo.common.numbering.FormatPart
import com.memo.common.numbering.NumberFormat
import com.memo.common.numbering.NumberFormats
import com.memo.common.numbering.NumberReset
import com.memo.common.web.FieldProblem
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDate
import java.util.UUID

/** How a committee's minutes are approved (ADR-0066). */
enum class MinutesApproval {
    /** At the committee's next meeting, as its "Minutes of the previous meeting" item; then the chair confirms them. */
    AT_NEXT_MEETING,

    /** By circulation: members have a few working days to comment, then the chair confirms them; noted at the next meeting. */
    BY_CIRCULATION,
}

/** When a memo waiting for the committee moves on after its decision (ADR-0065). */
enum class MemoResumes {
    /** When the meeting is marked as held; a decision recorded after that at once. */
    AT_MEETING_END,

    /** When the meeting's minutes are approved. */
    ON_MINUTES_APPROVAL,
}

/** What a part of a resolution number is (ADR-0065). */
enum class ResolutionPartKind {
    /** Fixed text, including separators. */
    TEXT,

    /** The committee's code, for example `BRC`. */
    COMMITTEE_CODE,

    /** The calendar year of the meeting: 2 or 4 digits. */
    YEAR,

    /** The financial year of the meeting, as `2026-27` or `FY27`. */
    FINANCIAL_YEAR,

    /** The meeting's number in the committee's series (the 3 of "BRC 3/2026"). */
    MEETING_NUMBER,

    /** The resolution's sequence within the committee and period, padded to a minimum of digits. */
    SEQUENCE,
}

data class ResolutionNumberPart(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val kind: ResolutionPartKind,
    /** TEXT: the text. */
    val text: String? = null,
    /** YEAR: 2 or 4; SEQUENCE: the minimum number of digits, 1 to 9. */
    val digits: Int? = null,
    /** FINANCIAL_YEAR: how it is written. */
    val style: FinancialYearStyle? = null,
)

/**
 * How a committee type numbers its resolutions (ADR-0065): the parts, when the sequence starts again (the period is the
 * meeting's date in its zone), and the month the financial year starts in. Each committee has its own counter per period.
 */
data class ResolutionNumbering(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val parts: List<ResolutionNumberPart> = DEFAULT_PARTS,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val reset: NumberReset = NumberReset.YEARLY,
    /** The month the financial year starts in, 1 to 12. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val fyStartMonth: Int = DEFAULT_FY_START,
) {
    companion object {
        const val DEFAULT_FY_START = 7

        /** `BRC/2026/014` (ADR-0065, approved by the owner on 2026-11-19). */
        val DEFAULT_PARTS =
            listOf(
                ResolutionNumberPart(ResolutionPartKind.COMMITTEE_CODE),
                ResolutionNumberPart(ResolutionPartKind.TEXT, text = "/"),
                ResolutionNumberPart(ResolutionPartKind.YEAR, digits = 4),
                ResolutionNumberPart(ResolutionPartKind.TEXT, text = "/"),
                ResolutionNumberPart(ResolutionPartKind.SEQUENCE, digits = 3),
            )
    }
}

/**
 * A committee type's minutes policy (ADR-0065, ADR-0066, design E06 §1.8), part of each type version and reviewed with
 * it; pinned with the version on each meeting and copied onto the meeting's minutes when they are created. A version
 * stored before it existed reads as the defaults.
 */
data class MinutesPolicy(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val approval: MinutesApproval = MinutesApproval.AT_NEXT_MEETING,
    /** Working days members have to comment on circulated minutes, 1 to 20. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val circulationWorkingDays: Int = DEFAULT_CIRCULATION_DAYS,
    /** Working days after the meeting before the secretaries are reminded that the minutes are not circulated, 1 to 30. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val draftWorkingDays: Int = DEFAULT_DRAFT_DAYS,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val memoResumes: MemoResumes = MemoResumes.AT_MEETING_END,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val resolutionNumbering: ResolutionNumbering = ResolutionNumbering(),
    /** An uploaded minutes template (purpose `forum.minutes-template`); null: the built-in template. */
    val templateFileId: UUID? = null,
) {
    companion object {
        /** CGI's examples run from two days to a week; SS-1 gives seven days (research E06 §6.2). */
        const val DEFAULT_CIRCULATION_DAYS = 5
        const val DEFAULT_DRAFT_DAYS = 10
        const val MAX_CIRCULATION_DAYS = 20
        const val MAX_DRAFT_DAYS = 30
    }
}

/**
 * Resolution numbers (ADR-0065) on the shared format code ([NumberFormats]): the committee's code is written out, the
 * meeting's number varies. Pure; the minutes module takes the numbers, each committee from its own counter.
 */
object ResolutionFormats {
    const val COMMITTEE = "committee"
    const val MEETING = "meeting"

    /** A committee's code: 1 to 10 characters (ADR-0056). */
    const val COMMITTEE_CODE_MAX = 10

    /** A meeting's number in its series. */
    const val MEETING_NUMBER_MAX = 6

    /** The sequence an example shows ("BRC/2026/014", flow AI). */
    const val EXAMPLE_SEQUENCE = 14L

    fun format(numbering: ResolutionNumbering): NumberFormat =
        NumberFormat(
            numbering.parts.map { part ->
                when (part.kind) {
                    ResolutionPartKind.TEXT -> FormatPart.Text(part.text)
                    ResolutionPartKind.COMMITTEE_CODE -> FormatPart.Code(COMMITTEE, varies = false, maxLength = COMMITTEE_CODE_MAX)
                    ResolutionPartKind.MEETING_NUMBER -> FormatPart.Code(MEETING, varies = true, maxLength = MEETING_NUMBER_MAX)
                    ResolutionPartKind.YEAR -> FormatPart.Year(part.digits)
                    ResolutionPartKind.FINANCIAL_YEAR -> FormatPart.FinancialYear(part.style)
                    ResolutionPartKind.SEQUENCE -> FormatPart.Sequence(part.digits)
                }
            },
            numbering.reset,
            numbering.fyStartMonth,
        )

    /** The counter period of a meeting held on [date]: `ALL`, `2026` or `FY2026-27`. */
    fun period(
        numbering: ResolutionNumbering,
        date: LocalDate,
    ): String = NumberFormats.period(format(numbering), date)

    /** Resolution [sequence] of the committee [committeeCode] at its meeting number [meetingNumber], held on [date]. */
    fun render(
        numbering: ResolutionNumbering,
        committeeCode: String,
        meetingNumber: Int?,
        date: LocalDate,
        sequence: Long,
    ): String =
        NumberFormats.render(format(numbering), date, sequence) { code ->
            if (code == COMMITTEE) committeeCode else checkNotNull(meetingNumber) { "the number needs the meeting's number" }.toString()
        }

    /** What resolution numbers of [committeeCode] look like apart from what varies (two formats overlap when equal). */
    fun skeleton(
        numbering: ResolutionNumbering,
        committeeCode: String,
    ): String = NumberFormats.skeleton(format(numbering)) { committeeCode }

    /**
     * What is wrong with [numbering], each problem under [at]. Resolution numbers are unique in the tenant, and each
     * committee counts on its own, so the committee's code must be in the number.
     */
    fun problems(
        numbering: ResolutionNumbering,
        at: String,
    ): List<FieldProblem> {
        val code =
            FieldProblem("$at/parts", "COMMITTEE_CODE_MISSING", "The number needs the committee's code.").takeIf {
                numbering.parts.none { it.kind == ResolutionPartKind.COMMITTEE_CODE }
            }
        return NumberFormats.problems(format(numbering), at, listOfNotNull(code))
    }
}
