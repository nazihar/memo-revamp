package com.memo.actions.internal

import com.memo.audit.AuditLog
import com.memo.common.web.refuseIf
import com.memo.forum.ActionsPolicy
import com.memo.forum.ForumDirectory
import com.memo.forum.ForumPolicies
import com.memo.forum.StandingKind
import com.memo.identity.UserSummaries
import com.memo.meeting.ItemLink
import com.memo.meeting.ItemLinks
import com.memo.meeting.LineAccess
import com.memo.meeting.MeetingViewers
import com.memo.meeting.Meetings
import com.memo.memo.ReportDocument
import com.memo.memo.ReportPages
import com.memo.org.OrgDirectory
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import com.memo.memo.ReportSection as PdfSection

/** One row of the action taken report as the reader may see it: the snapshot taken when the agenda was published. */
data class ActionReportRowResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val actionId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val reference: String,
    /** "BRC 3/2026, item 5 — Resolution BRC/2026/014, condition 2": numbers only. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val sourceLabel: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val title: String,
    /** The position's title, or the person's name. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val owner: String,
    /** Who held the owner's task, when not the person owner. */
    val holder: String? = null,
    val dueDate: LocalDate? = null,
    val originalDueDate: LocalDate? = null,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val dueRevisions: Int,
    /** Red, amber or green in words, as it was at publication. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val rag: ActionRagResponse,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val status: ActionStatus,
    val confidence: Confidence? = null,
    /** The latest update's first 500 characters, and when it was posted. */
    val latestUpdate: String? = null,
    val latestUpdateAt: Instant? = null,
    val doneAt: Instant? = null,
    val closedAt: Instant? = null,
    /** Cancelled: how ("risk accepted", superseded, …) and why. */
    val cancelKind: CancelKind? = null,
    val cancelReason: String? = null,
    /**
     * The action's follow-up memos the reader may read now, joined at read time through the memo's own read rule (never
     * part of the frozen snapshot): a memo the reader may not read is left out, with neither its number nor its subject.
     */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val memos: List<ReportMemoResponse> = emptyList(),
    /** Proposed to close: whether the reader may close it now as agreed by the committee (the verifier's rule). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val canClose: Boolean = false,
)

/** A follow-up memo on a report's row, for a reader who may read the memo: its number, subject and status now. */
data class ReportMemoResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val memoId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val kind: MemoLinkKind,
    /** The memo's number; null until it is numbered. */
    val reference: String? = null,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val subject: String,
    /** The memo's status (DRAFT, IN_REVIEW, RETURNED, APPROVED, REJECTED, WITHDRAWN, DISCARDED, CANCELLED, CLOSED). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val status: String,
)

/** What the reader may do with the report now. */
data class ActionReportAbilities(
    /**
     * "Close the actions the committee agreed": the meeting runs, is held or has its minutes approved, and the reader may
     * verify at least one action proposed to close (each row's `canClose`).
     */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val closeAgreed: Boolean,
)

/** A section of the report with the reader's rows; [count] is theirs, from the same rule. */
data class ActionReportSectionResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val section: ActionReportSection,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val count: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val rows: List<ActionReportRowResponse>,
    /** Section 6 only: the corrections to earlier minutes approved since the last meeting (E06 T8). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val corrections: List<ActionReportCorrectionResponse> = emptyList(),
)

/** A correction to earlier minutes, as section 6 lists it: in words, with when it was approved. */
data class ActionReportCorrectionResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val label: String,
    val approvedAt: Instant? = null,
)

/**
 * The action taken report of a meeting's agenda revision (design E07 §1.12, flow AO), as the reader may see it: built when
 * the revision was published and never changed. Sections 1–6 in order, each with the reader's rows.
 */
