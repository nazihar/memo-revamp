package com.memo.minutes.internal

import com.memo.audit.AuditLog
import com.memo.common.authz.ForbiddenException
import com.memo.common.authz.Permission
import com.memo.common.authz.Policy
import com.memo.common.numbering.NumberReset
import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import com.memo.forum.ForumDirectory
import com.memo.forum.ForumPolicies
import com.memo.forum.ForumRef
import com.memo.forum.ForumRole
import com.memo.forum.ResolutionNumbering
import com.memo.meeting.MeetingStatus
import com.memo.meeting.MeetingView
import com.memo.meeting.MeetingViewers
import com.memo.meeting.Meetings
import com.memo.memo.MemoBrief
import com.memo.memo.MemoReaders
import com.memo.minutes.ResolutionState
import com.memo.minutes.Resolutions
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Who reads what of the resolutions (design E06 §1.9, §4, flows AC1–AC2): the committee's register (its officers,
 * `forums.manage` and `forum.read`), each row in full only when the viewer sees its item's decision in full, else its
 * number, state, meeting and item number ("restricted"); one resolution for whoever sees its item's decision (in full, or
 * as their recused line) and for the readers of its memo (outcome, number, text, conditions); the register check; and the
 * starting number of a period, set once by `forums.manage`. The only reader of the resolution tables for viewers.
 */
