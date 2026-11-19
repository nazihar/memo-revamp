package com.memo.meeting.internal

import com.memo.common.id.IdGenerator
import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import com.memo.forum.AgendaLimits
import com.memo.forum.ForumDirectory
import com.memo.forum.ForumPolicies
import com.memo.forum.ForumRef
import com.memo.forum.ForumSubmissionChanged
import com.memo.forum.ForumTypePolicy
import com.memo.forum.LateMode
import com.memo.forum.SubmissionPlacements
import com.memo.forum.SubmissionStatus
import com.memo.meeting.MeetingStatus
import com.memo.memo.MemoPack
import com.memo.memo.MemoPaperSource
import com.memo.memo.MemoPapers
import com.memo.memo.MemoReaders
import com.memo.workflow.WorkingDays
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.util.UUID

/**
 * Late papers (design E03 §1.4): the paper deadline is the end of the working day [SubmissionPolicy.deadlineWorkingDays]
 * working days before the meeting's date, on the tenant's business calendar, compared in the meeting's zone. What is
 * placed after it follows the type version's late mode; what is added while the meeting is in progress is tabled.
 */
@Component
class AgendaLateness(
    private val workingDays: WorkingDays,
    private val store: MeetingStore,
    private val directory: ForumDirectory,
    private val policies: ForumPolicies,
) {
    /** The pinned type version's policies of [meeting]. */
    fun policy(meeting: MeetingRow): ForumTypePolicy {
        val forum = forum(meeting)
        return checkNotNull(policies.version(forum.typeId, meeting.typeVersion)) { "type version ${meeting.typeVersion}" }
    }

    fun forum(meeting: MeetingRow): ForumRef = checkNotNull(directory.forum(meeting.forumId)) { "forum ${meeting.forumId}" }

    /** The last day papers are on time; null when the calendar has no working day to count. */
    fun deadline(meeting: MeetingRow): LocalDate? = workingDays.before(meeting.date, policy(meeting).submission.deadlineWorkingDays)

    /** Whether today, in the meeting's zone, is past [deadline]. */
    fun passed(
        meeting: MeetingRow,
        deadline: LocalDate?,
    ): Boolean =
        deadline != null &&
            store
                .now()
                .atZoneSameInstant(meeting.zone)
                .toLocalDate()
                .isAfter(deadline)

    /**
     * The lateness of something placed now: tabled while the meeting is in progress; on time before the deadline; after it,
     * LATE (ALLOW), AWAITING_CHAIR (CHAIR_APPROVAL), or refused with 409 `PAPER_DEADLINE_PASSED` (BLOCK).
     */
    fun of(meeting: MeetingRow): Lateness {
        val inProgress = meeting.status == MeetingStatus.IN_PROGRESS
        val late = !inProgress && passed(meeting, deadline(meeting))
        val mode = policy(meeting).submission.lateMode
        refuseIf(late && mode == LateMode.BLOCK, "PAPER_DEADLINE_PASSED") {
            "The papers for this meeting were due by the deadline; this committee takes late papers at a later meeting."
        }
        return when {
            inProgress -> Lateness.TABLED
            !late -> Lateness.NONE
            mode == LateMode.ALLOW -> Lateness.LATE
            else -> Lateness.AWAITING_CHAIR
        }
    }
}

/**
 * Submissions on the draft (design E03 §1.8): placed from the tray with the memo's subject, cover sheet and pack (its final
 * record or document, then its attachments) and the late mode; kept in step with the forum, so a submission returned,
 * declined, referred, deferred or withdrawn leaves the draft of a meeting not yet started; released to the tray when the
 * meeting is cancelled; and following the meeting's date when it is rescheduled.
 */