data class ActionReportResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val reportId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val meetingId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val forumId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val committee: String,
    /** "BRC 4/2026". */
    val meetingLabel: String? = null,
    /** The agenda revision it was built for. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val revision: Int,
    /** Every revision of the meeting with a report, oldest first. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val revisions: List<Int>,
    /** The MATTERS_ARISING item it is shown under. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val itemId: UUID,
    /** When the agenda was published ("Built when the agenda was published on 28 November 2026 at 16:05"). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val builtAt: Instant,
    /** The previous meeting's start: "since the last meeting" counts from it; null when there was none. */
    val since: Instant? = null,
    val previousMeetingLabel: String? = null,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val sections: List<ActionReportSectionResponse>,
    /** The reader's actions beyond the report's 500 rows: "and 12 more actions; see the committee's tracker". */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val more: Int,
    /** For officers only: the actions held back by a secretary ("1 action held back by the secretary"). */
    val heldBack: Int? = null,
    /** "Action taken report: 2 to close, 3 overdue, 9 open, 4 closed since the last meeting". */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val summary: String,
    /** The key's numbers: the committee type's actions policy in force today (as the tracker's). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val key: ActionRagKey = ActionRagKey(ActionsPolicy.DEFAULT_AMBER_DAYS),
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val can: ActionReportAbilities = ActionReportAbilities(false),
)

/** Holding an action back from a meeting's report: why (1 to 500 characters), and the meeting (the next one by default). */
data class ReportHoldRequest(
    val reason: String? = null,
    val meetingId: UUID? = null,
)

/** The action held back, and the meeting whose reports leave it out. */
data class ReportHoldResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val actionId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val meetingId: UUID,
    val meetingLabel: String? = null,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val meetingStartsAt: Instant,
)

/** "Ask owners for an update": how many owners were asked. */
data class AskForUpdatesResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val asked: Int,
)

/** A report as one reader may see it: its header, their rows, and whether they are an officer of its committee. */
private class SeenReport(
    val header: ReportHeader,
    val viewer: ActionViewer,
    val rows: List<ReportRowSeen>,
) {
    val officer: Boolean get() = viewer.officer(header.forumId)

    fun count(section: ActionReportSection): Int = rows.count { it.section == section }
}

/**
 * Reading the action taken report (design E07 §3: `GET /meetings/{id}/action-report` and its PDF): for the readers of the
 * meeting's MATTERS_ARISING item who are officers or members of the committee today, or `forum.read` holders (the matrix:
 * no guest, item attendee or former member), 404 for everyone else; each reader gets the rows the one rule gives them
 * ([ActionRuleConditions.report]), so no row and no count of an item a reader is recused from. Every open is audited as a
 * document open (`actions.report.opened`, screen or PDF).
 */
