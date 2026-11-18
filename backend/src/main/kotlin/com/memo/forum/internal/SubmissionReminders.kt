package com.memo.forum.internal

import com.memo.audit.AuditLog
import com.memo.common.authz.AccessAdministration
import com.memo.common.authz.Permission
import com.memo.workflow.WorkingDays
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

/**
 * The waiting reminder (ADR-0059, design E02 §9): a submission still QUEUED after its forum type's `triageWorkingDays`
 * (working days in the tenant's calendar) is chased once, to the forum's secretaries. `reminded_at` keeps re-runs from
 * sending twice. Runs in the forum module's hourly per-tenant task, inside a tenant transaction.
 */
@Component
class SubmissionReminders(
    private val store: SubmissionStore,
    private val forums: ForumStore,
    private val types: ForumTypeVersionStore,
    private val notifier: SubmissionNotifier,
    private val workingDays: WorkingDays,
    private val access: AccessAdministration,
    private val audit: AuditLog,
) {
    /** Sends what is due in the current tenant; returns how many reminders went out. */
    fun remindWaiting(): Int {
        var sent = 0
        val waiting = store.unreminded(BATCH)
        val forumsById = forums.all().associateBy { it.id }
        val limits = mutableMapOf<UUID, Int?>()
        waiting.forEach { row ->
            val forum = forumsById[row.forumId] ?: return@forEach
            val limit =
                limits.getOrPut(forum.id) {
                    types
                        .published(forum.typeId)
                        ?.settings
                        ?.submissionPolicy
                        ?.triageWorkingDays
                }
                    ?: return@forEach
            // Working days never outnumber calendar days: most rows are too young to need the calendar.
            if (Duration.between(row.submittedAt, store.now().toInstant()).toDays() + 1 < limit) return@forEach
            val age = workingDays.since(row.submittedAt)
            if (age < limit || !store.reminded(row.id)) return@forEach
            notifier.waiting(forum, row) { access.tenantWideHolders(Permission.FORUMS_MANAGE) }
            audit.record(
                "forum.submission.reminded",
                ForumSubmissions.OBJECT_TYPE,
                row.id.toString(),
                after = mapOf("forumId" to forum.id.toString(), "workingDays" to age),
            )
            sent++
        }
        return sent
    }

    private companion object {
        /** The oldest queued submissions looked at per run; the rest wait for the next hour. */
        const val BATCH = 1000
    }
}