@Component
class ResolutionAccess(
    private val store: ResolutionStore,
    private val counters: ResolutionCounters,
    private val reads: DecisionReads,
    private val resolutions: Resolutions,
    private val access: MinutesAccess,
    private val viewers: MeetingViewers,
    private val meetings: Meetings,
    private val directory: ForumDirectory,
    private val policies: ForumPolicies,
    private val memos: MemoReaders,
    private val policy: Policy,
    private val audit: AuditLog,
    private val minutes: MinutesStore,
) {
    /**
     * A page of [forumId]'s register for [period] (default: its latest), the latest number first; [before] is the cursor
     * (the sequence of the page's last row). 404 for someone who does not know the committee, 403 for one of its members.
     */
    fun register(
        forumId: UUID,
        period: String?,
        state: ResolutionState?,
        before: Long?,
        limit: Int,
    ): ResolutionRegisterResponse {
        val forum = requireRegister(forumId)
        val periods = counters.counters(forum.id).map { it.period }
        val shown = period ?: periods.maxOrNull()
        if (shown == null) return ResolutionRegisterResponse(forum.id, null, periods, emptyList(), null)
        val page = limit.coerceIn(1, MAX_PAGE)
        val rows = store.page(forum.id, shown, state, before, page + 1)
        val decisions =
            rows
                .map { it.decisionId }
                .distinct()
                .mapNotNull { reads.currentById(it) }
                .associateBy { it.decision.id }
        val views = rows.map { it.meetingId }.distinct().associateWith { viewers.find(it) }
        val full =
            rows
                .filter { r ->
                    val view = views[r.meetingId]
                    val decision = decisions[r.decisionId]
                    view != null && decision != null && access.sight(view, decision.decision.itemId) == DecisionSight.FULL
                }.map { it.id }
                .toSet()
        val memoBriefs = memos.readable(rows.filter { it.id in full }.mapNotNull { decisions[it.decisionId]?.decision?.memoId }.toSet())
        val entries = rows.take(page).map { r -> entry(r, views[r.meetingId], decisions[r.decisionId], r.id in full, memoBriefs) }
        val next = rows.getOrNull(page)?.let { rows[page - 1].sequence.toString() }
        return ResolutionRegisterResponse(forum.id, shown, periods, entries, next)
    }

    /**
     * A row of the register: in full when the viewer sees its [decision] in full ([full]), else the number, its state, the
     * meeting and the item's number ("restricted").
     */
    private fun entry(
        r: ResolutionRow,
        view: MeetingView?,
        decision: CurrentDecision?,
        full: Boolean,
        memoBriefs: Map<UUID, MemoBrief>,
    ): ResolutionEntryResponse {
        val meeting = view?.meeting ?: meetings.find(r.meetingId)
        val item = decision?.let { view?.agenda?.item(it.decision.itemId) }
        val line =
            ResolutionEntryResponse(
                id = r.id,
                number = r.number,
                sequence = r.sequence,
                state = r.state,
                numberedAt = r.numberedAt,
                meetingId = r.meetingId,
                meetingLabel = meeting?.label,
                meetingDate = meeting?.date(),
                itemNumber = item?.number,
                restricted = !full,
            )
        return decision?.takeIf { full }?.let { d ->
            line.copy(
                itemTitle = item?.title,
                outcome = d.entry.outcomeKind,
                outcomeLabel = d.entry.outcomeLabel,
                memo =
                    d.decision.memoId
                        ?.let { memoBriefs[it] }
                        ?.let { ResolutionMemo(it.id, it.reference) },
                voidedAt = r.voidedAt,
                voidReason = r.voidReason,
            )
        } ?: line
    }

    /** The register check of [forumId]'s [period] (default: its latest); 404 when it has no counter there. */
    fun check(
        forumId: UUID,
        period: String?,
    ): ResolutionCheckResponse {
        val forum = requireRegister(forumId)
        val shown = period ?: counters.counters(forum.id).maxOfOrNull { it.period } ?: throw MinutesNotFoundException("resolutions")
        val c = resolutions.check(forum.id, shown) ?: throw MinutesNotFoundException("resolutions")
        return ResolutionCheckResponse(
            c.forumId,
            c.period,
            c.firstNumber,
            c.lastNumber,
            c.inForce,
            c.void,
            c.missing,
            c.missingCount,
            c.duplicates,
            c.outOfRange,
            c.complete,
        )
    }

    /**
     * Resolution [resolutionId] as the actor may see it (design E06 §1.9): through its meeting, by the decision rule, or as
     * a reader of its memo; 404 otherwise.
     */
    fun resolution(resolutionId: UUID): ResolutionResponse {
        val row = store.find(resolutionId)
        val decision = row?.let { reads.currentById(it.decisionId) }
        if (row == null || decision == null) throw MinutesNotFoundException("resolution")
        val view = viewers.find(row.meetingId)
        val sight = view?.let { access.sight(it, decision.decision.itemId) } ?: DecisionSight.NONE
        val memoId = decision.decision.memoId
        val memo = memoId?.let { memos.readable(setOf(it))[it] }
        if (sight == DecisionSight.NONE && memo == null) throw MinutesNotFoundException("resolution")
        val meeting = view?.meeting ?: checkNotNull(meetings.find(row.meetingId))
        val forum = checkNotNull(directory.forum(row.forumId))
        val item = view?.agenda?.item(decision.decision.itemId)?.takeIf { sight != DecisionSight.NONE }
        val e = decision.entry
        val full = sight == DecisionSight.FULL
        return ResolutionResponse(
            id = row.id,
            number = row.number,
            state = row.state,
            numberedAt = row.numberedAt,
            forumId = forum.id,
            forumName = forum.name,
            meetingId = meeting.id,
            meetingLabel = meeting.label,
            meetingDate = meeting.date(),
            decisionId = decision.decision.id,
            itemNumber = item?.number,
            itemTitle = item?.takeIf { !it.titleHidden || full }?.title,
            outcome = e.outcomeKind,
            outcomeLabel = e.outcomeLabel,
            text = e.text,
            conditions = e.conditions,
            validUntil = e.validUntil,
            releasedAt = decision.decision.releasedAt,
            memo = memo?.let { ResolutionMemo(it.id, it.reference) },
            viewerRecused = view != null && decision.decision.itemId in view.recused,
            full = full,
            voidedAt = row.voidedAt,
            voidReason = row.voidReason.takeIf { full },
            minutesApprovedAt = minutes.find(row.meetingId)?.takeIf { it.state == MinutesStage.APPROVED }?.approvedAt,
            mayIssueExtract = mayIssueExtract(row, decision, meeting, view),
        )
    }

    /**
     * Sets where [forumId]'s numbers start in [period] (go-live, ADR-0044): `forums.manage` only (the step-up is the
     * controller's), before any number of the period (409 `ALREADY_STARTED`); the period must be one of the committee's
     * format (422 `FORMAT`), [next] 1 to 999,999,999 (422 `RANGE`). Audited.
     */
    fun start(
        forumId: UUID,
        period: String,
        next: Long,
    ) {
        val forum = requireManager(forumId)
        val numbering = policies.published(forum.typeId)?.minutes?.resolutionNumbering ?: ResolutionNumbering()
        requireNoProblems(
            listOfNotNull(
                FieldProblem("period", "FORMAT", "A period of this committee's numbering: ${hint(numbering.reset)}.")
                    .takeIf { !fits(numbering.reset, period) },
                FieldProblem("next", "RANGE", "1 to $MAX_START.").takeIf { next !in 1..MAX_START },
            ),
        )
        refuseIf(counters.counter(forum.id, period) != null || !counters.start(forum.id, period, next), "ALREADY_STARTED") {
            "This committee has given numbers in $period already: its first number cannot be set."
        }
        audit.record(
            "minutes.resolution.counter_started",
            "forum",
            forum.id.toString(),
            after = mapOf("period" to period, "next" to next),
        )
    }

    /**
     * Whether the actor may issue a certified extract of [row] now (design E06 §1.6.6): a secretary of its committee in
     * office, not recused from the item, the number in force and the memo moved on (released; for an item without a memo,
     * the meeting held).
     */
    private fun mayIssueExtract(
        row: ResolutionRow,
        decision: CurrentDecision,
        meeting: com.memo.meeting.MeetingRef,
        view: MeetingView?,
    ): Boolean {
        val user = actorOrNull() ?: return false
        val secretary = directory.membersOn(row.forumId).any { it.userId == user && it.role == ForumRole.SECRETARY }
        val recused = view != null && decision.decision.itemId in view.recused
        val released =
            if (decision.decision.submissionId != null) {
                decision.decision.releasedAt != null
            } else {
                meeting.status == MeetingStatus.HELD || meeting.status == MeetingStatus.MINUTES_APPROVED
            }
        return secretary && !recused && row.state == ResolutionState.IN_FORCE && released
    }

    /** Called with the period's format before the step-up, so a refusal does not ask for one. */
    fun requireManager(forumId: UUID): ForumRef {
        val forum = directory.forum(forumId)
        if (forum != null && policy.allows(Permission.FORUMS_MANAGE)) return forum
        val known = forum != null && (roleIn(forum.id) != null || policy.allows(Permission.FORUM_READ))
        throw if (known) ForbiddenException(Permission.FORUMS_MANAGE) else MinutesNotFoundException("forum")
    }

    /** The register's readers: the committee's officers, `forums.manage` and `forum.read`; 403 to a member, 404 to others. */
    private fun requireRegister(forumId: UUID): ForumRef {
        val forum = directory.forum(forumId)
        val role = forum?.let { roleIn(it.id) }
        val reads = policy.allows(Permission.FORUMS_MANAGE) || policy.allows(Permission.FORUM_READ) || role in OFFICERS
        if (forum != null && reads) return forum
        throw if (forum != null && role != null) ForbiddenException(Permission.FORUM_READ) else MinutesNotFoundException("forum")
    }

    private fun roleIn(forumId: UUID): ForumRole? = actorOrNull()?.let { directory.rolesOf(it)[forumId] }

    private fun fits(
        reset: NumberReset,
        period: String,
    ): Boolean =
        when (reset) {
            NumberReset.NEVER -> period == "ALL"
            NumberReset.YEARLY -> period.matches(Regex("[0-9]{4}"))
            NumberReset.FINANCIAL_YEAR -> period.matches(Regex("FY[0-9]{4}-[0-9]{2}"))
        }

    private fun hint(reset: NumberReset): String =
        when (reset) {
            NumberReset.NEVER -> "ALL"
            NumberReset.YEARLY -> "a year, such as 2026"
            NumberReset.FINANCIAL_YEAR -> "a financial year, such as FY2026-27"
        }

    private companion object {
        const val MAX_PAGE = 100
        const val MAX_START = 999_999_999L
        val OFFICERS = setOf(ForumRole.SECRETARY, ForumRole.CHAIR, ForumRole.VICE_CHAIR)
    }
}