@Service
class ActionReportReads(
    private val reports: ActionReportStore,
    private val rule: ActionRuleConditions,
    private val viewers: ActionViewers,
    private val meetings: Meetings,
    private val meetingViewers: MeetingViewers,
    private val directory: ForumDirectory,
    private val users: UserSummaries,
    private val pages: ReportPages,
    private val org: OrgDirectory,
    private val audit: AuditLog,
    private val json: JsonMapper,
    private val access: ActionAccess,
    private val memoViews: ActionMemoViews,
    private val policies: ForumPolicies,
) {
    /** The report of [meetingId]'s [revision] (its latest by default) as the actor may see it. */
    fun report(
        meetingId: UUID,
        revision: Int?,
    ): ActionReportResponse {
        val seen = seen(meetingId, revision)
        opened(seen, "SCREEN")
        return response(seen)
    }

    /** The same report as a PDF of the actor's rows ([ReportPages]), built on demand and never stored. */
    fun pdf(
        meetingId: UUID,
        revision: Int?,
    ): ByteArray {
        val seen = seen(meetingId, revision)
        opened(seen, "PDF")
        val report = response(seen)
        val reader = users.names(listOf(seen.viewer.userId))[seen.viewer.userId].orEmpty()
        val out = ByteArrayOutputStream()
        pages.render(ReportPdf.document(report, reader, org.zone()), out)
        return out.toByteArray()
    }

    /** The report's header and the actor's rows; 404 for no report, or a reader the matrix does not give it to. */
    private fun seen(
        meetingId: UUID,
        revision: Int?,
    ): SeenReport {
        val header = reports.find(meetingId, revision) ?: throw ActionNotFoundException("report")
        val viewer = viewers.actor()
        val forum = header.forumId
        notFoundUnless(viewer.officer(forum) || viewer.member(forum) || viewer.reader, "report")
        val line = meetingViewers.find(meetingId)?.lines?.get(header.itemId)
        notFoundUnless(line == LineAccess.FULL || line == LineAccess.TITLE, "report")
        return SeenReport(header, viewer, reports.rows(header.id, rule.report(viewer, forum)))
    }

    private fun opened(
        seen: SeenReport,
        format: String,
    ) = audit.record(
        "actions.report.opened",
        ActionReports.OBJECT_TYPE,
        seen.header.id.toString(),
        after =
            mapOf(
                "meetingId" to seen.header.meetingId.toString(),
                "revision" to seen.header.revision,
                "format" to format,
                "rows" to seen.rows.count { it.section in ActionReportSection.SHOWN },
            ),
    )

    private fun response(seen: SeenReport): ActionReportResponse {
        val header = seen.header
        val meeting = meetings.find(header.meetingId)
        val previous = header.previousMeetingId?.let(meetings::find)
        val shown = seen.rows.filter { it.section in ActionReportSection.SHOWN }
        // Live, per reader: the follow-up memos they may read now (the snapshot stays as it was built).
        val memos = memoViews.readableOf(shown.mapNotNull { it.actionId }.toSet())
        // "Close the actions the committee agreed": only while the meeting runs, is held or minuted, by the verifier's rule.
        val closing = meeting != null && meeting.status in ActionRules.RAISE_WINDOW
        val toClose = shown.filter { it.section == ActionReportSection.TO_CLOSE }.mapNotNull { it.actionId }
        val closable = if (closing) access.verifiable(seen.viewer, header.forumId, toClose) else emptySet()
        val policy = policies.current(header.forumId)?.actions ?: ActionsPolicy()
        return ActionReportResponse(
            reportId = header.id,
            meetingId = header.meetingId,
            forumId = header.forumId,
            committee = directory.forum(header.forumId)?.name.orEmpty(),
            meetingLabel = meeting?.label,
            revision = header.revision,
            revisions = reports.revisions(header.meetingId),
            itemId = header.itemId,
            builtAt = header.builtAt,
            since = header.since,
            previousMeetingLabel = previous?.label,
            sections =
                ActionReportSection.SHOWN.map { section ->
                    val rows = seen.rows.filter { it.section == section }
                    ActionReportSectionResponse(
                        section,
                        seen.count(section),
                        rows.mapNotNull { r ->
                            r.actionId?.let {
                                row(it, r.snapshot).copy(memos = memos[it].orEmpty(), canClose = it in closable)
                            }
                        },
                        rows.filter { it.actionId == null }.map { correction(it.snapshot) },
                    )
                },
            more = seen.count(ActionReportSection.MORE),
            heldBack = seen.count(ActionReportSection.HELD_BACK).takeIf { seen.officer },
            summary = ReportWords.summary(ActionReportSection.SHOWN.associateWith(seen::count)),
            key = ActionRagKey(policy.amberWorkingDays, policy.staleWorkingDays.takeIf { it != ActionsPolicy.OFF }),
            can = ActionReportAbilities(closeAgreed = closable.isNotEmpty()),
        )
    }

    private fun correction(snapshot: String): ActionReportCorrectionResponse =
        json.readValue(snapshot, CorrectionSnapshot::class.java).let { ActionReportCorrectionResponse(it.label, it.approvedAt) }

    private fun row(
        actionId: UUID,
        snapshot: String,
    ): ActionReportRowResponse {
        val s = json.readValue(snapshot, ReportSnapshot::class.java)
        return ActionReportRowResponse(
            actionId = actionId,
            reference = s.reference,
            sourceLabel = s.sourceLabel,
            title = s.title,
            owner = s.owner,
            holder = s.holder,
            dueDate = s.dueDate,
            originalDueDate = s.originalDueDate,
            dueRevisions = s.dueRevisions,
            rag = ActionRagResponse(s.rag.colour, s.rag),
            status = s.status,
            confidence = s.confidence,
            latestUpdate = s.latestUpdate,
            latestUpdateAt = s.latestUpdate?.let { s.latestUpdateAt },
            doneAt = s.doneAt,
            closedAt = s.closedAt,
            cancelKind = s.cancelKind,
            cancelReason = s.cancelReason,
        )
    }

    /** For the item link: the actor's counts per section of [header], or null when the report is not theirs to read. */
    fun counts(header: ReportHeader): Map<ActionReportSection, Int>? {
        val viewer = viewers.actor()
        val forum = header.forumId
        if (!(viewer.officer(forum) || viewer.member(forum) || viewer.reader)) return null
        val rows = reports.rows(header.id, rule.report(viewer, forum))
        return ActionReportSection.SHOWN.associateWith { s -> rows.count { it.section == s } }
    }
}

