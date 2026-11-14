package com.memo.memo.internal

import com.memo.audit.AuditLog
import com.memo.common.authz.Policy
import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import com.memo.org.OrgDirectory
import com.memo.org.OrgUnitDirectory
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.UUID

/** A counter of a series as administrators see it. */
data class NumberCounterView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val period: String,
    /** Empty, or the unit counted for (a series counted per unit). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val scope: String,
    val scopeName: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val lastNumber: Long,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val startedByAdmin: Boolean,
)

/** A numbering series: the memo types numbering in it and its counters. */
data class NumberSeriesView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val series: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val types: List<Named>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val counters: List<NumberCounterView>,
)

/** A number in the register. Withdrawn, cancelled and discarded memos keep their numbers, which are void (ADR-0044). */
data class RegisterEntry(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val number: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val sequence: Long,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val memoId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val status: MemoStatus,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val void: Boolean,
    /** Only for memos the reader takes part in and is cleared for. */
    val subject: String?,
)

/** One page of a counter's register, with the completeness check: the numbers no memo has. */
data class NumberRegisterView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val series: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val period: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val scope: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val lastNumber: Long,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val entries: List<RegisterEntry>,
    /** The sequence to pass as `after` for the next page; null on the last page. */
    val next: Long?,
    /** Numbers given by the counter that no memo has, the first few; empty when the register is complete. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val missing: List<Long>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val missingCount: Long,
)

/** A preview of a numbering: sample numbers now and at the start of the next period, or what is wrong. */
data class NumberingPreview(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val samples: List<String>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val problems: List<FieldProblem>,
)

/**
 * Numbering for administrators and auditors (ADR-0044, design E01 §3): the series and their counters, the register of a
 * counter with its completeness check, a starting number for a counter not used yet, and previews. Inside a tenant
 * transaction; the controller checks permissions and the step-up.
 */
