package com.memo.actions.internal

import com.memo.audit.AuditLog
import com.memo.forum.StandingKind
import com.memo.identity.UserSummaries
import com.memo.meeting.AgendaItemRef
import com.memo.meeting.MeetingItems
import com.memo.meeting.MeetingRef
import com.memo.meeting.MeetingStatus
import com.memo.meeting.Meetings
import com.memo.org.OrgDirectory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The sections of an action taken report (ADR-0067 C1, design E07 §1.12), in the order the meeting reads them: those
 * that need the meeting first. [HELD_BACK] and [MORE] rows are never shown, only counted.
 */
enum class ActionReportSection {
    /** 1. Done, to be verified: the committee is asked to agree. */
    TO_CLOSE,

    /** 2. Open or in progress, past their date. */
    OVERDUE,

    /** 3. The other open actions, due date first. */
    OPEN,

    /** 4. Closed since the previous meeting's start, for noting. */
    CLOSED,

    /** 5. Cancelled since the previous meeting's start, for noting ("risk accepted" in words). */
    CANCELLED,

    /** 6. Corrections to earlier minutes (E06 T8's `MinutesCorrections.since`, through [ReportCorrections]). */
    CORRECTIONS,

    /** Held back by a secretary from this meeting's reports: counted for officers. */
    HELD_BACK,

    /** Beyond the report's 500 rows: "and 12 more actions; see the committee's tracker". */
    MORE,
    ;

    companion object {
        /** The sections shown, in order. */
        val SHOWN = listOf(TO_CLOSE, OVERDUE, OPEN, CLOSED, CANCELLED, CORRECTIONS)
    }
}

/**
 * What a report row shows of an action, as it was when the agenda was published (design E07 §1.12): never changed after.
 * The reference, the source label (numbers only), the title, the owner by title first, the dates, the colour in words,
 * the status, the latest update (its first 500 characters, with its date), and how it ended. Business content: each
 * reader sees only the rows the rule gives them ([ActionRuleConditions.report]).
 */
data class ReportSnapshot(
    val reference: String = "",
    val sourceLabel: String = "",
    val title: String = "",
    /** The position's title, or the person's name. */
    val owner: String = "",
    /** Who held the owner's task (a position's holder, or a substitute); null when it is the person owner or nobody. */
    val holder: String? = null,
    val dueDate: LocalDate? = null,
    val originalDueDate: LocalDate? = null,
    val dueRevisions: Int = 0,
    val rag: RagWord = RagWord.ON_TRACK,
    val status: ActionStatus = ActionStatus.OPEN,
    val confidence: Confidence? = null,
    val latestUpdate: String? = null,
    val latestUpdateAt: Instant? = null,
    val doneAt: Instant? = null,
    val closedAt: Instant? = null,
    val cancelKind: CancelKind? = null,
    val cancelReason: String? = null,
)

/** What a correction row shows (section 6): the correction in words, numbers only, and when it was approved. */
data class CorrectionSnapshot(
    val label: String = "",
    val approvedAt: Instant? = null,
)

/**
 * A correction to earlier minutes for section 6 (design E07 §1.12, §11): one per approved correction of the committee's
 * minutes since the previous meeting, with the corrected item's columns and level, so the rule gives each reader theirs.
 */
data class ReportCorrection(
    /** The correction in words, without the item's title or the minutes' text ("Minutes of BRC 3/2026, item 5 corrected"). */
    val label: String,
    val approvedAt: Instant,
    val itemId: UUID?,
    val item: ItemCopy?,
    val classificationId: UUID,
)

/**
 * Section 6's source (design E07 §11): [ActionReportCorrections] provides it from E06 T8's
 * `MinutesCorrections.since(forumId, since)`; without a provider the section is empty.
 */
fun interface ReportCorrections {
    /** [forumId]'s approved corrections to earlier minutes after [since] (every one when null), oldest first. */
    fun since(
        forumId: UUID,
        since: Instant?,
    ): List<ReportCorrection>
}

