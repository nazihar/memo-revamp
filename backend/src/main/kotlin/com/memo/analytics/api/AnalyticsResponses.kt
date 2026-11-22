package com.memo.analytics.api

import com.memo.analytics.measures.SeatAttendance
import com.memo.forum.ForumRole
import java.time.LocalDate
import java.util.UUID

/** How much of a committee's analytics the viewer is given (design E08 §2.3). */
enum class AnalyticsAccess {
    /** Officers (secretary, chair, vice-chair), `forums.manage` and `forum.read`: every measure, every member's row. */
    FULL,

    /** A member in office: the committee's totals, their own attendance row, the meeting-level share of papers on time. */
    MEMBER,
}

/** M1 for a committee: seat holders' attended meetings over expected ones, and the held meetings counted. */
data class AttendanceSummary(
    val heldMeetings: Int,
    val expected: Int,
    val attended: Int,
    /** attended ÷ expected in whole percent; null without an expected meeting. */
    val ratePercent: Int?,
)

/** M2 for a committee, from the actions the viewer may see. */
data class ActionsSummary(
    /** Issued, OPEN or IN_PROGRESS, past the due date today. */
    val overdue: Int,
    val overdue1To30: Int,
    val overdue31To90: Int,
    val overdueOver90: Int,
    /** Overdue ones whose date was changed at least once. */
    val overdueRevised: Int,
    /** Closed in the period. */
    val closed: Int,
    val closedByOriginalDate: Int,
    val closedByAgreedDate: Int,
    val closedByOriginalPercent: Int?,
    val closedByAgreedPercent: Int?,
)

/** M3 over some decisions the viewer sees in full: calendar days with working days beside them. */
data class TimingSummary(
    /** The basis: "based on n decisions you can see in full". */
    val decisions: Int,
    val medianDays: Int?,
    val p90Days: Int?,
    val medianWorkingDays: Int?,
    val p90WorkingDays: Int?,
    /** At least five decisions: below that the screen lists the decisions instead of a typical time. */
    val enough: Boolean,
)

/** M4 over the decisions on submission items the viewer sees in full. */
data class DeferralSummary(
    val decisions: Int,
    val deferred: Int,
    val deferredPercent: Int?,
    /** Items deferred for at least the second time in their chain. */
    val deferredTwice: Int,
)

/** M5 for a committee: its held meetings whose first agenda revision was published by the papers deadline. */
data class PapersSummary(
    val meetings: Int,
    /** The meetings with a deadline to compare with: the share's basis. */
    val withDeadline: Int,
    val onTime: Int,
    val onTimePercent: Int?,
    /** Items and papers flagged late or tabled that the viewer sees in full; null for members (the share only). */
    val lateItems: Int?,
    val latePapers: Int?,
)

/** One committee's five measures (flow AU). */
data class CommitteeSummaryResponse(
    val forumId: UUID,
    val code: String,
    val name: String,
    val shortName: String,
    val access: AnalyticsAccess,
    val attendance: AttendanceSummary,
    val actions: ActionsSummary,
    val timeToDecision: TimingSummary,
    val deferrals: DeferralSummary,
    val papers: PapersSummary,
)

/** The analytics overview: one block per committee in the viewer's scope, by name (flow AU). */
data class AnalyticsSummaryResponse(
    val from: LocalDate,
    val to: LocalDate,
    val committees: List<CommitteeSummaryResponse>,
)

/** One held meeting of a seat holder's term. */
data class MemberMeetingResponse(
    val meetingId: UUID,
    val date: LocalDate,
    val attendance: SeatAttendance,
    /** Their alternate attended in their place (not counted as theirs). */
    val byAlternate: Boolean,
    val joinedLate: Boolean,
    val leftEarly: Boolean,
)

/** A seat holder's attendance in the period (flow AV). */
data class MemberAttendanceResponse(
    val userId: UUID,
    val name: String?,
    val role: ForumRole,
    val seatFrom: LocalDate,
    val seatTo: LocalDate,
    val expected: Int,
    val attended: Int,
    val inPerson: Int,
    val remote: Int,
    val joinedLate: Int,
    val leftEarly: Int,
    val apologies: Int,
    val absent: Int,
    val byAlternate: Int,
    val ratePercent: Int?,
    val meetings: List<MemberMeetingResponse>,
)

/** M1 in detail: the totals and the seat holders' rows. */
data class AttendanceDetail(
    val summary: AttendanceSummary,
    /** By name; a member's own row only; empty when [membersOmitted]. */
    val members: List<MemberAttendanceResponse>,
    /** The viewer is a member: [members] holds their own row (when they held a seat at a held meeting) only. */
    val ownRowOnly: Boolean,
    /** The period is longer than five years: per-member rows are not given (the committee's totals are). */
    val membersOmitted: Boolean,
)

/** One owner's overdue actions with ageing (flow AW). */
data class OwnerOverdueResponse(
    val ownerUserId: UUID?,
    val ownerPositionId: UUID?,
    val holderUserId: UUID?,
    /** The position's title for a position owner, else the person's name. */
    val ownerName: String?,
    /** Who holds the owner's task now. */
    val holderName: String?,
    val overdue: Int,
    val days1To30: Int,
    val days31To90: Int,
    val over90: Int,
    val revised: Int,
)

data class ActionsDetail(
    val summary: ActionsSummary,
    val owners: List<OwnerOverdueResponse>,
)

data class QuarterTimingResponse(
    /** "2026-Q1". */
    val quarter: String,
    val timing: TimingSummary,
)

data class MemoTypeTimingResponse(
    val memoTypeId: UUID?,
    val timing: TimingSummary,
)

data class TimingDetail(
    val overall: TimingSummary,
    val byQuarter: List<QuarterTimingResponse>,
    val byMemoType: List<MemoTypeTimingResponse>,
)

data class QuarterDeferralResponse(
    val quarter: String,
    val deferrals: DeferralSummary,
)

data class DeferralDetail(
    val overall: DeferralSummary,
    val byQuarter: List<QuarterDeferralResponse>,
)

/** One held meeting: its papers and its seat holders' attendance (the committee's meetings of the period). */
data class MeetingPapersResponse(
    val meetingId: UUID,
    val label: String?,
    val date: LocalDate,
    /** Null: no deadline to compare with. */
    val onTime: Boolean?,
    val lateItems: Int?,
    val latePapers: Int?,
    val expected: Int,
    val attended: Int,
)

data class PapersDetail(
    val summary: PapersSummary,
    val meetings: List<MeetingPapersResponse>,
)

/** A committee's detail (flows AV, AW). */
data class CommitteeDetailResponse(
    val forumId: UUID,
    val code: String,
    val name: String,
    val shortName: String,
    val access: AnalyticsAccess,
    val from: LocalDate,
    val to: LocalDate,
    val attendance: AttendanceDetail,
    val actions: ActionsDetail,
    val timeToDecision: TimingDetail,
    val deferrals: DeferralDetail,
    val papers: PapersDetail,
)
