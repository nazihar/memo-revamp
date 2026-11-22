package com.memo.analytics.api

import com.memo.analytics.measures.AttendanceMeasure
import com.memo.analytics.measures.CommitteeActions
import com.memo.analytics.measures.CommitteeAttendance
import com.memo.analytics.measures.CommitteePapers
import com.memo.analytics.measures.DecisionMeasure
import com.memo.analytics.measures.Deferrals
import com.memo.analytics.measures.MeetingPapers
import com.memo.analytics.measures.TimelinessMeasure
import com.memo.analytics.measures.Timing
import com.memo.meeting.HeldMeetingAttendance
import com.memo.search.DecisionFact
import com.memo.workflow.WorkingDayCounter
import java.time.ZoneId
import java.util.UUID

/** The measures as the API answers them (design E08 §3): one mapping per measure, used by the overview and the detail. */
internal fun attendanceOf(a: CommitteeAttendance) = AttendanceSummary(a.heldMeetings, a.expected, a.attended, a.ratePercent)

internal fun actionsOf(a: CommitteeActions): ActionsSummary =
    ActionsSummary(
        overdue = a.overdue.count,
        overdue1To30 = a.overdue.days1To30,
        overdue31To90 = a.overdue.days31To90,
        overdueOver90 = a.overdue.over90,
        overdueRevised = a.overdue.revised,
        closed = a.closed.closed,
        closedByOriginalDate = a.closed.byOriginalDate,
        closedByAgreedDate = a.closed.byAgreedDate,
        closedByOriginalPercent = a.closed.byOriginalPercent,
        closedByAgreedPercent = a.closed.byAgreedPercent,
    )

internal fun timingOf(t: Timing) = TimingSummary(t.decisions, t.medianDays, t.p90Days, t.medianWorkingDays, t.p90WorkingDays, t.enough)

internal fun deferralsOf(d: Deferrals) = DeferralSummary(d.decisions, d.deferred, d.deferredPercent, d.deferredTwice)

internal fun papersOf(p: CommitteePapers) = PapersSummary(p.meetings, p.withDeadline, p.onTime, p.onTimePercent, p.lateItems, p.latePapers)

/** M3 in detail: overall, by quarter and by memo type ([typeNames] names the types). */
internal fun timingDetail(
    facts: List<DecisionFact>,
    zone: ZoneId,
    counter: WorkingDayCounter,
    typeNames: Map<UUID, String>,
) = TimingDetail(
    overall = timingOf(DecisionMeasure.timing(facts, zone, counter)),
    byQuarter = DecisionMeasure.timingByQuarter(facts, zone, counter).map { QuarterTimingResponse(it.quarter, timingOf(it.timing)) },
    byMemoType =
        DecisionMeasure.timingByMemoType(facts, zone, counter).map {
            MemoTypeTimingResponse(it.memoTypeId, it.memoTypeId?.let(typeNames::get), timingOf(it.timing))
        },
)

/** M4 in detail: overall and by quarter. */
internal fun deferralDetail(facts: List<DecisionFact>) =
    DeferralDetail(
        overall = deferralsOf(DecisionMeasure.deferrals(facts)),
        byQuarter = DecisionMeasure.deferralsByQuarter(facts).map { QuarterDeferralResponse(it.quarter, deferralsOf(it.deferrals)) },
    )

/** M5 in detail: the committee's totals and each held meeting's papers and attendance ([papers] in [held]'s order). */
internal fun papersDetail(
    held: List<HeldMeetingAttendance>,
    papers: List<MeetingPapers>,
    withLate: Boolean,
) = PapersDetail(
    summary = papersOf(TimelinessMeasure.committee(papers, withLate)),
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
)