/** A row a reader may see: its place, its section, its action and its snapshot (JSON). */
data class ReportRowSeen(
    val position: Int,
    val section: ActionReportSection,
    val actionId: UUID?,
    val snapshot: String,
    val itemId: UUID?,
)

/**
 * Builds the action taken report (ADR-0067 C1, design E07 §1.12) in the transaction that publishes a revision of a
 * meeting's agenda with a MATTERS_ARISING item, and freezes it: a later revision gets its own report, earlier ones stay.
 * For the meeting's committee, every issued action:
 * 1. done, to be verified; 2. overdue; 3. open, due date first; 4. closed and 5. cancelled since the previous meeting's
 * start (every one when there was none); 6. corrections to earlier minutes ([ReportCorrections], from E06 T8's `MinutesCorrections`).
 * An action a secretary held back from this meeting is left out and counted for officers. At most [ROWS_MAX] rows are
 * shown: beyond that, the latest-due open actions (then the last rows) are counted as "more". Audited
 * `actions.report.built` with the rows per section and the content's SHA-256.
 */
@Service
class ActionReports(
    private val candidates: ActionReportCandidates,
    private val reports: ActionReportStore,
    private val meetings: Meetings,
    private val items: MeetingItems,
    private val users: UserSummaries,
    private val org: OrgDirectory,
    private val audit: AuditLog,
    private val json: JsonMapper,
    private val corrections: ObjectProvider<ReportCorrections>,
    private val clock: Clock,
) {
    /** The report of [meetingId]'s revision [revision], built once; null when the revision has no MATTERS_ARISING item. */
    fun build(
        meetingId: UUID,
        revision: Int,
    ): ReportHeader? {
        val meeting = meetings.find(meetingId)
        val item = meeting?.let { mattersArising(it, revision) } ?: return null
        val previous = ActionMeetings.previous(meetings.ofForum(meeting.forumId), meeting)
        val since = previous?.startsAt
        val today = org.today()
        val held = reports.held(meetingId)
        val sections = sections(meeting.forumId, since, today)
        val (shown, more) = cap(sections.filterKeys { it in ActionReportSection.SHOWN }.mapValues { (_, v) -> v.filter { it.id !in held } })
        val heldRows =
            sections.values
                .flatten()
                .filter { it.id in held }
                .distinctBy { it.id }
        val snapshots = snapshots(shown.map { it.second }, today)
        val fixes = corrections(meeting.forumId, since, ActionReports.ROWS_MAX - shown.size)
        val rows =
            shown.map { (section, row) -> input(section, row, json.writeValueAsString(snapshots.getValue(row.id))) } + fixes +
                heldRows.map { input(ActionReportSection.HELD_BACK, it, EMPTY) } +
                more.map { input(ActionReportSection.MORE, it, EMPTY) }
        val sha = ActionRules.sha256(rows.joinToString("\n") { "${it.section}|${it.actionId}|${it.snapshot}" })
        val header =
            ReportHeader(
                id = UUID.randomUUID(),
                forumId = meeting.forumId,
                meetingId = meetingId,
                revision = revision,
                itemId = item.id,
                previousMeetingId = previous?.id,
                since = since,
                builtAt = clock.instant().truncatedTo(ChronoUnit.MICROS),
                rowCount = shown.size + fixes.size,
                moreCount = more.size,
                heldCount = heldRows.size,
                contentSha256 = sha,
            )
        reports.insert(header, rows)
        audit.record(
            "actions.report.built",
            OBJECT_TYPE,
            header.id.toString(),
            after =
                mapOf(
                    "meetingId" to meetingId.toString(),
                    "revision" to revision,
                    "itemId" to item.id.toString(),
                    "previousMeetingId" to previous?.id?.toString(),
                    "rows" to ActionReportSection.SHOWN.associate { s -> s.name to rows.count { it.section == s } },
                    "heldBack" to heldRows.size,
                    "more" to more.size,
                    "contentSha256" to sha,
                ),
        )
        return header
    }

    /**
     * Section 6: the corrections to earlier minutes approved since [since] ([ReportCorrections], E06 T8's
     * `MinutesCorrections.since`), at most [room]; none while no module provides them.
     */
    private fun corrections(
        forumId: UUID,
        since: Instant?,
        room: Int,
    ): List<ReportRowInput> =
        corrections.ifAvailable
            ?.since(forumId, since)
            .orEmpty()
            .take(room.coerceAtLeast(0))
            .map { c ->
                val snapshot = json.writeValueAsString(CorrectionSnapshot(c.label, c.approvedAt))
                ReportRowInput(ActionReportSection.CORRECTIONS, null, snapshot, c.item, c.itemId, c.classificationId)
            }

    /** [meeting]'s MATTERS_ARISING item in its latest revision [revision], when that revision has no report yet. */
    private fun mattersArising(
        meeting: MeetingRef,
        revision: Int,
    ): AgendaItemRef? {
        val agenda = items.agenda(meeting.id)
        val fresh = agenda.revision == revision && !reports.exists(meeting.id, revision)
        return agenda.items.firstOrNull { fresh && it.published && it.standingKind == StandingKind.MATTERS_ARISING }
    }

    /** The committee's issued actions by section on the tenant's [today], each in its order. */
    private fun sections(
        forumId: UUID,
        since: Instant?,
        today: LocalDate,
    ): Map<ActionReportSection, List<ActionRow>> = ReportSections.of(candidates.forReport(forumId, since), today)

    /**
     * The rows shown, in section order, at most [ROWS_MAX]; and the rest. The latest-due open actions are left out first
     * (the tracker lists them), then the last rows.
     */
    private fun cap(
        sections: Map<ActionReportSection, List<ActionRow>>,
    ): Pair<List<Pair<ActionReportSection, ActionRow>>, List<ActionRow>> {
        val total = sections.values.sumOf { it.size }
        val over = (total - ROWS_MAX).coerceAtLeast(0)
        val open = sections[ActionReportSection.OPEN].orEmpty()
        val openKept = open.take((open.size - over).coerceAtLeast(0))
        val ordered =
            ActionReportSection.SHOWN.flatMap { s ->
                (if (s == ActionReportSection.OPEN) openKept else sections[s].orEmpty()).map { s to it }
            }
        val shown = ordered.take(ROWS_MAX)
        return shown to (open.drop(openKept.size) + ordered.drop(ROWS_MAX).map { it.second })
    }

    /** Each action's snapshot as the meeting is shown it. */
    private fun snapshots(
        rows: List<ActionRow>,
        today: LocalDate,
    ): Map<UUID, ReportSnapshot> {
        if (rows.isEmpty()) return emptyMap()
        val updates = candidates.latestUpdates(rows.map { it.id })
        val names = users.names(rows.flatMap { listOfNotNull(it.owner?.userId, it.holderUserId) }.toSet())
        val positions = rows.mapNotNull { it.owner?.positionId }.toSet().let { if (it.isEmpty()) emptyMap() else org.positions(it) }
        return rows.associate { row ->
            val owner = row.owner
            val ownerName = owner?.positionId?.let { positions[it]?.title } ?: owner?.userId?.let { names[it] }
            val holder = row.holderUserId?.takeIf { it != owner?.userId }?.let { names[it] }
            val update = updates[row.id]
            row.id to
                ReportSnapshot(
                    reference = row.reference.orEmpty(),
                    sourceLabel = row.sourceLabel,
                    title = row.title,
                    owner = ownerName.orEmpty(),
                    holder = holder,
                    dueDate = row.dueDate,
                    originalDueDate = row.originalDueDate,
                    dueRevisions = row.dueRevisions,
                    rag = RagRule.word(row.ragFacts, today),
                    status = row.status,
                    confidence = row.confidence,
                    latestUpdate = update?.first?.take(UPDATE_MAX),
                    latestUpdateAt = update?.second,
                    doneAt = row.doneAt,
                    closedAt = row.closedAt,
                    cancelKind = row.cancelKind,
                    cancelReason = row.cancelReason,
                )
        }
    }

    private fun input(
        section: ActionReportSection,
        row: ActionRow,
        snapshot: String,
    ) = ReportRowInput(section, row.id, snapshot, row.item, row.itemId, row.classificationId)

    companion object {
        /** At most this many rows are shown (design E07 §1.12). */
        const val ROWS_MAX = 500

        /** The latest update's first characters. */
        const val UPDATE_MAX = 500

        const val OBJECT_TYPE = "action_report"
        private const val EMPTY = "{}"
    }
}

