package com.memo.search.internal.decision

import com.memo.common.context.ActorNames
import com.memo.forum.ForumPolicies
import com.memo.forum.ForumRef
import com.memo.memo.MemoSearchSource
import com.memo.org.OrgDirectory
import com.memo.search.DecisionFlag
import com.memo.search.DecisionSort
import com.memo.search.LineReason
import com.memo.search.RegisterLevel
import com.memo.search.RegisterRow
import com.memo.tenancy.TenancyProperties
import com.memo.tenancy.TenantRegistry
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * The words of the register's export (design E08 §1.5, flow AT), shared by the spreadsheet and the PDF so that both say the
 * same: outcomes, states, flags, the reason on a restricted line, dates. English only (Phase 4 decision).
 */
object RegisterWords {
    const val TITLE = "Register of committee decisions"
    const val RESTRICTED = "Restricted item"
    const val NOT_EXPORTED = "Not in this export"
    const val RULE = "Restricted items show their number only."
    const val RULE_DETAIL =
        "A restricted item is a decision you may not see in full: you declared an interest in it, or it is restricted to " +
            "named members, its title is hidden, or it is in a members-only section. Its line shows the committee, the " +
            "meeting, the item number and the resolution number with its state, and nothing of the decision itself."
    const val CHECK_DETAIL =
        "For each committee and numbering period in this export, every resolution number from the first to the last given " +
            "is accounted for once, in force or void. A void number is never given again; its reason is shown only where " +
            "you see its decision in full."

    private val DAY = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)
    private val MINUTE = DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale.ENGLISH)

    fun day(date: LocalDate): String = DAY.format(date)

    fun at(
        instant: Instant,
        zone: ZoneId,
    ): String = MINUTE.format(instant.atZone(zone))

    /** "APPROVED_WITH_CONDITIONS" as "Approved with conditions". */
    fun words(name: String): String = name.lowercase(Locale.ROOT).replace('_', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) }

    fun numberState(state: String?): String? =
        when (state) {
            "IN_FORCE" -> "In force"
            "VOID" -> "Void"
            else -> state
        }

    /** The line's title cell: "Restricted item", and why for this viewer. */
    fun line(reason: LineReason?): String =
        when (reason) {
            LineReason.RECUSED -> "$RESTRICTED: you declared an interest in this item"
            else -> "$RESTRICTED: restricted to named members"
        }

    /** The exception flags that hold on a FULL row, in words. */
    fun flags(
        row: RegisterRow,
        today: LocalDate,
    ): List<String> {
        if (row.level != RegisterLevel.FULL) return emptyList()
        val validUntil = row.validUntil
        return listOfNotNull(
            "With conditions".takeIf { !row.conditions.isNullOrEmpty() },
            "With dissent".takeIf { row.hasDissent == true },
            "To be ratified".takeIf { row.ratificationRequired == true && row.ratifiedByDecisionId == null },
            "Corrected".takeIf { row.corrected == true },
            "Validity passed".takeIf { validUntil != null && validUntil < today },
        )
    }

    /** The conditions, numbered, one a line. */
    fun conditions(conditions: List<String>?): String? =
        conditions?.takeIf { it.isNotEmpty() }?.mapIndexed { i, c -> "${i + 1}. $c" }?.joinToString("\n")

    fun sort(sort: DecisionSort): String =
        when (sort) {
            DecisionSort.RELEVANCE -> "Best match first"
            DecisionSort.NEWEST -> "Newest first"
            DecisionSort.OLDEST -> "Oldest first"
            DecisionSort.NUMBER -> "Number order"
        }

    fun flag(flag: DecisionFlag): String = words(flag.name)

    /** A period as the check names it: "2026", "FY2026-27", or every year for a committee that never resets. */
    fun period(period: String): String = if (period == "ALL") "All years" else period
}

/**
 * Who, when and what of an export (design E08 §1.5): the exporter's name, the organisation, the time in UTC and the tenant's
 * zone, the filters in words (committee, type and memo type names, dates, outcomes, states, flags, the order) and the
 * memo types' names of the rows. Inside the export's tenant transaction.
 */
@Component
class ExportDescriber(
    private val names: ActorNames,
    private val policies: ForumPolicies,
    private val memos: MemoSearchSource,
    private val org: OrgDirectory,
    private val registry: TenantRegistry,
    private val tenancy: TenancyProperties,
    private val clock: Clock,
) {
    @Suppress("LongParameterList")
    internal fun describe(
        format: RegisterExportFormat,
        viewer: RegisterViewer,
        asked: DecisionRegister.Asked,
        committees: Map<UUID, ForumRef>,
        rows: List<RegisterRow>,
        checks: List<NumberCheck>,
    ): RegisterExport {
        val tenant = registry.current()
        val types = memos.typeNames((rows.mapNotNull { it.memoTypeId } + asked.memoTypeIds).toSet())
        return RegisterExport(
            format = format,
            organisation = tenant?.name.orEmpty(),
            producedBy = names.names(setOf(viewer.userId))[viewer.userId].orEmpty(),
            producedAt = clock.instant(),
            zone = org.zone(),
            today = org.today(),
            filters = filters(asked, committees, types),
            rows = rows.map { ExportRow(it, committees[it.forumId]?.name.orEmpty(), it.memoTypeId?.let(types::get)) },
            checks = checks,
            appUrl = tenant?.let { tenancy.publicUrl(it.slug) }.orEmpty(),
        )
    }

    private fun filters(
        asked: DecisionRegister.Asked,
        committees: Map<UUID, ForumRef>,
        types: Map<UUID, String>,
    ): List<Pair<String, String>> {
        val from = asked.from
        val to = asked.to
        val dates =
            when {
                from != null && to != null -> "${RegisterWords.day(from)} to ${RegisterWords.day(to)}"
                from != null -> "From ${RegisterWords.day(from)}"
                to != null -> "Until ${RegisterWords.day(to)}"
                else -> "Any"
            }
        return listOfNotNull(
            ("Words" to asked.text).takeIf { asked.text.isNotEmpty() },
            "Committees" to
                asked.forumIds
                    .map { committees[it]?.name.orEmpty() }
                    .sorted()
                    .joinToString(", ")
                    .ifEmpty { "Every committee in your register" },
            joined("Committee types", asked.forumTypeIds.map { policies.published(it)?.name.orEmpty() }),
            "Meeting dates" to dates,
            joined("Outcomes", asked.outcomes.map { RegisterWords.words(it.name) }),
            ("Resolutions only" to "Yes").takeIf { asked.resolutionsOnly },
            joined("States", asked.states.map { RegisterWords.words(it.name) }),
            joined("Memo types", asked.memoTypeIds.map { types[it].orEmpty() }),
            joined("Exceptions", asked.flags.map(RegisterWords::flag)),
            "Order" to RegisterWords.sort(asked.sort),
        )
    }

    private fun joined(
        label: String,
        values: List<String>,
    ): Pair<String, String>? = values.takeIf { it.isNotEmpty() }?.let { label to it.sorted().joinToString(", ") }
}
