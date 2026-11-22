package com.memo.search.internal.decision

import com.memo.audit.AuditLog
import com.memo.common.authz.ForbiddenException
import com.memo.common.authz.Permission
import com.memo.forum.ForumRef
import com.memo.forum.OutcomeKind
import com.memo.minutes.ResolutionCheck
import com.memo.minutes.Resolutions
import com.memo.search.DecisionCursor
import com.memo.search.DecisionFlag
import com.memo.search.DecisionPage
import com.memo.search.DecisionQuery
import com.memo.search.DecisionSort
import com.memo.search.RegisterLevel
import com.memo.search.RegisterRow
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

private const val SPREADSHEET_ROWS = 20_000
private const val PDF_ROWS = 2_000

/** The register's export formats (design E08 §1.5), with the most rows each holds. */
enum class RegisterExportFormat(
    val maxRows: Int,
) {
    /** A spreadsheet for sorting and checking: Decisions, About this export, Number check. */
    XLSX(SPREADSHEET_ROWS),

    /** Landscape pages for filing: the about block, a table per committee in date order, the number check. */
    PDF(PDF_ROWS),
}

/**
 * The body of `POST /decision-register/export` (design E08 §1.5, flow AT): the format and the search's filters and order
 * (`POST /decision-register/search` without its cursor and page size). A body, so that words stay out of URLs and logs.
 */
data class DecisionExportRequest(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val format: RegisterExportFormat,
    val text: String? = null,
    /** An officer who is not oversight exports the committees they are an officer of: none given means all of those. */
    val forumIds: List<UUID>? = null,
    val forumTypeIds: List<UUID>? = null,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val outcomes: List<OutcomeKind>? = null,
    val resolutionsOnly: Boolean? = null,
    val states: List<RegisterState>? = null,
    val memoTypeIds: List<UUID>? = null,
    val flags: List<DecisionFlag>? = null,
    val sort: DecisionSort? = null,
) {
    fun search(): DecisionSearchRequest =
        DecisionSearchRequest(text, forumIds, forumTypeIds, from, to, outcomes, resolutionsOnly, states, memoTypeIds, flags, sort)
}

/** More rows match than [format] holds: 422 `TOO_MANY_ROWS` with the count ([moreThan]: at least that many). */
class TooManyRowsException(
    val format: RegisterExportFormat,
    val rows: Int,
    val moreThan: Boolean,
) : RuntimeException("too many rows for ${format.name}: ${if (moreThan) "more than " else ""}$rows")

/** One void number of a period in the number check: its reason only when the viewer sees its decision in full. */
data class VoidNumber(
    val number: String,
    val voidedAt: Instant?,
    val reason: String?,
    /** Why the reason is not shown: the decision is a restricted line here, or not in this export. */
    val reasonWithheld: String?,
)

/** E06's register check of one committee's period found in the export, with its void numbers. */
data class NumberCheck(
    val forumId: UUID,
    val committee: String,
    val period: String,
    /** Null when the committee has no counter for the period (a number imported from elsewhere). */
    val check: ResolutionCheck?,
    val voids: List<VoidNumber>,
)

/** A row of the export with the names it shows. */
data class ExportRow(
    val row: RegisterRow,
    val committee: String,
    val memoType: String?,
)

/**
 * What an export holds, read in one transaction and audited there, before the file is written (design E08 §1.5): who and
 * when, the filters in words, the rows as the viewer sees them in the order asked, and the number check.
 */
data class RegisterExport(
    val format: RegisterExportFormat,
    val organisation: String,
    val producedBy: String,
    val producedAt: Instant,
    val zone: ZoneId,
    val today: LocalDate,
    val filters: List<Pair<String, String>>,
    val rows: List<ExportRow>,
    val checks: List<NumberCheck>,
    /** The app's address for this organisation, for each row's link to its meeting. */
    val appUrl: String,
)

/**
 * The register's export for auditors (ADR-0069, design E08 §1.5): a committee's officers (their committees), `forums.manage`
 * and `forum.read` (every committee); members read the register but do not export it (403). The rows are the search's,
 * under the one rule with the exporter's parameters (restricted lines with their line's fields only), read in pages of
 * 1,000 in the request's transaction, at most the format's maximum (more is 422 `TOO_MANY_ROWS`). Each export is audited
 * (`search.decisions.exported`: the format, the words as a hash, the filters, the order, the counts and `rowsSha256`)
 * before the first byte is written. Inside a tenant transaction.
 */