@Service
class AgendaPlacing(
    private val guard: AgendaGuard,
    private val writes: AgendaWrites,
    private val placements: SubmissionPlacements,
    private val memoPapers: MemoPapers,
    private val people: AgendaPeople,
    private val lateness: AgendaLateness,
    private val notifier: MeetingNotifier,
    private val items: ItemStore,
    private val papers: PaperStore,
    private val meetings: MeetingStore,
    private val revisions: RevisionStore,
    private val ids: IdGenerator,
    private val carryOver: DeclarationCarryOver,
) {
    /** Places the submission of [input] on the draft open in [edit]: accepted first when queued (design E03 §1.8). */
    fun place(
        edit: AgendaEdit,
        input: ItemInput,
    ): UUID {
        val meeting = edit.meeting
        val submissionId = input.submissionId
        requireNoProblems(
            listOfNotNull(FieldProblem("submissionId", "REQUIRED", "Choose a submission from the tray.").takeIf { submissionId == null }),
        )
        val submission =
            placements.find(checkNotNull(submissionId))?.takeIf { it.forumId == meeting.forumId }
                ?: throw MeetingNotFoundException("submission")
        val late = lateness.of(meeting)
        val parent = input.parentId?.let(edit::item)
        val sectionId = writes.target(edit, input.sectionId, parent)
        val pack = submission.takeIf { it.subjectType == MemoReaders.SUBJECT_TYPE }?.let { memoPapers.pack(it.subjectId) }
        val level = pack?.let { people.higher(meeting.classificationId, it.classificationId) } ?: meeting.classificationId
        val title = checked(edit, input, pack, level, parent != null)
        val presenter = submission.presenterId?.takeIf { it in people.active(listOf(it)) && people.uncleared(listOf(it), level).isEmpty() }
        val row =
            ItemRow(
                id = ids.next(),
                meetingId = meeting.id,
                sectionId = sectionId,
                parentId = parent?.id,
                position = 0,
                kind = ItemKind.SUBMISSION,
                standingKind = null,
                submissionId = submission.id,
                memoId = pack?.memoId,
                title = title,
                description = null,
                purpose = submission.purpose,
                resolutionSought = submission.resolutionSought?.take(AgendaRules.MAX_DESCRIPTION),
                minutes = input.minutes,
                presenters = listOfNotNull(presenter?.let { Presenter(userId = it) }),
                restrictedTo = null,
                titleHidden = false,
                classificationId = level,
                late = late,
                addedAfterRevision = edit.agenda.currentRevision,
            )
        AgendaRules.requireOneLevel(row, parent, hasChildren = false)
        placements.place(meeting.forumId, submission.id, meeting.id, meeting.date)
        writes.insert(edit, row, input.position)
        presenter?.let { items.setAttendees(row.id, setOf(it)) }
        pack?.let { addPack(row, it) }
        writes.record("meeting.agenda.item_added", meeting.id, after = AgendaWrites.audited(row) + ("papers" to pack?.files?.size))
        carryOver.placed(meeting, row)
        if (late == Lateness.AWAITING_CHAIR) requested(meeting, mapOf("itemId" to row.id.toString()), row.scope)
        return row.id
    }

    /** The item's title (the memo's subject unless one is sent), once the placement's fields pass their checks. */
    private fun checked(
        edit: AgendaEdit,
        input: ItemInput,
        pack: MemoPack?,
        level: UUID,
        subItem: Boolean,
    ): String {
        val minutes = input.minutes
        val (title, titleProblem) =
            AgendaRules.title(
                "title",
                input.title ?: pack?.subject?.take(AgendaLimits.MAX_ITEM_TITLE) ?: TITLE,
                AgendaLimits.MAX_ITEM_TITLE,
            )
        requireNoProblems(
            listOfNotNull(
                titleProblem,
                people.level("submissionId", level, level),
                FieldProblem("minutes", "RANGE", "0 to ${AgendaLimits.MAX_MINUTES} minutes, and none on a sub-item.")
                    .takeIf { minutes != null && (subItem || minutes !in 0..AgendaLimits.MAX_MINUTES) },
                AgendaRules.capProblem(edit.rows.sections.size, edit.rows.items.size + 1),
            ),
        )
        return title
    }

    /**
     * A late item or paper waits for the chair: audited, and who decides for the item whose [scope] is given is told (the
     * chair, the vice-chair when the chair is recused from it or there is none; design E04 §1.7).
     */
    fun requested(
        meeting: MeetingRow,
        what: Map<String, Any?>,
        scope: Collection<UUID>,
    ) {
        writes.record("meeting.late.requested", meeting.id, after = what)
        notifier.latePaper(meeting, lateness.forum(meeting), scope)
    }

    /**
     * A submission changed in the forum (ADR-0059): returned, declined, referred, deferred or withdrawn, it leaves the
     * draft of a meeting not yet started, and the forum clears its placement. Once the meeting has started the item stays:
     * what happens to it at the meeting is E06's. When it was on the published agenda, the secretaries are told to publish
     * a new revision (`MEETING_ITEM_WITHDRAWN`).
     *
     * The forum has locked the submission's row; the agenda's is taken next. Every change that places or unplaces a
     * submission takes the two in this order (design E03 §1.8, as built in T3), so none waits on this one in a cycle.
     */
    @EventListener
    fun onSubmissionChanged(event: ForumSubmissionChanged) {
        if (event.status !in LEAVES) return
        val item = items.ofSubmission(event.submissionId)
        val meeting = item?.let { meetings.find(it.meetingId) }?.takeIf { AgendaChanges.of(it.status) == AgendaChanges.ANY }
        val edit = meeting?.let { guard.system(it) }
        val live = edit?.rows?.items?.firstOrNull { it.id == item.id }
        if (edit != null && live != null) {
            writes.removeItem(edit, live, RemovalReason.SUBMISSION_CHANGED)
            // Members still read it in the published revision: the secretaries publish a new one.
            if (revisions.inLatest(edit.meeting.id, live.id)) {
                notifier.itemWithdrawn(edit.meeting, lateness.forum(edit.meeting), live.scope)
            }
            guard.done(edit.meeting.id)
        }
    }

    /**
     * [meeting] was cancelled (design E03 §1.1, decided in T2): its agenda freezes, and its submission items are removed
     * with the reason MEETING_CANCELLED, so the submissions go back to the tray, still accepted. Audited per item, per
     * submission (`forum.submission.unplaced`) and once as `meeting.agenda.released`.
     */
    fun released(meeting: MeetingRow) {
        var edit = guard.system(meeting) ?: return
        var count = 0
        while (true) {
            val next = edit.rows.items.firstOrNull { it.kind == ItemKind.SUBMISSION } ?: break
            writes.removeItem(edit, next, RemovalReason.MEETING_CANCELLED)
            count++
            edit = checkNotNull(guard.system(meeting))
        }
        writes.record("meeting.agenda.released", meeting.id, after = mapOf("submissions" to count))
        guard.done(meeting.id)
    }

    /** [meeting] was rescheduled: its placements follow its new date. */
    fun redate(meeting: MeetingRow) = placements.redate(meeting.id, meeting.date)

    private fun addPack(
        item: ItemRow,
        pack: MemoPack,
    ) {
        pack.files.forEachIndexed { i, file ->
            papers.insert(
                PaperRow(
                    id = ids.next(),
                    meetingId = item.meetingId,
                    itemId = item.id,
                    position = i,
                    source = file.source.paper(),
                    fileId = file.fileId,
                    title = file.title.take(AgendaLimits.MAX_ITEM_TITLE),
                    restrictedTo = null,
                    classificationId = people.higher(item.classificationId, file.classificationId),
                    late = Lateness.NONE,
                ),
                item.addedAfterRevision,
                actorOrNull(),
            )
        }
    }

    private companion object {
        /** A submission's subject other than a memo has no subject line to take. */
        const val TITLE = "Paper for the committee"

        /** The statuses that take a submission off a draft. */
        val LEAVES =
            setOf(
                SubmissionStatus.RETURNED,
                SubmissionStatus.DECLINED,
                SubmissionStatus.REFERRED,
                SubmissionStatus.DEFERRED,
                SubmissionStatus.WITHDRAWN,
            )
    }
}

/** The paper source of a memo file. */
fun MemoPaperSource.paper(): PaperSource =
    when (this) {
        MemoPaperSource.DOCUMENT -> PaperSource.MEMO_DOCUMENT
        MemoPaperSource.ATTACHMENT -> PaperSource.MEMO_ATTACHMENT
        MemoPaperSource.RECORD -> PaperSource.MEMO_RECORD
    }
