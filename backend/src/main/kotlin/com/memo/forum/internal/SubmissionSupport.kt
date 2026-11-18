package com.memo.forum.internal

import com.memo.audit.AuditLog
import com.memo.common.authz.ForbiddenException
import com.memo.common.authz.Permission
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import com.memo.forum.ForumRole
import com.memo.forum.ForumSubmissionChanged
import com.memo.forum.SubmissionStatus
import com.memo.org.OrgDirectory
import org.jooq.Field
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.UUID

/**
 * What submission intake, triage and decisions share (ADR-0059): who is an officer of a forum, guarded status moves, the
 * audit-notify-publish ending of every change, and the reason and date checks.
 */
@Component
class SubmissionSupport(
    private val store: SubmissionStore,
    private val forums: ForumStore,
    private val access: ForumAccess,
    private val notifier: SubmissionNotifier,
    private val org: OrgDirectory,
    private val audit: AuditLog,
    private val events: ApplicationEventPublisher,
    private val readers: SubmissionReaders,
) {
    /** The forum's chair or secretary in office, or an administrator. */
    fun officer(forum: ForumRow): Boolean =
        access.managesAll() ||
            access.ownRows(forum).any { (it.role == ForumRole.SECRETARY || it.role == ForumRole.CHAIR) && it.inOffice(org.today()) }

    /** A submission the actor may triage (a secretary in office or an administrator), in one of [allowed] (none: any open). */
    fun triageable(
        forumId: UUID,
        id: UUID,
        allowed: Set<SubmissionStatus>,
    ): SubmissionRow {
        val forum = access.requireVisible(forumId)
        if (!access.managesAll() && !access.isSecretary(forum)) throw ForbiddenException(Permission.FORUMS_MANAGE)
        val row = store.find(id)?.takeIf { it.forumId == forum.id } ?: throw ForumNotFoundException("submission")
        val states = allowed.ifEmpty { ForumSubmissions.OPEN }
        refuseIf(row.status !in states, "WRONG_STATE") { "This submission is ${row.status.name.lowercase()}." }
        return row
    }

    fun move(
        row: SubmissionRow,
        to: SubmissionStatus,
        version: Long,
        fields: Map<out Field<*>, Any?>,
    ) {
        if (!store.move(row, to, version, fields)) throw ConcurrentModificationException("submission ${row.id}", version)
    }

    /** Audits [action] with before and after, tells the author and presenter when [notify], and publishes the change. */
    fun done(
        action: String,
        before: SubmissionRow,
        notify: Boolean = true,
    ): SubmissionRow {
        val after = checkNotNull(store.find(before.id))
        audit.record(
            action,
            ForumSubmissions.OBJECT_TYPE,
            after.id.toString(),
            before = ForumSubmissions.audited(before),
            after = ForumSubmissions.audited(after),
        )
        if (notify) notifier.triaged(after)
        changed(after)
        return after
    }

    /** Keeps the memo's committee readers in step (ADR-0059) and publishes the change with the status it left. */
    fun changed(row: SubmissionRow) {
        readers.sync(row.subjectType, row.subjectId)
        val status = store.find(row.id)?.status ?: row.status
        events.publishEvent(ForumSubmissionChanged(forums.tenant(), row.id, status))
    }

    fun requiredReason(reason: String?): String {
        val text = reason?.trim()?.ifEmpty { null }
        requireNoProblems(
            listOfNotNull(
                FieldProblem("reason", "REASON_REQUIRED", "Say why.").takeIf { text == null },
                tooLong("reason", text),
            ),
        )
        return checkNotNull(text)
    }

    fun tooLong(
        field: String,
        text: String?,
    ): FieldProblem? =
        FieldProblem(field, "LENGTH", "At most ${ForumSubmissions.MAX_TEXT} characters.").takeIf {
            (text?.length ?: 0) > ForumSubmissions.MAX_TEXT
        }

    fun pastTarget(date: LocalDate?): FieldProblem? =
        FieldProblem("targetDate", "TARGET_IN_PAST", "Choose today or a later date.").takeIf { date != null && date.isBefore(org.today()) }
}