@Service
class DecisionExports(
    private val access: DecisionRegisterAccess,
    private val register: DecisionRegister,
    private val describer: ExportDescriber,
    private val resolutions: Resolutions,
    private val audit: AuditLog,
) {
    /** Who exports, with the filters checked: 403 without a register or for a member, 422 for the filters. */
    fun exporter(request: DecisionExportRequest): UUID = prepare(request).viewer.userId

    /** The export of [request] for the actor, audited; 403, 422 as [exporter], and 422 `TOO_MANY_ROWS`. */
    fun export(request: DecisionExportRequest): RegisterExport {
        val prepared = prepare(request)
        val rows = read(prepared, request.format)
        val export = describer.describe(request.format, prepared.viewer, prepared.asked, prepared.committees, rows, checks(rows, prepared))
        audit.record(
            "search.decisions.exported",
            DecisionRegister.OBJECT_TYPE,
            prepared.viewer.userId.toString(),
            after =
                (
                    prepared.asked.words() +
                        mapOf(
                            "format" to request.format.name,
                            "filters" to prepared.asked.filters(),
                            "sort" to prepared.asked.sort.name,
                            "rows" to rows.size,
                            "lines" to rows.count { it.level == RegisterLevel.LINE },
                            "rowsSha256" to rowsSha256(rows),
                        )
                ).filterValues { it != null },
        )
        return export
    }

    /** The viewer, their committees and the search as it is exported (an officer's: their officer committees only). */
    private fun prepare(request: DecisionExportRequest): Prepared {
        val viewer = access.parameters() ?: throw register.noScope()
        val scope = viewer.scope
        if (!scope.all && scope.officerForums.isEmpty()) {
            throw ForbiddenException(Permission.FORUM_READ, detail = "Members read the register; its officers and oversight export it.")
        }
        val within = if (scope.all) null else scope.officerForums
        val (committees, asked) = register.checked(viewer, request.search(), within)
        val applied = if (within == null || asked.forumIds.isNotEmpty()) asked else asked.copy(forumIds = within.sorted())
        return Prepared(viewer, committees, applied)
    }

    /**
     * Every row of the search, in pages of [DecisionQuery.MAX_PAGE]; 422 `TOO_MANY_ROWS` above [format]'s maximum, from the
     * first page's total when it shows it (exact to 10,000), else once the rows read pass the maximum. Words take up to
     * [DecisionQuery.MAX_CANDIDATES] candidates: a search whose candidates reach that cap is too many for either format.
     */
    private fun read(
        prepared: Prepared,
        format: RegisterExportFormat,
    ): List<RegisterRow> {
        val query = prepared.asked.query().copy(limit = DecisionQuery.MAX_PAGE, candidates = DecisionQuery.MAX_CANDIDATES)
        val rows = mutableListOf<RegisterRow>()
        var cursor: DecisionCursor? = null
        do {
            val page = access.find(prepared.viewer, query.copy(cursor = cursor))
            if (cursor == null) refuseAtOnce(page, format, prepared.asked.text.isNotEmpty())
            rows += page.rows
            if (rows.size > format.maxRows) throw TooManyRowsException(format, format.maxRows, moreThan = true)
            cursor = page.next
        } while (cursor != null)
        return rows
    }

    private fun refuseAtOnce(
        first: DecisionPage,
        format: RegisterExportFormat,
        words: Boolean,
    ) {
        when {
            first.capped && (words || format.maxRows <= COUNTED) -> throw TooManyRowsException(format, COUNTED, moreThan = true)
            first.total > format.maxRows -> throw TooManyRowsException(format, first.total, moreThan = false)
        }
    }

    /**
     * E06's check of each committee and period whose numbers are in [rows], first by committee, then by period, with each
     * void's reason only when its decision is a FULL row here (a line's or an unexported decision's reason is withheld).
     */
    private fun checks(
        rows: List<RegisterRow>,
        prepared: Prepared,
    ): List<NumberCheck> {
        val levels = rows.associate { it.decisionId to it.level }
        return rows
            .mapNotNull { row -> row.numberPeriod?.takeIf { row.resolutionNumber != null }?.let { row.forumId to it } }
            .distinct()
            .map { (forumId, period) ->
                val voids =
                    resolutions.voids(forumId, period).map { void ->
                        val level = levels[void.decisionId]
                        VoidNumber(
                            number = void.number,
                            voidedAt = void.voidedAt,
                            reason = void.voidReason.takeIf { level == RegisterLevel.FULL },
                            reasonWithheld =
                                when (level) {
                                    RegisterLevel.FULL -> null
                                    RegisterLevel.LINE -> RegisterWords.RESTRICTED
                                    null -> RegisterWords.NOT_EXPORTED
                                },
                        )
                    }
                NumberCheck(forumId, prepared.committees[forumId]?.name.orEmpty(), period, resolutions.check(forumId, period), voids)
            }.sortedWith(compareBy<NumberCheck> { it.committee }.thenBy { it.period })
    }

    private data class Prepared(
        val viewer: RegisterViewer,
        val committees: Map<UUID, ForumRef>,
        val asked: DecisionRegister.Asked,
    )

    companion object {
        /** The search counts exactly up to this many rows. */
        private const val COUNTED = 10_000

        /**
         * The rows' hash (design E08 §1.5, threat model T3): SHA-256 over the sorted `decision_id:entry_seq` pairs, one a line,
         * so an export can later be matched to what the register held.
         */
        fun rowsSha256(rows: List<RegisterRow>): String =
            DecisionRegister.sha256(rows.map { "${it.decisionId}:${it.entrySeq}" }.sorted().joinToString("\n"))
    }
}
