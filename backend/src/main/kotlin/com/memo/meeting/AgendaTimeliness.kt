package com.memo.meeting

import com.memo.meeting.internal.AgendaLateness
import com.memo.meeting.internal.MeetingStore
import com.memo.meeting.internal.PaperViewers
import com.memo.meeting.internal.RevisionStore
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Whether a meeting's papers went out on time (design E08 §2.1 M5). */
data class MeetingTimeliness(
    val meetingId: UUID,
    /** When the agenda's first revision was published; null when it never was. */
    val firstPublishedAt: Instant?,
    /** That day in the meeting's zone. */
    val firstPublishedOn: LocalDate?,
    /** The papers deadline ([AgendaDeadlines.of]): the last day papers are on time; null when no working day counts. */
    val deadline: LocalDate?,
    /** The latest published revision's number; null when none was published. */
    val latestRevision: Int?,
) {
    /**
     * The first revision was published on or before the deadline; false when it came later or never; null when the
     * meeting has no deadline to compare with (left out of the share).
     */
    val onTime: Boolean?
        get() =
            when {
                deadline == null -> null
                firstPublishedOn == null -> false
                else -> !firstPublishedOn.isAfter(deadline)
            }
}

/** The items and papers flagged LATE or TABLED in a meeting's latest published revision that the viewer sees in full. */
data class LateCounts(
    val meetingId: UUID,
    val items: Int,
    val papers: Int,
)

/**
 * Papers on time for analytics (ADR-0070, design E08 §2.1 M5, §2.2), for other modules: per meeting, whether the first
 * revision of its agenda was published by the papers deadline (the deadline as [AgendaDeadlines] gives it), and, in its
 * latest revision, how many items and papers were late or tabled, counted only on what the signed-in actor sees in full
 * (the paper rule with their recusals, [PaperAccess.late]). Read only, inside a tenant transaction; a few queries for
 * any number of meetings.
 */
@Component
class AgendaTimeliness(
    private val meetings: MeetingStore,
    private val revisions: RevisionStore,
    private val lateness: AgendaLateness,
    private val viewers: PaperViewers,
    private val paperAccess: PaperAccess,
) {
    /** The timeliness of each of [meetingIds] that exists (the meeting-level share: no item is looked at). */
    fun of(meetingIds: Collection<UUID>): Map<UUID, MeetingTimeliness> {
        val rows = meetings.findAll(meetingIds)
        val heads = revisions.heads(rows.map { it.id })
        return rows.associate { row ->
            val published = heads[row.id].orEmpty()
            val first = published.firstOrNull()?.publishedAt
            row.id to
                MeetingTimeliness(
                    meetingId = row.id,
                    firstPublishedAt = first,
                    firstPublishedOn = first?.atZone(row.zone)?.toLocalDate(),
                    deadline = lateness.deadline(row),
                    latestRevision = published.lastOrNull()?.number,
                )
        }
    }

    /**
     * The late and tabled items and papers of each of [meetingIds]' latest published revision that the signed-in actor
     * sees in full; empty for nobody signed in. Meetings without a published revision count none.
     */
    fun lateCounts(meetingIds: Collection<UUID>): Map<UUID, LateCounts> {
        val rows = meetings.findAll(meetingIds)
        val actors = viewers.actors(rows)
        if (actors.isEmpty()) return emptyMap()
        val latest =
            revisions
                .heads(rows.map { it.id })
                .mapNotNull { (meeting, heads) ->
                    heads.lastOrNull()?.let { meeting to it.id }
                }.toMap()
        // Meetings the actor stands in the same way to share one viewer, so one pair of queries serves them all.
        val counted =
            latest.keys
                .groupBy { actors.getValue(it) }
                .flatMap { (viewer, ids) -> paperAccess.late(viewer, ids.map { latest.getValue(it) }).entries }
                .associate { it.key to it.value }
        return rows.associate { row ->
            val (items, papers) = latest[row.id]?.let { counted[it] } ?: (0 to 0)
            row.id to LateCounts(row.id, items, papers)
        }
    }
}
