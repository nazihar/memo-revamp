package com.memo.search.internal.decision

import com.memo.common.authz.Permission
import com.memo.common.authz.Policy
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.tenant.TenantDsl
import com.memo.forum.ForumDirectory
import com.memo.forum.ForumRole
import com.memo.jooq.tables.records.SearchFindDecisionsRecord
import com.memo.jooq.tables.references.SEARCH_DECISION_STATS
import com.memo.jooq.tables.references.SEARCH_FIND_DECISIONS
import com.memo.meeting.Recusals
import com.memo.org.OrgDirectory
import com.memo.search.DecisionCursor
import com.memo.search.DecisionFacets
import com.memo.search.DecisionFact
import com.memo.search.DecisionPage
import com.memo.search.DecisionQuery
import com.memo.search.LineReason
import com.memo.search.RegisterLevel
import com.memo.search.RegisterRow
import com.memo.search.RegisterScope
import com.memo.search.internal.SearchFinder
import com.memo.search.internal.SearchReferences
import com.memo.search.internal.SearchTimeoutException
import org.jooq.JSONB
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.sql.SQLException
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * The viewer's parameters of the register's one rule (ADR-0069, design E08 §1.3), computed per request from live state in
 * the request's transaction, never taken from the request:
 *  - officer committees: a secretary, the chair, or the vice-chair when there is no chair in office today; all for a
 *    `forums.manage` holder (E06's officers, `MeetingAccess.officer`);
 *  - member committees: a seat in office today as chair, vice-chair or member (alternates, observers and standing
 *    invitees have no register);
 *  - `forum.read`; the clearance rank;
 *  - the items they may not see in full because of a declaration, from [Recusals.itemsOf] (their own, their member's as
 *    an alternate, awaiting a ruling, sub-items), so a declaration committed a moment ago applies at once.
 * The only caller of `search_find_decisions` and `search_decision_stats` (`DecisionRegisterArchTest`); the SQL there is
 * the only place the rule is applied to rows.
 */
@Component
class DecisionRegisterAccess(
    private val policy: Policy,
    private val directory: ForumDirectory,
    private val recusals: Recusals,
    private val org: OrgDirectory,
    private val tenantDsl: TenantDsl,
    private val json: JsonMapper,
) {
    /** The actor's committees in the register; null when they have none (former members, guests, memo readers, others). */
    fun scope(): RegisterScope? = viewer()?.let(::scopeOf)

    /** The actor's parameters, scope and recusals included; null without a scope. */
    fun parameters(): RegisterViewer? =
        viewer()?.let { user ->
            scopeOf(user)?.let { scope -> RegisterViewer(user, policy.clearanceRank(), scope, recusals.itemsOf(user)) }
        }

    /** One page of the register for the actor under the rule (design E08 §1.4); empty without a scope. */
    fun find(query: DecisionQuery): DecisionPage = parameters()?.let { find(it, query) } ?: EMPTY

    /** [query] for [viewer] (whose parameters [parameters] computed). */
    fun find(
        viewer: RegisterViewer,
        query: DecisionQuery,
    ): DecisionPage {
        val keys = SearchReferences.keys(query.text)
        val cursor = query.cursor
        val call =
            SEARCH_FIND_DECISIONS.call(
                tenantDsl.tenant().value,
                viewer.userId,
                viewer.clearanceRank,
                viewer.scope.manager,
                viewer.scope.officerForums.toTypedArray<UUID?>(),
                viewer.scope.memberForums.toTypedArray<UUID?>(),
                viewer.scope.reader,
                viewer.recused.toTypedArray<UUID?>(),
                query.text,
                query.prefix,
                keys?.first,
                keys?.second,
                query.forumIds.toTypedArray<UUID?>(),
                query.forumTypeIds.toTypedArray<UUID?>(),
                query.from,
                query.to,
                query.outcomes.toTypedArray<String?>(),
                query.resolutionsOnly,
                query.states.toTypedArray<String?>(),
                query.memoTypeIds.toTypedArray<UUID?>(),
                query.flags.map { it.name }.toTypedArray<String?>(),
                org.today(),
                query.sort.name,
                cursor?.meetingDate,
                cursor?.decidedAt?.atOffset(ZoneOffset.UTC),
                cursor?.score,
                cursor?.period,
                cursor?.sequence,
                cursor?.decisionId,
                query.limit + 1,
                query.candidates,
            )
        val records =
            timed {
                tenantDsl
                    .current()
                    .selectFrom(call)
                    .queryTimeout(SearchFinder.TIMEOUT_SECONDS)
                    .fetch()
            }
        val rows = records.filter { it.kind == "ROW" }.map(::row)
        val meta = records.firstOrNull { it.kind == "META" }
        val page = rows.take(query.limit)
        val next = page.lastOrNull()?.takeIf { rows.size > query.limit }?.let { cursorOf(it) }
        return DecisionPage(page, meta?.total ?: 0, meta?.capped == true, facets(meta?.facets), next)
    }

    /**
     * The decisions on SUBMISSION items the viewer sees in FULL (`search_decision_stats`, design E08 §2.2, M3 and M4) in
     * [forumIds] (empty: all in scope) with a meeting date from [from] to [to]; a LINE row is never among them. Empty
     * without a scope.
     */
    fun stats(
        forumIds: Set<UUID>,
        from: LocalDate,
        to: LocalDate,
    ): List<DecisionFact> {
        val viewer = parameters() ?: return emptyList()
        val call =
            SEARCH_DECISION_STATS.call(
                tenantDsl.tenant().value,
                viewer.userId,
                viewer.clearanceRank,
                viewer.scope.manager,
                viewer.scope.officerForums.toTypedArray<UUID?>(),
                viewer.scope.memberForums.toTypedArray<UUID?>(),
                viewer.scope.reader,
                viewer.recused.toTypedArray<UUID?>(),
                forumIds.toTypedArray<UUID?>(),
                from,
                to,
            )
        return timed {
            tenantDsl
                .current()
                .selectFrom(call)
                .queryTimeout(SearchFinder.TIMEOUT_SECONDS)
                .fetch {
                    DecisionFact(
                        forumId = checkNotNull(it.forumId),
                        memoTypeId = it.memoTypeId,
                        memoId = it.memoId,
                        submissionId = checkNotNull(it.submissionId),
                        meetingDate = checkNotNull(it.meetingDate),
                        decidedAt = checkNotNull(it.decidedAt).toInstant(),
                        chainSubmittedAt = it.chainSubmittedAt?.toInstant(),
                        outcomeKind = checkNotNull(it.outcomeKind),
                        priorDeferrals = it.priorDeferrals ?: 0,
                    )
                }
        }
    }

    private fun scopeOf(user: UUID): RegisterScope? {
        val manager = policy.allows(Permission.FORUMS_MANAGE)
        val reader = policy.allows(Permission.FORUM_READ)
        val roles = directory.rolesOf(user)
        val officers =
            roles
                .filter { (forum, role) ->
                    role == ForumRole.SECRETARY || role == ForumRole.CHAIR ||
                        (role == ForumRole.VICE_CHAIR && directory.membersOn(forum).none { it.role == ForumRole.CHAIR })
                }.keys
        val members = roles.filter { (forum, role) -> role in SEATS && forum !in officers }.keys
        val committees = officers.isNotEmpty() || members.isNotEmpty()
        return RegisterScope(manager || reader, officers, members, manager, reader).takeIf { manager || reader || committees }
    }

    private fun viewer(): UUID? = (ActorContext.current().actor as? Actor.User)?.userId

    private fun row(r: SearchFindDecisionsRecord): RegisterRow {
        val level = RegisterLevel.valueOf(checkNotNull(r.level))
        return RegisterRow(
            decisionId = checkNotNull(r.decisionId),
            level = level,
            reason = r.reason?.let(LineReason::valueOf),
            forumId = checkNotNull(r.forumId),
            forumTypeId = checkNotNull(r.forumTypeId),
            meetingId = checkNotNull(r.meetingId),
            meetingLabel = r.meetingLabel,
            meetingDate = checkNotNull(r.meetingDate),
            itemId = checkNotNull(r.itemId),
            itemNumber = r.itemNumber,
            state = checkNotNull(r.decisionState),
            resolutionId = r.resolutionId,
            resolutionNumber = r.resolutionNumber,
            resolutionState = r.resolutionState,
            numberPeriod = r.numberPeriod,
            numberSequence = r.numberSequence,
            decidedAt = checkNotNull(r.decidedAt).toInstant(),
            entrySeq = checkNotNull(r.entrySeq),
            score = r.score,
            title = r.title,
            outcomeKind = r.outcomeKind,
            outcomeLabel = r.outcomeLabel,
            isResolution = r.isResolution,
            text = r.decisionText,
            conditions = r.conditions?.filterNotNull(),
            validUntil = r.validUntil,
            hasDissent = r.hasDissent,
            voteMode = r.voteMode,
            ratificationRequired = r.ratificationRequired,
            ratifiedByDecisionId = r.ratifiedByDecisionId,
            corrected = r.corrected,
            finalAt = r.finalAt?.toInstant(),
            submissionId = r.submissionId,
            memoId = r.memoId,
            memoTypeId = r.memoTypeId,
            memoNumber = r.memoNumber,
        )
    }

    private fun cursorOf(row: RegisterRow) =
        DecisionCursor(row.decisionId, row.meetingDate, row.decidedAt, row.score, row.numberPeriod, row.numberSequence)

    private fun facets(value: JSONB?): DecisionFacets {
        val node = value?.data()?.let { json.readTree(it) } ?: return DecisionFacets(emptyMap(), emptyMap(), emptyMap())

        fun counts(name: String): Map<String, Long> =
            node
                .get(name)
                ?.properties()
                ?.associate { (key, count) -> key to count.asLong() }
                .orEmpty()
        return DecisionFacets(
            forums = counts("forums").mapKeys { UUID.fromString(it.key) },
            years = counts("years").mapKeys { it.key.toInt() },
            outcomes = counts("outcomes"),
        )
    }

    private fun <T> timed(query: () -> T): T =
        try {
            query()
        } catch (e: DataAccessException) {
            if (generateSequence<Throwable>(e) { it.cause }.any { (it as? SQLException)?.sqlState == QUERY_CANCELED }) {
                throw SearchTimeoutException()
            }
            throw e
        }

    private companion object {
        val SEATS = setOf(ForumRole.CHAIR, ForumRole.VICE_CHAIR, ForumRole.MEMBER)
        const val QUERY_CANCELED = "57014"
        val EMPTY = DecisionPage(emptyList(), 0, false, DecisionFacets(emptyMap(), emptyMap(), emptyMap()), null)
    }
}

/** A viewer's parameters of the rule, from live state (design E08 §1.3). */
data class RegisterViewer(
    val userId: UUID,
    val clearanceRank: Int,
    val scope: RegisterScope,
    /** The items they may not see in full because of a declaration, sub-items included. */
    val recused: Set<UUID>,
)
