package com.memo.analytics.api

import com.memo.actions.ActionStats
import com.memo.analytics.export.AttendanceExport
import com.memo.analytics.measures.ActionMeasure
import com.memo.analytics.measures.AnalyticsRejectedException
import com.memo.analytics.measures.AttendanceMeasure
import com.memo.analytics.measures.Closed
import com.memo.analytics.measures.CommitteeActions
import com.memo.analytics.measures.CommitteeAttendance
import com.memo.analytics.measures.CommitteePapers
import com.memo.analytics.measures.DecisionMeasure
import com.memo.analytics.measures.Deferrals
import com.memo.analytics.measures.MemberAttendance
import com.memo.analytics.measures.Overdue
import com.memo.analytics.measures.Period
import com.memo.analytics.measures.TimelinessMeasure
import com.memo.analytics.measures.Timing
import com.memo.audit.AuditLog
import com.memo.common.authz.ForbiddenException
import com.memo.common.context.ActorNames
import com.memo.common.web.FieldProblem
import com.memo.forum.ForumRef
import com.memo.meeting.AgendaTimeliness
import com.memo.meeting.AttendanceStats
import com.memo.meeting.HeldMeetingAttendance
import com.memo.org.OrgDirectory
import com.memo.search.DecisionFact
import com.memo.search.DecisionStats
import com.memo.workflow.WorkingDayCounter
import com.memo.workflow.WorkingDays
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Meeting analytics (ADR-0070, design E08 §2, flows AU–AW): the five measures of the committees in the viewer's scope,
 * computed live on every request from the rows the viewer may see, through the published reads of the meeting
 * ([AttendanceStats], [AgendaTimeliness]), actions ([ActionStats]) and search ([DecisionStats]) modules, in the request's
 * transaction. Nothing is stored or cached. Held meetings above the viewer's clearance are left out of every measure.
 * Reads are not audited (lists of counts); the attendance export is.
 */
