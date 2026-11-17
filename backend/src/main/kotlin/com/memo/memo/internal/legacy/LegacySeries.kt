package com.memo.memo.internal.legacy

import com.memo.memo.LiveSeries
import com.memo.memo.LiveSeriesReset
import com.memo.memo.internal.FinancialYearStyle
import com.memo.memo.internal.MemoNumberStore
import com.memo.memo.internal.MemoTypeVersionRow
import com.memo.memo.internal.MemoTypeVersionStore
import com.memo.memo.internal.NumberCountPer
import com.memo.memo.internal.NumberCounter
import com.memo.memo.internal.NumberFormats
import com.memo.memo.internal.NumberPart
import com.memo.memo.internal.NumberPartKind
import com.memo.memo.internal.Numbering
import com.memo.org.OrgDirectory
import com.memo.org.OrgUnitDirectory
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * The number series of every published memo-type version, active or not (a type can be switched on again), as
 * [LiveSeries] (ADR-0044, design E06 §5.2): each format as a pattern, the period counted in today (the tenant's time zone,
 * as numbering uses it) and the counters of its series. Reads only, inside a tenant transaction.
 */
@Component
class LegacySeries(
    private val versions: MemoTypeVersionStore,
    private val numbers: MemoNumberStore,
    private val units: OrgUnitDirectory,
    private val directory: OrgDirectory,
) {
    fun all(): List<LiveSeries> {
        val today = directory.today()
        val counters = numbers.counters().groupBy { it.key.series }
        val unitScopes by lazy { units.units(units.allUnits()).values.associate { it.code.uppercase() to it.id.toString() } }
        return versions.publishedAll().map { version ->
            val numbering = version.settings.numbering
            live(version, today, counters[numbering.series].orEmpty(), unitScopes.takeIf { numbering.countPer == NumberCountPer.UNIT })
        }
    }

    private fun live(
        version: MemoTypeVersionRow,
        today: LocalDate,
        counters: List<NumberCounter>,
        unitScopes: Map<String, String>?,
    ): LiveSeries {
        val numbering = version.settings.numbering
        return LiveSeries(
            series = numbering.series,
            typeId = version.typeId,
            format = NumberFormats.skeleton(numbering, version.settings.code),
            pattern = pattern(numbering, version.settings.code),
            reset = LiveSeriesReset.valueOf(numbering.reset.name),
            fyStartMonth = numbering.fyStartMonth,
            currentPeriod = NumberFormats.period(numbering, today),
            lastNumbers = counters.associate { "${it.key.period}/${it.key.scope}" to it.lastNumber },
            unitScopes = unitScopes,
        )
    }

    companion object {
        /** The whole number as [numbering] writes it for a type with [typeCode]; the first part of each kind is a named group. */
        fun pattern(
            numbering: Numbering,
            typeCode: String,
        ): Regex {
            val named = mutableSetOf<String>()

            fun group(
                name: String,
                body: String,
            ) = if (named.add(name)) "(?<$name>$body)" else "(?:$body)"
            val body = numbering.parts.joinToString("") { part -> part(part, typeCode, ::group) }
            return Regex(body, RegexOption.IGNORE_CASE)
        }

        private fun part(
            part: NumberPart,
            typeCode: String,
            group: (String, String) -> String,
        ): String =
            when (part.kind) {
                NumberPartKind.TEXT -> Regex.escape(part.text.orEmpty())
                NumberPartKind.TYPE_CODE -> Regex.escape(typeCode)
                NumberPartKind.UNIT_CODE -> group(LiveSeries.UNIT, "[A-Za-z0-9][A-Za-z0-9._-]{0,29}")
                NumberPartKind.YEAR -> group(LiveSeries.YEAR, "[0-9]{${part.digits ?: FULL_YEAR}}")
                NumberPartKind.FINANCIAL_YEAR -> financialYear(part, group)
                NumberPartKind.SEQUENCE -> group(LiveSeries.SEQUENCE, "[0-9]{${part.digits ?: 1},}")
            }

        private fun financialYear(
            part: NumberPart,
            group: (String, String) -> String,
        ): String =
            if (part.style == FinancialYearStyle.SHORT) {
                "FY" + group(LiveSeries.FY_SHORT, "[0-9]{2}")
            } else {
                group(LiveSeries.FY, "[0-9]{4}(?:-[0-9]{2})?")
            }

        private const val FULL_YEAR = 4
    }
}
