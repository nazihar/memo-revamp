package com.memo.actions.internal

import com.memo.meeting.MeetingItems
import com.memo.meeting.Meetings
import com.memo.minutes.MinutesCorrections
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * Section 6 of the action taken report from E06 T8's [MinutesCorrections] (design E07 §1.12, §11): one row per approved
 * correction of the committee's minutes since the previous meeting's start (every one when there was none). The row
 * names the corrected minutes and item by number only ("Minutes of BRC 3/2026, item 5: corrected"), never the
 * correction's text, and keeps the corrected item's columns and level (the minutes' meeting's level for a general
 * correction), so each reader gets the rows the one rule gives them.
 */
@Component
class ActionReportCorrections(
    private val corrections: MinutesCorrections,
    private val meetings: Meetings,
    private val items: MeetingItems,
) : ReportCorrections {
    override fun since(
        forumId: UUID,
        since: Instant?,
    ): List<ReportCorrection> {
        val found = corrections.since(forumId, since ?: Instant.EPOCH)
        val agendas = found.map { it.meetingId }.distinct().associateWith { items.agenda(it) }
        return found.mapNotNull { c ->
            val meeting = meetings.find(c.meetingId) ?: return@mapNotNull null
            val item = c.itemId?.let { agendas.getValue(c.meetingId).item(it) }
            // An item no longer on that agenda: left out, never shown as a general correction (fail closed).
            if (c.itemId != null && item == null) return@mapNotNull null
            val minutes = "Minutes of ${meeting.label ?: "the meeting"}"
            val label = item?.number?.let { "$minutes, item $it: corrected" } ?: "$minutes: corrected"
            ReportCorrection(
                label = label,
                approvedAt = c.approvedAt,
                itemId = item?.id,
                item = item?.let { ItemCopy(it.parentId, it.classificationId, it.restrictedTo, it.titleHidden, it.membersOnly) },
                classificationId = item?.classificationId ?: meeting.classificationId,
            )
        }
    }
}