@Service
class CommitteeAnalytics(
    private val viewers: AnalyticsViewers,
    private val attendance: AttendanceStats,
    private val timeliness: AgendaTimeliness,
    private val actions: ActionStats,
    private val decisions: DecisionStats,
    private val workingDays: WorkingDays,
    private val org: OrgDirectory,
    private val names: ActorNames,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    /**
     * One summary per committee in scope (or of [forumIds], each in scope: else 422 `forumIds FORUM_OUT_OF_SCOPE`), over
     * the period [from]–[to] (default the last 12 months, at most ten years: 422 `RANGE`). 403 without a scope.
     */
    fun summary(
        from: LocalDate?,
        to: LocalDate?,
        forumIds: Set<UUID>,
    ): AnalyticsSummaryResponse {
        val viewer = viewer()
        val period = Period.of(from, to, org.today())
        val outside = forumIds.filter { viewer.access(it) == null }
        if (outside.isNotEmpty()) {
            throw AnalyticsRejectedException(
                listOf(FieldProblem("forumIds", "FORUM_OUT_OF_SCOPE", "Choose committees from your analytics.")),
            )
        }
        val committees = viewer.committees.filter { forumIds.isEmpty() || it.id in forumIds }
        val data = gather(viewer, committees, period)
        return AnalyticsSummaryResponse(
            from = period.from,
            to = period.to,
            committees =
                committees.map { forum ->
                    val access = checkNotNull(viewer.access(forum.id))
                    val held = data.held[forum.id].orEmpty()
                    val facts = data.facts[forum.id].orEmpty()
                    CommitteeSummaryResponse(
                        forumId = forum.id,
                        code = forum.code,
                        name = forum.name,
                        shortName = forum.shortName,
                        access = access,
                        attendance = attendanceOf(AttendanceMeasure.committee(held)),
                        actions = actionsOf(data.actions.getValue(forum.id)),
                        timeToDecision = timingOf(DecisionMeasure.timing(facts, data.zone, data.counter)),
                        deferrals = deferralsOf(DecisionMeasure.deferrals(facts)),
                        papers = papersOf(TimelinessMeasure.committee(data.papers(held, access), access == AnalyticsAccess.FULL)),
                    )
                },
        )
    }

    /**
     * [forumId]'s detail (flows AV, AW): 403 without a scope, 404 for a committee outside it. A member gets their own
     * attendance row and no late counts; above five years no member rows are given.
     */
    fun detail(
        forumId: UUID,
        from: LocalDate?,
        to: LocalDate?,
    ): CommitteeDetailResponse {
        val viewer = viewer()
        val access = viewer.access(forumId) ?: throw AnalyticsNotFoundException()
        val forum = viewer.committees.first { it.id == forumId }
        val period = Period.of(from, to, org.today())
        val data = gather(viewer, listOf(forum), period)
        val held = data.held[forumId].orEmpty()
        val facts = data.facts[forumId].orEmpty()
        val withMembers = period.within(Period.MAX_MEMBER_YEARS)
        val members =
            if (!withMembers) {
                emptyList()
            } else {
                AttendanceMeasure
                    .members(held, period)
                    .filter { access == AnalyticsAccess.FULL || it.userId == viewer.userId }
            }
        val papers = data.papers(held, access)
        val committeeActions = data.actions.getValue(forumId)
        return CommitteeDetailResponse(
            forumId = forum.id,
            code = forum.code,
            name = forum.name,
            shortName = forum.shortName,
            access = access,
            from = period.from,
            to = period.to,
            attendance =
                AttendanceDetail(
                    summary = attendanceOf(AttendanceMeasure.committee(held)),
                    members = memberRows(members),
                    ownRowOnly = access == AnalyticsAccess.MEMBER,
                    membersOmitted = !withMembers,
                ),
            actions = ActionsDetail(actionsOf(committeeActions), owners(ActionMeasure.owners(forumId, data.overdue))),
            timeToDecision =
                TimingDetail(
                    overall = timingOf(DecisionMeasure.timing(facts, data.zone, data.counter)),
                    byQuarter =
                        DecisionMeasure.timingByQuarter(facts, data.zone, data.counter).map {
                            QuarterTimingResponse(it.quarter, timingOf(it.timing))
                        },
                    byMemoType =
                        DecisionMeasure.timingByMemoType(facts, data.zone, data.counter).map {
                            MemoTypeTimingResponse(it.memoTypeId, timingOf(it.timing))
                        },
                ),
            deferrals =
                DeferralDetail(
                    overall = deferralsOf(DecisionMeasure.deferrals(facts)),
                    byQuarter =
                        DecisionMeasure
                            .deferralsByQuarter(
                                facts,
                            ).map { QuarterDeferralResponse(it.quarter, deferralsOf(it.deferrals)) },
                ),
            papers =
                PapersDetail(
                    summary = papersOf(TimelinessMeasure.committee(papers, access == AnalyticsAccess.FULL)),
                    meetings =
                        held.zip(papers).map { (meeting, paper) ->
                            val counted = AttendanceMeasure.committee(listOf(meeting))
                            MeetingPapersResponse(
                                meetingId = meeting.meeting.id,
                                label = meeting.meeting.label,
                                date = meeting.meeting.date,
                                onTime = paper.onTime,
                                lateItems = paper.lateItems,
                                latePapers = paper.latePapers,
                                expected = counted.expected,
                                attended = counted.attended,
                            )
                        },
                ),
        )
    }

    /**
     * The actor, when they may export [forumId]'s attendance for the period (checked before the rate limit is counted):
     * 403 without a scope or for a member, 404 outside scope, 422 `RANGE` beyond five years.
     */
    fun exporter(
        forumId: UUID,
        from: LocalDate?,
        to: LocalDate?,
    ): UUID {
        val viewer = viewer()
        exportable(viewer, forumId)
        Period.of(from, to, org.today(), Period.MAX_MEMBER_YEARS)
        return viewer.userId
    }

    /** [forumId]'s attendance per seat holder for the period (design E08 §2.4), audited before a byte is written. */
    fun export(
        forumId: UUID,
        from: LocalDate?,
        to: LocalDate?,
    ): AttendanceExport {
        val viewer = viewer()
        val forum = exportable(viewer, forumId)
        val period = Period.of(from, to, org.today(), Period.MAX_MEMBER_YEARS)
        val held = attendance.held(setOf(forumId), period.from, period.to, viewer.clearanceRank)
        val members = AttendanceMeasure.members(held, period)
        val named = names.names(members.map { it.userId } + viewer.userId)
        val rows = members.sortedWith(compareBy({ named[it.userId].orEmpty().lowercase() }, { it.userId }))
        audit.record(
            "analytics.attendance.exported",
            OBJECT_TYPE,
            forumId.toString(),
            after =
                mapOf(
                    "from" to period.from.toString(),
                    "to" to period.to.toString(),
                    "heldMeetings" to held.size,
                    "rows" to rows.size,
                ),
        )
        return AttendanceExport(
            committee = forum.name,
            period = period,
            heldMeetings = held.size,
            committeeTotals = AttendanceMeasure.committee(held),
            rows = rows,
            names = named,
            producedBy = named[viewer.userId].orEmpty(),
            producedAt = clock.instant(),
            zone = org.zone(),
        )
    }

    private fun exportable(
        viewer: AnalyticsViewer,
        forumId: UUID,
    ): ForumRef {
        val access = viewer.access(forumId) ?: throw AnalyticsNotFoundException()
        if (access != AnalyticsAccess.FULL) {
            throw ForbiddenException(null, "MEMBERS_DO_NOT_EXPORT", "The attendance export is for officers and oversight.")
        }
        return viewer.committees.first { it.id == forumId }
    }

    private fun viewer(): AnalyticsViewer =
        viewers.actor()
            ?: throw ForbiddenException(null, "NO_COMMITTEES", "Analytics are for committee members, officers and oversight.")

    /** Everything the five measures read for [committees], in a fixed number of queries. */
    private fun gather(
        viewer: AnalyticsViewer,
        committees: List<ForumRef>,
        period: Period,
    ): Gathered {
        val ids = committees.map { it.id }.toSet()
        val held = attendance.held(ids, period.from, period.to, viewer.clearanceRank).groupBy { it.meeting.forumId }
        val all = held.values.flatten().map { it.meeting.id }
        val full =
            held
                .filterKeys { viewer.access(it) == AnalyticsAccess.FULL }
                .values
                .flatten()
                .map { it.meeting.id }
        val overdue = actions.overdue(ids, org.today())
        val closed = actions.closedOnTime(ids, period.from, period.to)
        val facts = if (ids.isEmpty()) emptyList() else decisions.of(ids, period.from, period.to)
        val zone = org.zone()
        return Gathered(
            held = held,
            timeliness = timeliness.of(all),
            late = timeliness.lateCounts(full),
            overdue = overdue,
            actions = ActionMeasure.committees(ids, overdue, closed),
            facts = facts.groupBy { it.forumId },
            zone = zone,
            counter = counter(facts, zone),
        )
    }

    /** One calendar read for every span of [facts]. */
    private fun counter(
        facts: List<DecisionFact>,
        zone: ZoneId,
    ): WorkingDayCounter {
        val days = facts.flatMap { listOfNotNull(it.chainSubmittedAt, it.decidedAt) }.map { it.atZone(zone).toLocalDate() }
        val first = days.minOrNull() ?: return WorkingDayCounter { _, _ -> 0 }
        return workingDays.counter(first, checkNotNull(days.maxOrNull()))
    }

    private inner class Gathered(
        val held: Map<UUID, List<HeldMeetingAttendance>>,
        val timeliness: Map<UUID, com.memo.meeting.MeetingTimeliness>,
        val late: Map<UUID, com.memo.meeting.LateCounts>,
        val overdue: List<com.memo.actions.OverdueActions>,
        val actions: Map<UUID, CommitteeActions>,
        val facts: Map<UUID, List<DecisionFact>>,
        val zone: ZoneId,
        val counter: WorkingDayCounter,
    ) {
        fun papers(
            meetings: List<HeldMeetingAttendance>,
            access: AnalyticsAccess,
        ) = TimelinessMeasure.meetings(
            meetings.map { it.meeting.id },
            timeliness,
            late.takeIf { access == AnalyticsAccess.FULL },
        )
    }

    private fun memberRows(members: List<MemberAttendance>): List<MemberAttendanceResponse> {
        val named = names.names(members.map { it.userId })
        return members
            .map { m ->
                MemberAttendanceResponse(
                    userId = m.userId,
                    name = named[m.userId],
                    role = m.role,
                    seatFrom = m.seatFrom,
                    seatTo = m.seatTo,
                    expected = m.expected,
                    attended = m.attended,
                    inPerson = m.inPerson,
                    remote = m.remote,
                    joinedLate = m.joinedLate,
                    leftEarly = m.leftEarly,
                    apologies = m.apologies,
                    absent = m.absent,
                    byAlternate = m.byAlternate,
                    ratePercent = m.ratePercent,
                    meetings =
                        m.meetings.map {
                            MemberMeetingResponse(it.meetingId, it.date, it.attendance, it.byAlternate, it.joinedLate, it.leftEarly)
                        },
                )
            }.sortedWith(compareBy({ it.name.orEmpty().lowercase() }, { it.userId }))
    }

    private fun owners(owners: List<com.memo.analytics.measures.OwnerOverdue>): List<OwnerOverdueResponse> {
        val people = names.names(owners.flatMap { listOfNotNull(it.ownerUserId, it.holderUserId) }.distinct())
        val positions = org.positions(owners.mapNotNull { it.ownerPositionId }.distinct())
        return owners.map {
            OwnerOverdueResponse(
                ownerUserId = it.ownerUserId,
                ownerPositionId = it.ownerPositionId,
                holderUserId = it.holderUserId,
                ownerName = it.ownerPositionId?.let { id -> positions[id]?.title } ?: it.ownerUserId?.let(people::get),
                holderName = it.holderUserId?.let(people::get),
                overdue = it.overdue.count,
                days1To30 = it.overdue.days1To30,
                days31To90 = it.overdue.days31To90,
                over90 = it.overdue.over90,
                revised = it.overdue.revised,
            )
        }
    }

    private fun attendanceOf(a: CommitteeAttendance) = AttendanceSummary(a.heldMeetings, a.expected, a.attended, a.ratePercent)

    private fun actionsOf(a: CommitteeActions): ActionsSummary {
        val o: Overdue = a.overdue
        val c: Closed = a.closed
        return ActionsSummary(
            overdue = o.count,
            overdue1To30 = o.days1To30,
            overdue31To90 = o.days31To90,
            overdueOver90 = o.over90,
            overdueRevised = o.revised,
            closed = c.closed,
            closedByOriginalDate = c.byOriginalDate,
            closedByAgreedDate = c.byAgreedDate,
            closedByOriginalPercent = c.byOriginalPercent,
            closedByAgreedPercent = c.byAgreedPercent,
        )
    }

    private fun timingOf(t: Timing) = TimingSummary(t.decisions, t.medianDays, t.p90Days, t.medianWorkingDays, t.p90WorkingDays, t.enough)

    private fun deferralsOf(d: Deferrals) = DeferralSummary(d.decisions, d.deferred, d.deferredPercent, d.deferredTwice)

    private fun papersOf(p: CommitteePapers) =
        PapersSummary(p.meetings, p.withDeadline, p.onTime, p.onTimePercent, p.lateItems, p.latePapers)

    private companion object {
        const val OBJECT_TYPE = "forum"
    }
}