/**
 * The MATTERS_ARISING item's link to its report on the members' agenda (design E07 §1.12, through the meeting's item-links
 * port): "Action taken report: 2 to close, 3 overdue, 9 open, 4 closed since the last meeting", the counts the reader's
 * own (the same rule as the rows). Nothing for a reader the report is not given to, or before a report is built.
 */
@Component
class ActionReportLinks(
    private val reports: ActionReportStore,
    private val reads: ActionReportReads,
) : ItemLinks {
    override val standingKind: StandingKind = StandingKind.MATTERS_ARISING

    override fun links(
        meetingId: UUID,
        itemId: UUID,
    ): List<ItemLink> {
        val header = reports.find(meetingId, null)?.takeIf { it.itemId == itemId }
        val counts = header?.let(reads::counts) ?: return emptyList()
        return listOf(
            ItemLink(
                kind = KIND,
                meetingId = meetingId,
                label = ReportWords.summary(counts),
                counts = counts.mapKeys { it.key.name },
                state = BUILT,
                at = header.builtAt,
            ),
        )
    }

    companion object {
        const val KIND = "ACTION_REPORT"
        const val BUILT = "BUILT"
    }
}

/**
 * Holding an action back from a meeting's report (design E07 §1.12, `POST /actions/{id}/report-hold`): a secretary of the
 * committee or `forums.manage`, not recused from its item, with a reason; for the committee's next meeting (or the one
 * named, still to come), whose reports built afterwards leave it out and count it for officers. It stays open in the
 * tracker. Recorded on the action's timeline (`HELD_BACK`) and audited `actions.report.held` (the reason's hash).
 */
@Service
class ActionReportHolds(
    private val scenes: ActionScenes,
    private val lines: ActionLines,
    private val reports: ActionReportStore,
    private val meetings: Meetings,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    fun hold(
        actionId: UUID,
        input: ReportHoldRequest,
    ): ReportHoldResponse {
        val scene = scenes.scene(actionId)
        scenes.requireManager(scene)
        val row = scene.row
        refuseIf(row.reference == null, ActionRules.WRONG_STATE) { "A draft is on no report." }
        val reason = input.reason?.trim()
        requireNoProblems(listOfNotNull(ActionChecks.reason(reason)))
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val forumMeetings = meetings.ofForum(row.forumId)
        val meeting =
            input.meetingId?.let { id -> forumMeetings.firstOrNull { it.id == id } ?: throw ActionNotFoundException("meeting") }
                ?: ActionMeetings.next(forumMeetings, now)
        refuseIf(meeting == null, NO_MEETING) { "The committee has no meeting to come: there is no report to hold it back from." }
        checkNotNull(meeting)
        refuseIf(!ActionMeetings.coming(meeting), ActionRules.WRONG_STATE) { "That meeting has started: its report stays as it was." }
        refuseIf(!reports.hold(row.id, meeting.id, checkNotNull(reason), scene.viewer.userId, now), ALREADY_HELD) {
            "This action is already held back from that meeting's report."
        }
        lines.entry(row.id, HELD_BACK, scene.viewer.userId, mapOf("meetingId" to meeting.id.toString()), now, EntryMore(text = reason))
        audit.record(
            "actions.report.held",
            ActionItemEffects.OBJECT_TYPE,
            row.id.toString(),
            after = mapOf("meetingId" to meeting.id.toString(), "reasonSha256" to ActionRules.sha256(reason)),
        )
        return response(row.id, meeting)
    }

    /** The answer for [actionId] held back from [meetingId] (a replay of the key). */
    fun held(
        actionId: UUID,
        meetingId: UUID,
    ): ReportHoldResponse = response(actionId, checkNotNull(meetings.find(meetingId)))

    private fun response(
        actionId: UUID,
        meeting: com.memo.meeting.MeetingRef,
    ) = ReportHoldResponse(actionId, meeting.id, meeting.label, meeting.startsAt)

    companion object {
        const val HELD_BACK = "HELD_BACK"
        const val ALREADY_HELD = "ALREADY_HELD"
        const val NO_MEETING = "NO_MEETING"
    }
}