@Component
class MemoNumberingAdmin(
    private val numbers: MemoNumberStore,
    private val versions: MemoTypeVersionStore,
    private val units: OrgUnitDirectory,
    private val directory: OrgDirectory,
    private val policy: Policy,
    private val audit: AuditLog,
) {
    fun series(): List<NumberSeriesView> {
        val published = versions.publishedAll()
        val counters = numbers.counters()
        val names =
            units
                .units(
                    counters.mapNotNull { runCatching { UUID.fromString(it.key.scope) }.getOrNull() },
                ).mapValues { it.value.name }
        val all = (published.map { it.settings.numbering.series } + counters.map { it.key.series }).toSortedSet()
        return all.map { series ->
            NumberSeriesView(
                series,
                published.filter { it.settings.numbering.series == series }.map { Named(it.typeId, it.settings.name) }.sortedBy { it.name },
                counters.filter { it.key.series == series }.map { c ->
                    val unitName = runCatching { UUID.fromString(c.key.scope) }.getOrNull()?.let(names::get)
                    NumberCounterView(c.key.period, c.key.scope, unitName, c.lastNumber, c.startedByAdmin)
                },
            )
        }
    }

    fun register(
        key: NumberKey,
        after: Long,
        limit: Int,
        reader: UUID,
    ): NumberRegisterView {
        val counter = numbers.counters().firstOrNull { it.key == key } ?: throw MemoNotFoundException("numbering counter")
        val page = limit.coerceIn(1, MAX_PAGE)
        val entries = numbers.issued(key, after, page + 1, RegisterReader(reader, policy.clearanceRank()))
        val from = if (counter.startedByAdmin) numbers.first(key) ?: (counter.lastNumber + 1) else 1L
        val (missing, missingCount) = numbers.missing(key, from..counter.lastNumber, MAX_MISSING)
        return NumberRegisterView(
            key.series,
            key.period,
            key.scope,
            counter.lastNumber,
            entries.take(page).map { RegisterEntry(it.reference, it.sequence, it.memoId, it.status, it.status in VOID, it.subject) },
            entries.getOrNull(page)?.let { entries[page - 1].sequence },
            missing,
            missingCount,
        )
    }

    /**
     * Sets the first number of a counter not used yet (to continue a legacy series, ADR-0044): the series must be one a
     * type numbers in, the period and scope must fit how it counts, and nothing may have been numbered in it.
     */
    fun start(
        key: NumberKey,
        next: Long,
    ) {
        val numbering = versions.publishedAll().map { it.settings.numbering }.firstOrNull { it.series == key.series }
        val problems =
            listOfNotNull(
                FieldProblem("series", "UNKNOWN", "No memo type numbers in this series.").takeIf { numbering == null },
                FieldProblem("period", "FORMAT", "A period of this series: ${numbering?.reset?.let(::periodHint)}.").takeIf {
                    numbering != null && !periodFits(numbering, key.period)
                },
                FieldProblem("scope", "FORMAT", "A unit for a series counted per unit; empty otherwise.").takeIf {
                    numbering != null && !scopeFits(numbering, key.scope)
                },
                FieldProblem("next", "RANGE", "1 to $MAX_START.").takeIf { next !in 1..MAX_START },
            )
        if (problems.isNotEmpty()) throw MemoRejectedException(problems)
        refuseIf(numbers.first(key) != null || !numbers.start(key, next), "ALREADY_STARTED") {
            "This counter has given numbers already; its next number cannot be set."
        }
        audit.record(
            "memo.numbering.started",
            "memo.numbering",
            key.series,
            after = mapOf("period" to key.period, "scope" to key.scope, "next" to next),
        )
    }

    /** Sample numbers for [numbering] now and at the start of its next period, with [typeCode] and [unitCode]. */
    fun preview(
        numbering: Numbering,
        typeCode: String,
        unitCode: String?,
    ): NumberingPreview {
        val problems = NumberFormats.problems(numbering)
        if (problems.isNotEmpty()) return NumberingPreview(emptyList(), problems)
        val today = directory.today()
        val nextPeriod =
            when (numbering.reset) {
                NumberReset.NEVER -> {
                    today
                }

                NumberReset.YEARLY -> {
                    LocalDate.of(today.year + 1, 1, 1)
                }

                NumberReset.FINANCIAL_YEAR -> {
                    LocalDate.of(
                        NumberFormats.financialYear(numbering, today).first + 1,
                        numbering.fyStartMonth,
                        1,
                    )
                }
            }
        val unit = unitCode ?: SAMPLE_UNIT
        return NumberingPreview(
            listOf(
                NumberFormats.render(numbering, typeCode, unit, today, 1),
                NumberFormats.render(numbering, typeCode, unit, nextPeriod, 1),
            ),
            emptyList(),
        )
    }

    private fun periodFits(
        numbering: Numbering,
        period: String,
    ): Boolean =
        when (numbering.reset) {
            NumberReset.NEVER -> period == "ALL"
            NumberReset.YEARLY -> YEAR.matches(period)
            NumberReset.FINANCIAL_YEAR -> FY.matches(period)
        }

    private fun scopeFits(
        numbering: Numbering,
        scope: String,
    ): Boolean =
        if (numbering.countPer == NumberCountPer.UNIT) {
            runCatching { UUID.fromString(scope) }.getOrNull()?.let(directory::unitExists) == true
        } else {
            scope.isEmpty()
        }

    private fun periodHint(reset: NumberReset) =
        when (reset) {
            NumberReset.NEVER -> "ALL"
            NumberReset.YEARLY -> "a year such as 2026"
            NumberReset.FINANCIAL_YEAR -> "a financial year such as FY2026-27"
        }

    private companion object {
        const val MAX_PAGE = 200
        const val MAX_MISSING = 20
        const val MAX_START = 999_999_999L
        const val SAMPLE_UNIT = "UNIT"
        val VOID = setOf(MemoStatus.WITHDRAWN, MemoStatus.CANCELLED, MemoStatus.DISCARDED)
        val YEAR = Regex("^[0-9]{4}$")
        val FY = Regex("^FY[0-9]{4}-[0-9]{2}$")
    }
}
