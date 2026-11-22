package com.memo.search.internal.decision

import com.memo.common.tenant.TenantDsl
import com.memo.jooq.routines.references.searchPutDecision
import com.memo.jooq.routines.references.searchPutDecisionItems
import com.memo.jooq.routines.references.searchPutDecisionsFinal
import com.memo.jooq.tables.references.SEARCH_DECISION_UNINDEXED
import org.jooq.JSONB
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Writes the decision register (ADR-0069) through the search functions, the only way the application role reaches
 * `search_decision`. Every method runs inside a tenant transaction: the rows move with their source's transaction.
 */
@Component
class DecisionIndex(
    private val source: DecisionSource,
    private val tenantDsl: TenantDsl,
    private val json: JsonMapper,
    private val clock: Clock,
) {
    /** Writes [decisionId]'s row as it is now; false when there is no such decision. */
    fun put(decisionId: UUID): Boolean = source.row(decisionId)?.also(::write) != null

    /** [meetingId]'s minutes were approved at [at]: its rows are FINAL. Returns how many rows changed. */
    fun putFinal(
        meetingId: UUID,
        at: Instant,
    ): Int = searchPutDecisionsFinal(tenantDsl.current().configuration(), tenant(), meetingId, utc(at), now()) ?: 0

    /**
     * [meetingId]'s meeting columns and its decided items' columns from the latest revision (a new revision, a meeting
     * changed). Returns how many rows changed; 0 when the meeting has no decision.
     */
    fun putItems(meetingId: UUID): Int {
        val columns = source.meetingColumns(meetingId) ?: return 0
        val items =
            columns.items.map { (item, rank) ->
                mapOf(
                    "item" to item.itemId,
                    "parent" to item.parentId,
                    "number" to item.number,
                    "title" to item.title,
                    "rank" to rank,
                    "restrictedTo" to item.restrictedTo,
                    "titleHidden" to item.titleHidden,
                    "membersOnly" to item.membersOnly,
                )
            }
        return searchPutDecisionItems(
            tenantDsl.current().configuration(),
            tenant(),
            meetingId,
            columns.meeting.label,
            columns.meeting.date,
            JSONB.jsonb(json.writeValueAsString(items)),
            now(),
        ) ?: 0
    }

    /** Up to [limit] decisions of the tenant whose row is missing or behind, in id order (the backfill's work list). */
    fun unindexed(limit: Int): List<UUID> =
        tenantDsl
            .current()
            .select(SEARCH_DECISION_UNINDEXED.SEARCH_DECISION_UNINDEXED_)
            .from(SEARCH_DECISION_UNINDEXED.call(tenant(), limit))
            .fetch(SEARCH_DECISION_UNINDEXED.SEARCH_DECISION_UNINDEXED_)
            .filterNotNull()

    private fun write(row: DecisionRow) {
        searchPutDecision(
            tenantDsl.current().configuration(),
            tenant(),
            row.decisionId,
            row.forumId,
            row.forumTypeId,
            row.meetingId,
            row.meetingLabel,
            row.meetingDate,
            row.itemId,
            row.itemParentId,
            row.itemNumber,
            row.classificationRank,
            row.itemRestrictedTo?.toTypedArray<UUID?>(),
            row.itemTitleHidden,
            row.itemMembersOnly,
            row.title,
            row.outcomeKind,
            row.outcomeLabel,
            row.isResolution,
            row.resolutionId,
            row.resolutionNumber,
            row.resolutionState,
            row.numberPeriod,
            row.numberSequence,
            row.refNorm,
            row.refTail,
            row.state,
            utc(row.decidedAt),
            row.finalAt?.let(::utc),
            row.entrySeq,
            row.text,
            row.conditions.toTypedArray<String?>(),
            row.validUntil,
            row.hasDissent,
            row.voteMode,
            row.ratificationRequired,
            row.ratifiedBy,
            row.submissionId,
            row.memoId,
            row.memoTypeId,
            row.memoNumber,
            row.memoRefNorm,
            row.chainSubmittedAt?.let(::utc),
            row.priorDeferrals,
            now(),
        )
    }

    private fun utc(at: Instant): OffsetDateTime = at.atOffset(ZoneOffset.UTC)

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    private fun tenant(): UUID = tenantDsl.tenant().value
}