/** The report's words in English: the summary line, and the PDF's sections (ReportPages draws English only, ADR-0052). */
internal object ReportWords {
    private val SUMMARY =
        listOf(
            ActionReportSection.TO_CLOSE to "to close",
            ActionReportSection.OVERDUE to "overdue",
            ActionReportSection.OPEN to "open",
            ActionReportSection.CLOSED to "closed since the last meeting",
            ActionReportSection.CANCELLED to "cancelled since the last meeting",
            ActionReportSection.CORRECTIONS to "corrections to earlier minutes",
        )

    /** "Action taken report: 2 to close, 3 overdue, 9 open, 4 closed since the last meeting". */
    fun summary(counts: Map<ActionReportSection, Int>): String {
        val parts = SUMMARY.mapNotNull { (section, words) -> counts[section]?.takeIf { it > 0 }?.let { "$it $words" } }
        return "Action taken report: " + (parts.joinToString(", ").ifEmpty { "nothing to report" })
    }

    val HEADINGS =
        mapOf(
            ActionReportSection.TO_CLOSE to "Proposed to close",
            ActionReportSection.OVERDUE to "Overdue",
            ActionReportSection.OPEN to "Open",
            ActionReportSection.CLOSED to "Closed since the last meeting, for noting",
            ActionReportSection.CANCELLED to "Cancelled since the last meeting, for noting",
            ActionReportSection.CORRECTIONS to "Corrections to earlier minutes",
        )

    val RAG =
        mapOf(
            RagWord.DRAFT to "Draft",
            RagWord.CLOSED to "Closed",
            RagWord.CANCELLED to "Cancelled",
            RagWord.TO_VERIFY to "Done, to be verified",
            RagWord.OVERRIDE_RED to "Red (set by the committee)",
            RagWord.OVERRIDE_AMBER to "Amber (set by the committee)",
            RagWord.OVERRIDE_GREEN to "Green (set by the committee)",
            RagWord.OVERDUE to "Red: overdue",
            RagWord.OFF_TRACK to "Red: off track",
            RagWord.DUE_SOON to "Amber: due soon",
            RagWord.AT_RISK to "Amber: at risk",
            RagWord.NO_RECENT_UPDATE to "Amber: no recent update",
            RagWord.ON_TRACK to "Green: on track",
        )

    val CANCELLED =
        mapOf(
            CancelKind.NO_LONGER_NEEDED to "No longer needed",
            CancelKind.SUPERSEDED to "Superseded",
            CancelKind.RISK_ACCEPTED to "Risk accepted",
            CancelKind.DISCARDED to "Discarded",
            CancelKind.DUPLICATE to "Duplicate",
        )
}

/** The reader's rows of a report as ReportPages draws them: a section per part of the report, then a block per row. */
internal object ReportPdf {
    private val DATE =
        java.time.format.DateTimeFormatter
            .ofPattern("d MMMM yyyy", java.util.Locale.ENGLISH)
    private val TIME =
        java.time.format.DateTimeFormatter
            .ofPattern("d MMMM yyyy 'at' HH:mm", java.util.Locale.ENGLISH)