/**
 * Which section each action of a report goes in (design E07 §1.12), on [today]: the tenant's date (`OrgDirectory.today`,
 * the organisation's zone), never the database's `current_date` or the server's zone. Overdue is the red-amber-green
 * rule's own test ([RagTest.PastDue]: past the due date), so the report and the colour agree on the tenant's day.
 */
object ReportSections {
    fun of(
        rows: List<ActionRow>,
        today: LocalDate,
    ): Map<ActionReportSection, List<ActionRow>> {
        val byDue = compareBy<ActionRow>({ it.dueDate }, { it.numberYear }, { it.sequence })
        val byEnd = compareBy<ActionRow>({ it.closedAt }, { it.numberYear }, { it.sequence })
        val (overdue, open) = rows.filter { it.status in ActionChecks.UNDERWAY }.partition { RagTest.PastDue.holds(it.ragFacts, today) }
        return mapOf(
            ActionReportSection.TO_CLOSE to
                rows.filter { it.status == ActionStatus.DONE_PENDING }.sortedWith(compareBy<ActionRow>({ it.doneAt }).then(byDue)),
            ActionReportSection.OVERDUE to overdue.sortedWith(byDue),
            ActionReportSection.OPEN to open.sortedWith(byDue),
            ActionReportSection.CLOSED to rows.filter { it.status == ActionStatus.CLOSED }.sortedWith(byEnd),
            ActionReportSection.CANCELLED to rows.filter { it.status == ActionStatus.CANCELLED }.sortedWith(byEnd),
            ActionReportSection.CORRECTIONS to emptyList(),
        )
    }
}

