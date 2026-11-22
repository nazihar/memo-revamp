package com.memo.analytics.api

import com.memo.analytics.measures.SeatAttendance
import com.memo.forum.ForumRole
import io.swagger.v3.oas.annotations.media.Schema
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
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val heldMeetings: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val expected: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val attended: Int,
    /** attended ÷ expected in whole percent; null without an expected meeting. */
    val ratePercent: Int?,
)

/** M2 for a committee, from the actions the viewer may see. */
data class ActionsSummary(
    /** Issued, OPEN or IN_PROGRESS, past the due date today. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val overdue: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val overdue1To30: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val overdue31To90: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val overdueOver90: Int,
    /** Overdue ones whose date was changed at least once. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val overdueRevised: Int,
    /** Closed in the period. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val closed: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val closedByOriginalDate: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val closedByAgreedDate: Int,
    val closedByOriginalPercent: Int?,
    val closedByAgreedPercent: Int?,
)

/** M3 over some decisions the viewer sees in full: calendar days with working days beside them. */
data class TimingSummary(
    /** The basis: "based on n decisions you can see in full". */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val decisions: Int,
    val medianDays: Int?,
    val p90Days: Int?,
    val medianWorkingDays: Int?,
    val p90WorkingDays: Int?,
    /** At least five decisions: below that the screen lists the decisions instead of a typical time. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val enough: Boolean,
)

/** M4 over the decisions on submission items the viewer sees in full. */
data class DeferralSummary(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val decisions: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val deferred: Int,
    val deferredPercent: Int?,
    /** Items deferred for at least the second time in their chain. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val deferredTwice: Int,
)

/** M5 for a committee: its held meetings whose first agenda revision was published by the papers deadline. */
data class PapersSummary(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val meetings: Int,
    /** The meetings with a deadline to compare with: the share's basis. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val withDeadline: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val onTime: Int,
    val onTimePercent: Int?,
    /** Items and papers flagged late or tabled that the viewer sees in full; null for members (the share only). */
    val lateItems: Int?,
    val latePapers: Int?,
)

/** One committee's five measures (flow AU). */
data class CommitteeSummaryResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val forumId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val code: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val shortName: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val access: AnalyticsAccess,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val attendance: AttendanceSummary,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val actions: ActionsSummary,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val timeToDecision: TimingSummary,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val deferrals: DeferralSummary,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val papers: PapersSummary,
)

/** The analytics overview: one block per committee in the viewer's scope, by name (flow AU). */
data class AnalyticsSummaryResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val from: LocalDate,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val to: LocalDate,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val committees: List<CommitteeSummaryResponse>,
)

/** One held meeting of a seat holder's term. */
data class MemberMeetingResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val meetingId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val date: LocalDate,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val attendance: SeatAttendance,
    /** Their alternate attended in their place (not counted as theirs). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val byAlternate: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val joinedLate: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val leftEarly: Boolean,
)

/** A seat holder's attendance in the period (flow AV). */
data class MemberAttendanceResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val userId: UUID,
    val name: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val role: ForumRole,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val seatFrom: LocalDate,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val seatTo: LocalDate,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val expected: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val attended: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val inPerson: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val remote: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val joinedLate: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val leftEarly: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val apologies: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val absent: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val byAlternate: Int,
    val ratePercent: Int?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val meetings: List<MemberMeetingResponse>,
)

/** M1 in detail: the totals and the seat holders' rows. */
data class AttendanceDetail(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val summary: AttendanceSummary,
    /** By name; a member's own row only; empty when [membersOmitted]. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val members: List<MemberAttendanceResponse>,
    /** The viewer is a member: [members] holds their own row (when they held a seat at a held meeting) only. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val ownRowOnly: Boolean,
    /** The period is longer than five years: per-member rows are not given (the committee's totals are). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val membersOmitted: Boolean,
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
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val overdue: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val days1To30: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val days31To90: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val over90: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val revised: Int,
)

data class ActionsDetail(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val summary: ActionsSummary,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val owners: List<OwnerOverdueResponse>,
)

data class QuarterTimingResponse(
    /** "2026-Q1". */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val quarter: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val timing: TimingSummary,
)

data class MemoTypeTimingResponse(
    val memoTypeId: UUID?,
    /** The memo type's name; null for submissions without a memo type. */
    val memoTypeName: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val timing: TimingSummary,
)

data class TimingDetail(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val overall: TimingSummary,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val byQuarter: List<QuarterTimingResponse>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val byMemoType: List<MemoTypeTimingResponse>,
)

data class QuarterDeferralResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val quarter: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val deferrals: DeferralSummary,
)

data class DeferralDetail(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val overall: DeferralSummary,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val byQuarter: List<QuarterDeferralResponse>,
)

/** One held meeting: its papers and its seat holders' attendance (the committee's meetings of the period). */
data class MeetingPapersResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val meetingId: UUID,
    val label: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val date: LocalDate,
    /** Null: no deadline to compare with. */
    val onTime: Boolean?,
    val lateItems: Int?,
    val latePapers: Int?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val expected: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val attended: Int,
)

data class PapersDetail(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val summary: PapersSummary,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val meetings: List<MeetingPapersResponse>,
)

/** A committee's detail (flows AV, AW). */
data class CommitteeDetailResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val forumId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val code: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val shortName: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val access: AnalyticsAccess,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val from: LocalDate,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val to: LocalDate,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val attendance: AttendanceDetail,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val actions: ActionsDetail,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val timeToDecision: TimingDetail,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val deferrals: DeferralDetail,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val papers: PapersDetail,
)