    fun document(
        report: ActionReportResponse,
        reader: String,
        zone: java.time.ZoneId,
    ): ReportDocument {
        val meeting = report.meetingLabel ?: "the next meeting"
        val built = TIME.format(report.builtAt.atZone(zone))
        val parts =
            report.sections.flatMapIndexed { i, section ->
                val heading = "${i + 1}. ${ReportWords.HEADINGS.getValue(section.section)} (${section.count})"
                val intro =
                    when {
                        section.count == 0 -> listOf("None.")
                        section.section == ActionReportSection.TO_CLOSE -> listOf("The committee is asked to agree that these are done.")
                        else -> emptyList()
                    }
                val fixes =
                    section.corrections.map {
                        it.label to
                            (it.approvedAt?.let { at -> "approved " + DATE.format(at.atZone(zone)) } ?: "")
                    }
                listOf(PdfSection(heading, intro, fixes)) + section.rows.map { row(it, zone) }
            }
        val tail =
            listOfNotNull(
                "And ${report.more} more actions: see the committee's action tracker.".takeIf { report.more > 0 },
                report.heldBack?.takeIf { it > 0 }?.let { "$it ${if (it == 1) "action" else "actions"} held back by the secretary." },
            )
        return ReportDocument(
            title = "Action taken report",
            header = "${report.committee}, $meeting: action taken report",
            footer = "Built when the agenda was published on $built (revision ${report.revision}). Printed for $reader.",
            sections =
                listOf(PdfSection("${report.committee}, $meeting", listOf("Built when the agenda was published on $built."))) +
                    parts + (if (tail.isEmpty()) emptyList() else listOf(PdfSection("Not listed", tail))),
        )
    }

    private fun row(
        row: ActionReportRowResponse,
        zone: java.time.ZoneId,
    ): PdfSection {
        val owner = row.holder?.let { "${row.owner} ($it)" } ?: row.owner
        val lines =
            listOfNotNull(
                "Source" to row.sourceLabel,
                "Owner" to owner,
                due(row)?.let { "Due" to it },
                "Status" to ReportWords.RAG.getValue(row.rag.word),
                row.latestUpdate?.let { text ->
                    "Latest update" to (row.latestUpdateAt?.let { "${DATE.format(it.atZone(zone))}: " } ?: "") + text
                },
            ) + ended(row, zone) + row.memos.map { "Follow-up memo" to memo(it) }
        return PdfSection("${row.reference}  ${row.title}", lines = lines)
    }

    /** "30 October 2026 (originally 2 October 2026, revised twice)". */
    private fun due(row: ActionReportRowResponse): String? =
        row.dueDate?.let { date ->
            val revised = row.originalDueDate?.takeIf { row.dueRevisions > 0 }
            DATE.format(date) + (revised?.let { " (originally ${DATE.format(it)}, revised ${times(row.dueRevisions)})" } ?: "")
        }

    /** When it was closed, or how and why it was cancelled. */
    private fun ended(
        row: ActionReportRowResponse,
        zone: java.time.ZoneId,
    ): List<Pair<String, String>> {
        val on = row.closedAt?.let { DATE.format(it.atZone(zone)) }
        return listOfNotNull(
            on?.takeIf { row.status == ActionStatus.CLOSED }?.let { "Closed" to it },
            row.cancelKind?.let { kind ->
                val why = row.cancelReason?.let { ": $it" } ?: ""
                "Cancelled" to ReportWords.CANCELLED.getValue(kind) + (on?.let { " on $it" } ?: "") + why
            },
        )
    }

    /** "MEMO/2026/0412 (approved): Revised liquidity plan", the reader's own view of the memo now. */
    private fun memo(memo: ReportMemoResponse): String {
        val status = memo.status.lowercase().replace('_', ' ')
        return (memo.reference ?: "Memo not yet numbered") + " ($status): " + memo.subject
    }

    private fun times(n: Int): String =
        when (n) {
            1 -> "once"
            2 -> "twice"
            else -> "$n times"
        }
}
