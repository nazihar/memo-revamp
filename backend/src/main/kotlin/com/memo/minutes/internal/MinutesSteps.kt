package com.memo.minutes.internal

import com.memo.audit.AuditLog
import com.memo.common.authz.ForbiddenException
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.id.IdGenerator
import com.memo.common.web.FieldProblem
import com.memo.common.web.RefusedException
import com.memo.common.web.refuseIf
import com.memo.files.Files
import com.memo.forum.MinutesApproval
import com.memo.meeting.MeetingView
import com.memo.meeting.MeetingViewers
import com.memo.meeting.Meetings
import com.memo.notification.NotificationKind
import com.memo.workflow.WorkingDays
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.UUID

/** What the minutes' steps share (design E06 §1.4's lock order, If-Match, the history), inside a tenant transaction. */
@Component
class MinutesSteps(
    private val store: MinutesStore,
    private val viewers: MeetingViewers,
    private val meetings: Meetings,
    private val files: Files,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val workingDays: WorkingDays,
) {
    /** The deadline: the end of [until]'s working day (tomorrow at the earliest), or `circulationWorkingDays` from today. */
    fun deadline(
        row: MinutesRow,
        until: LocalDate?,
    ): Instant {
        val today = workingDays.today()
        requireNoProblems(
            listOfNotNull(
                FieldProblem("commentsUntil", "RANGE", "Give members until tomorrow at the earliest.").takeIf {
                    until != null &&
                        !until.isAfter(today)
                },
                FieldProblem(
                    "commentsUntil",
                    "RANGE",
                    "At most a year ahead.",
                ).takeIf { until != null && until.isAfter(today.plusYears(1)) },
            ),
        )
        val day =
            until ?: workingDays.after(today, row.policy.circulationWorkingDays)
                ?: today.plusDays(row.policy.circulationWorkingDays.toLong())
        return workingDays.closeOf(day)
    }

    /** The minutes locked (after the meeting's row: design E06 §1.4's lock order), at [version] (412 otherwise). */
    fun locked(
        view: MeetingView,
        version: Long,
    ): MinutesRow {
        meetings.locked(view.meeting.id)
        val row = store.lock(view.meeting.id) ?: throw MinutesNotFoundException("minutes")
        if (row.version != version) throw ConcurrentModificationException("minutes ${row.meetingId}", version)
        return row
    }

    /** The draft's current version, whose structure check (run before the transaction) is stored and sound. */
    fun checkedCurrent(row: MinutesRow): UUID {
        val current = row.fileId?.let(files::info)?.currentVersionId ?: throw RefusedException("NO_DRAFT", "Make the draft first.")
        refuseIf(row.checkedVersionId != current || row.structureOk != true, "STRUCTURE") {
            "A newer version of the draft was saved: check it and try again."
        }
        return current
    }

    /** A secretary of the committee or `forums.manage` (403), on a meeting they see (404). */
    fun manager(meetingId: UUID): MeetingView {
        val view = viewers.of(meetingId)
        if (!view.manages) throw ForbiddenException(null)
        return view
    }

    fun reasonOf(reason: String?): String {
        val why = reason?.trim()?.ifEmpty { null }
        requireNoProblems(
            listOfNotNull(
                FieldProblem("reason", "REASON_REQUIRED", "Say why.").takeIf { why == null },
                FieldProblem("reason", "LENGTH", "At most $MAX_NOTE characters.").takeIf { (why?.length ?: 0) > MAX_NOTE },
            ),
        )
        return checkNotNull(why)
    }

    fun save(
        before: MinutesRow,
        after: MinutesRow,
    ) = check(store.update(before, after)) { "the locked minutes moved on" }

    fun event(
        row: MinutesRow,
        kind: MinutesEventKind,
        actor: UUID? = actorOrNull(),
        onBehalfBy: UUID? = null,
        how: String? = null,
        note: String? = null,
        fileVersionId: UUID? = null,
    ) = store.insertEvent(
        MinutesEventRow(
            id = ids.next(),
            meetingId = row.meetingId,
            kind = kind,
            itemId = null,
            actor = actor,
            fileVersionId = fileVersionId,
            sha256 = null,
            at = now(),
            note = note,
            onBehalfBy = onBehalfBy,
            how = how,
        ),
    )

    fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)

    companion object {
        const val MAX_NOTE = 1000
        const val MAX_COMMENT = 2000
        const val MAX_ASKED = 20

        /** The steps that make or undo the reviewer's agreement. */
        val REVIEW_STEPS =
            setOf(
                MinutesEventKind.SENT_FOR_REVIEW,
                MinutesEventKind.AGREED,
                MinutesEventKind.RETURNED,
                MinutesEventKind.RETURNED_TO_DRAFT,
                MinutesEventKind.WITHDRAWN,
                MinutesEventKind.CIRCULATED,
            )

        fun sha256(text: String): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))
    }
}
