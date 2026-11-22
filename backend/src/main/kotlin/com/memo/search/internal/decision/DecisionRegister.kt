package com.memo.search.internal.decision

import com.memo.audit.AuditLog
import com.memo.common.authz.ForbiddenException
import com.memo.common.authz.Permission
import com.memo.common.tenant.TenantDsl
import com.memo.common.web.FieldProblem
import com.memo.forum.ForumDirectory
import com.memo.forum.ForumPolicies
import com.memo.forum.ForumRef
import com.memo.forum.ForumStatus
import com.memo.forum.OutcomeKind
import com.memo.memo.MemoSearchSource
import com.memo.search.DecisionFlag
import com.memo.search.DecisionPage
import com.memo.search.DecisionQuery
import com.memo.search.DecisionSort
import com.memo.search.RegisterLevel
import com.memo.search.RegisterRow
import com.memo.search.RegisterScope
import com.memo.search.internal.SearchQueries
import com.memo.search.internal.SearchReferences
import com.memo.search.internal.SearchRejectedException
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID

/**
 * The decision register's API (ADR-0069, design E08 §1.3–§1.4, §3, §5): the viewer's scope, and a search under the one
 * rule. Inputs are checked here (the text's caps, the committees against the scope, the period, number order), the
 * cursor is sealed ([RegisterCursors]), and every search is audited (`search.decisions.performed`) with the words as a
 * hash only: what a person types may name a borrower or an item the audit's readers may not see. 403 without a scope.
 * Inside a tenant transaction.
 */
