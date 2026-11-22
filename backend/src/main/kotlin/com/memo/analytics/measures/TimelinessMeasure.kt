package com.memo.analytics.measures

import com.memo.meeting.LateCounts
import com.memo.meeting.MeetingTimeliness
import java.util.UUID

/** One held meeting's papers (design E08 §2.1 M5): on time, and the late items and papers the viewer may count. */
data class MeetingPapers(
    val meetingId: UUID,
    /** Null: the meeting has no deadline to compare with. */
    val onTime: Boolean?,
    /** Null where the viewer is not given late counts (members). */
    val lateItems: Int?,
    val latePapers: Int?,
)

/**
 * A committee's papers on time over its held meetings: [meetings] held, [withDeadline] of them with a deadline to compare
 * with, [onTime] of those published by it; the late items and papers summed (null for members).
 */
data class CommitteePapers(
    val meetings: Int,
    val withDeadline: Int,
    val onTime: Int,
    val lateItems: Int?,
    val latePapers: Int?,
) {
    val onTimePercent: Int? get() = percent(onTime, withDeadline)
}

/**
 * Measure M5, papers on time (ADR-0070, design E08 §2.1): a held meeting's papers were on time when the first revision of
 * its agenda was published on or before the papers deadline (the committee type's agenda policy, counted in working days;
 * a meeting whose agenda was never published was not on time; one with no deadline to count is left out of the share).
 * The late items and papers are those flagged LATE or TABLED in its latest published revision, counted only on what the
 * viewer sees in full (with their recusals), and only for officers and oversight: members see the share only.
 */
object TimelinessMeasure {
    /** Each of [meetingIds]' papers; late counts only when [late] is given. */
    fun meetings(
        meetingIds: List<UUID>,
        timeliness: Map<UUID, MeetingTimeliness>,
        late: Map<UUID, LateCounts>?,
    ): List<MeetingPapers> =
        meetingIds.map { id ->
            MeetingPapers(
                meetingId = id,
                onTime = timeliness[id].let { if (it == null) false else it.onTime },
                lateItems = late?.let { it[id]?.items ?: 0 },
                latePapers = late?.let { it[id]?.papers ?: 0 },
            )
        }

    /** The committee's totals over [meetings]; late counts summed when [withLate]. */
    fun committee(
        meetings: List<MeetingPapers>,
        withLate: Boolean,
    ): CommitteePapers =
        CommitteePapers(
            meetings = meetings.size,
            withDeadline = meetings.count { it.onTime != null },
            onTime = meetings.count { it.onTime == true },
            lateItems = if (withLate) meetings.sumOf { it.lateItems ?: 0 } else null,
            latePapers = if (withLate) meetings.sumOf { it.latePapers ?: 0 } else null,
        )
}