/** The meetings around a report and an update request (design E07 §1.12, §9 (b)). */
object ActionMeetings {
    /** The meetings that took place: a previous meeting's start bounds "since the last meeting". */
    private val TOOK_PLACE = setOf(MeetingStatus.IN_PROGRESS, MeetingStatus.HELD, MeetingStatus.MINUTES_APPROVED, MeetingStatus.CLOSED)

    /** The meetings still to come. */
    private val COMING = setOf(MeetingStatus.DRAFT, MeetingStatus.SCHEDULED)

    /** The latest of [forumMeetings] that took place and started before [meeting] did. */
    fun previous(
        forumMeetings: List<MeetingRef>,
        meeting: MeetingRef,
    ): MeetingRef? = before(forumMeetings, meeting.startsAt).firstOrNull { it.id != meeting.id }

    /** The latest of [forumMeetings] that took place and started before [at]. */
    fun before(
        forumMeetings: List<MeetingRef>,
        at: Instant,
    ): List<MeetingRef> = forumMeetings.filter { it.status in TOOK_PLACE && it.startsAt.isBefore(at) }.sortedByDescending { it.startsAt }

    /** The first of [forumMeetings] still to come after [now]: drafted or scheduled. */
    fun next(
        forumMeetings: List<MeetingRef>,
        now: Instant,
    ): MeetingRef? = forumMeetings.filter { it.status in COMING && it.startsAt.isAfter(now) }.minByOrNull { it.startsAt }

    /** Whether [meeting] is still to come (an action may be held back from its reports). */
    fun coming(meeting: MeetingRef): Boolean = meeting.status in COMING
}