@Service
class DecisionRegister(
    private val access: DecisionRegisterAccess,
    private val directory: ForumDirectory,
    private val policies: ForumPolicies,
    private val memos: MemoSearchSource,
    private val cursors: RegisterCursors,
    private val tenantDsl: TenantDsl,
    private val audit: AuditLog,
) {
    /** What the actor may filter by; 403 when the register is not for them. */
    fun scope(): RegisterScopeView {
        val scope = access.scope() ?: throw noScope()
        val committees = committees(scope)
        val canExport = scope.manager || scope.reader || scope.officerForums.isNotEmpty()
        return RegisterScopeView(
            committees =
                committees.map { forum ->
                    RegisterCommitteeView(
                        id = forum.id,
                        name = forum.name,
                        shortName = forum.shortName,
                        typeId = forum.typeId,
                        officer = scope.manager || forum.id in scope.officerForums,
                        member = forum.id in scope.memberForums,
                        dissolved = forum.status == ForumStatus.DISSOLVED,
                    )
                },
            committeeTypes =
                committees
                    .map { it.typeId }
                    .distinct()
                    .map { NamedView(it, policies.published(it)?.name.orEmpty()) }
                    .sortedBy { it.name },
            outcomes = OutcomeKind.entries,
            states = RegisterState.entries,
            flags = DecisionFlag.entries,
            memoTypes = memos.allTypeNames().map { (id, name) -> NamedView(id, name) },
            canExport = canExport,
            canCheckNumbers = canExport,
        )
    }

    /** One page of the register for the actor (design E08 §1.4); 403 without a scope, 422 for inputs the rules refuse. */
    fun search(request: DecisionSearchRequest): DecisionSearchView {
        val viewer = access.parameters() ?: throw noScope()
        val (committees, asked) = checked(viewer, request)
        val binding = "${tenantDsl.tenant().value}|${viewer.userId}|${asked.fingerprint()}"
        val query = asked.query().copy(cursor = request.cursor?.let { cursors.open(it, binding) })
        val page = access.find(viewer, query)
        record(viewer.userId, asked, page)
        val types = memos.typeNames(page.rows.mapNotNull { it.memoTypeId }.toSet())
        return DecisionSearchView(
            rows = page.rows.map { view(it, committees, types) },
            total = page.total,
            capped = page.capped,
            facets =
                DecisionFacetsView(
                    forums =
                        page.facets.forums
                            .map { (id, n) -> ForumCountView(id, committees[id]?.name.orEmpty(), n) }
                            .sortedWith(compareByDescending<ForumCountView> { it.count }.thenBy { it.name }),
                    outcomes =
                        page.facets.outcomes
                            .map { (kind, n) -> OutcomeCountView(kind, n) }
                            .sortedWith(compareByDescending<OutcomeCountView> { it.count }.thenBy { it.outcome }),
                    years =
                        page.facets.years
                            .map { (year, n) -> YearCountView(year, n) }
                            .sortedByDescending { it.year },
                ),
            nextCursor = page.next?.let { cursors.seal(it, binding) },
        )
    }

    /**
     * [request] checked for [viewer] as a search is (the text's caps, the committees against [within], or else the viewer's
     * scope, the period, number order, the lists' sizes; 422 otherwise): the committees in scope by id, and the search.
     */
    internal fun checked(
        viewer: RegisterViewer,
        request: DecisionSearchRequest,
        within: Set<UUID>? = null,
    ): Pair<Map<UUID, ForumRef>, Asked> {
        val committees = committees(viewer.scope).associateBy { it.id }
        val asked = Asked.of(request)
        val problems = problems(asked, within ?: committees.keys)
        if (problems.isNotEmpty()) throw SearchRejectedException(problems)
        return committees to asked
    }

    /** The committees of [scope]: every one of the tenant for oversight, else where the viewer is an officer or a member. */
    private fun committees(scope: RegisterScope): List<ForumRef> =
        (if (scope.all) directory.all() else directory.forums(scope.officerForums + scope.memberForums).values.toList())
            .sortedBy { it.name }

    private fun problems(
        asked: Asked,
        scope: Set<UUID>,
    ): List<FieldProblem> {
        val from = asked.from
        val to = asked.to
        val values =
            mapOf(
                "forumIds" to asked.forumIds.size,
                "forumTypeIds" to asked.forumTypeIds.size,
                "memoTypeIds" to asked.memoTypeIds.size,
            )
        return listOfNotNull(
            when {
                asked.text.length > SearchQueries.MAX_CHARS -> {
                    FieldProblem("text", "TEXT_TOO_LONG", "At most ${SearchQueries.MAX_CHARS} characters.")
                }

                asked.terms.size > SearchQueries.MAX_TERMS -> {
                    FieldProblem("text", "TOO_MANY_TERMS", "At most ${SearchQueries.MAX_TERMS} words.")
                }

                else -> {
                    null
                }
            },
            FieldProblem("to", "RANGE", "The period ends before it starts or is longer than $MAX_YEARS years.")
                .takeIf { from != null && to != null && (to < from || from.plusYears(MAX_YEARS) < to) },
            FieldProblem("sort", "SORT_NEEDS_ONE_FORUM", "Number order is for one committee: choose one.")
                .takeIf { asked.sort == DecisionSort.NUMBER && asked.forumIds.size != 1 },
            FieldProblem("forumIds", "FORUM_OUT_OF_SCOPE", "Choose committees from your register's list.")
                .takeIf { asked.forumIds.any { it !in scope } },
            FieldProblem("limit", "RANGE", "A page is 1 to ${DecisionQuery.DEFAULT_PAGE} rows.")
                .takeIf { asked.limit !in 1..DecisionQuery.DEFAULT_PAGE },
        ) +
            values.filterValues { it > MAX_VALUES }.keys.map { FieldProblem(it, "TOO_MANY", "At most $MAX_VALUES values.") }
    }

    private fun record(
        viewer: UUID,
        asked: Asked,
        page: DecisionPage,
    ) {
        audit.record(
            "search.decisions.performed",
            OBJECT_TYPE,
            viewer.toString(),
            after =
                (
                    asked.words() +
                        mapOf(
                            "filters" to asked.filters(),
                            "sort" to asked.sort.name,
                            "nextPage" to asked.paging,
                            "total" to page.total,
                            "capped" to page.capped,
                            "decisions" to page.rows.map { it.decisionId.toString() },
                        )
                ).filterValues { it != null },
        )
    }

    /** [row] as the viewer may see it: a LINE carries the line's columns only (never its decision time or entry). */
    private fun view(
        row: RegisterRow,
        committees: Map<UUID, ForumRef>,
        types: Map<UUID, String>,
    ): DecisionRowView {
        val line =
            DecisionRowView(
                decisionId = row.decisionId,
                level = row.level,
                reason = row.reason.takeIf { row.level == RegisterLevel.LINE },
                forumId = row.forumId,
                forumName = committees[row.forumId]?.name.orEmpty(),
                meetingId = row.meetingId,
                meetingLabel = row.meetingLabel,
                meetingDate = row.meetingDate,
                itemId = row.itemId,
                itemNumber = row.itemNumber,
                state = row.state,
                resolutionId = row.resolutionId,
                resolutionNumber = row.resolutionNumber,
                resolutionState = row.resolutionState,
            )
        if (row.level != RegisterLevel.FULL) return line
        return line.copy(
            title = row.title,
            outcomeKind = row.outcomeKind,
            outcomeLabel = row.outcomeLabel,
            resolution = row.isResolution,
            text = row.text,
            conditions = row.conditions,
            validUntil = row.validUntil,
            hasDissent = row.hasDissent,
            voteMode = row.voteMode,
            ratificationRequired = row.ratificationRequired,
            ratifiedByDecisionId = row.ratifiedByDecisionId,
            corrected = row.corrected,
            decidedAt = row.decidedAt,
            finalAt = row.finalAt,
            submissionId = row.submissionId,
            memoId = row.memoId,
            memoTypeId = row.memoTypeId,
            memoTypeName = row.memoTypeId?.let { types[it] },
            memoNumber = row.memoNumber,
        )
    }

    internal fun noScope() =
        ForbiddenException(Permission.FORUM_READ, detail = "The decision register is for committee members, officers and oversight.")

    /** A search as asked, normalised: the words trimmed, the lists distinct, the order the one the function will apply. */
    internal data class Asked(
        val text: String,
        val forumIds: List<UUID>,
        val forumTypeIds: List<UUID>,
        val from: LocalDate?,
        val to: LocalDate?,
        val outcomes: List<OutcomeKind>,
        val resolutionsOnly: Boolean,
        val states: List<RegisterState>,
        val memoTypeIds: List<UUID>,
        val flags: List<DecisionFlag>,
        val sort: DecisionSort,
        val limit: Int,
        val paging: Boolean,
    ) {
        val terms: List<String> = SearchQueries.terms(text)
        val normal: String = terms.joinToString(" ").lowercase()

        /**
         * The function's query: a number-like text is looked up whole; otherwise the last word, when it is a plain word of
         * three or more letters or digits, matches as a prefix (design E08 §1.4), unless the words are a phrase.
         */
        fun query(): DecisionQuery {
            val last = terms.lastOrNull()
            val prefix =
                last?.takeIf {
                    SearchReferences.keys(text) == null && '"' !in text && PREFIXABLE.matches(it) && !it.equals("or", ignoreCase = true)
                }
            return DecisionQuery(
                text = if (prefix != null) terms.dropLast(1).joinToString(" ") else text,
                prefix = prefix,
                forumIds = forumIds.toSet(),
                forumTypeIds = forumTypeIds.toSet(),
                from = from,
                to = to,
                outcomes = outcomes.map { it.name }.toSet(),
                resolutionsOnly = resolutionsOnly,
                states = states.map { it.name }.toSet(),
                memoTypeIds = memoTypeIds.toSet(),
                flags = flags.toSet(),
                sort = sort,
                limit = limit,
            )
        }

        /**
         * The words for the audit, as a hash only (of the trimmed, lower-case text with single spaces): enough to check what
         * someone is said to have searched for, without the audit holding what they typed; their number; a number lookup.
         */
        fun words(): Map<String, Any?> =
            mapOf(
                "textSha256" to text.takeIf { it.isNotEmpty() }?.let { sha256(normal) },
                "terms" to terms.size,
                "numberLookup" to (text.isNotEmpty() && SearchReferences.keys(text) != null),
            )

        /** The filters for the audit: ids, dates and kinds only (no free text). */
        fun filters(): Map<String, Any> =
            mapOf(
                "forumIds" to forumIds.map(UUID::toString),
                "forumTypeIds" to forumTypeIds.map(UUID::toString),
                "from" to from?.toString(),
                "to" to to?.toString(),
                "outcomes" to outcomes.map { it.name },
                "resolutionsOnly" to resolutionsOnly.takeIf { it },
                "states" to states.map { it.name },
                "memoTypeIds" to memoTypeIds.map(UUID::toString),
                "flags" to flags.map { it.name },
            ).filterValues { it != null && it != emptyList<Any>() }.mapValues { (_, v) -> checkNotNull(v) }

        /** What a cursor is bound to: the same words, filters and order (sorted, so the lists' order does not matter). */
        fun fingerprint(): String =
            sha256(
                listOf(
                    normal,
                    forumIds.map(UUID::toString).sorted(),
                    forumTypeIds.map(UUID::toString).sorted(),
                    from,
                    to,
                    outcomes.map { it.name }.sorted(),
                    resolutionsOnly,
                    states.map { it.name }.sorted(),
                    memoTypeIds.map(UUID::toString).sorted(),
                    flags.map { it.name }.sorted(),
                    sort,
                ).joinToString("\u001f"),
            )

        companion object {
            fun of(request: DecisionSearchRequest): Asked {
                val text = request.text?.trim().orEmpty()
                val sort =
                    when (val asked = request.sort) {
                        null -> if (text.isEmpty()) DecisionSort.NEWEST else DecisionSort.RELEVANCE
                        DecisionSort.RELEVANCE -> if (text.isEmpty()) DecisionSort.NEWEST else asked
                        else -> asked
                    }
                return Asked(
                    text = text,
                    forumIds = request.forumIds.orEmpty().distinct(),
                    forumTypeIds = request.forumTypeIds.orEmpty().distinct(),
                    from = request.from,
                    to = request.to,
                    outcomes = request.outcomes.orEmpty().distinct(),
                    resolutionsOnly = request.resolutionsOnly == true,
                    states = request.states.orEmpty().distinct(),
                    memoTypeIds = request.memoTypeIds.orEmpty().distinct(),
                    flags = request.flags.orEmpty().distinct(),
                    sort = sort,
                    limit = request.limit ?: DecisionQuery.DEFAULT_PAGE,
                    paging = request.cursor != null,
                )
            }
        }
    }

    companion object {
        const val OBJECT_TYPE = "search"
        const val MAX_VALUES = 50
        const val MAX_YEARS = 25L
        private val PREFIXABLE = Regex("^[\\p{L}\\p{N}]{3,}$")

        fun sha256(text: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
