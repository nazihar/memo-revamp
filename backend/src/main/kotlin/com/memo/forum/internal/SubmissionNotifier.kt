package com.memo.forum.internal

import com.memo.forum.ForumDirectory
import com.memo.forum.ForumRole
import com.memo.notification.NotificationKind
import com.memo.notification.Notifications
import com.memo.notification.RecipientLookup
import com.memo.tenancy.TenancyProperties
import com.memo.tenancy.TenantRegistry
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Tells people about submissions (ADR-0059): the forum's secretaries when one arrives, the author and the presenter when
 * it is triaged or decided. In the change's transaction; subjects carry nothing, bodies name the committee and the status.
 */
@Component
class SubmissionNotifier(
    private val directory: ForumDirectory,
    private val store: ForumStore,
    private val recipients: RecipientLookup,
    private val notifications: Notifications,
    private val registry: TenantRegistry,
    private val tenancy: TenancyProperties,
) {
    fun received(
        forum: ForumRow,
        row: SubmissionRow,
    ) {
        val secretaries =
            directory
                .membersOn(forum.id)
                .filter { it.role == ForumRole.SECRETARY }
                .map { it.userId }
                .toSet()
        send(secretaries - setOfNotNull(actorOrNull()), NotificationKind.FORUM_SUBMISSION_RECEIVED, forum, row)
    }

    /** The waiting reminder: to the forum's secretaries in office, or the holders of [fallback] when it has none. */
    fun waiting(
        forum: ForumRow,
        row: SubmissionRow,
        fallback: () -> Collection<UUID>,
    ) {
        val secretaries =
            directory
                .membersOn(forum.id)
                .filter { it.role == ForumRole.SECRETARY }
                .map { it.userId }
                .toSet()
        send(secretaries.ifEmpty { fallback().toSet() }, NotificationKind.FORUM_SUBMISSION_WAITING, forum, row)
    }

    fun triaged(row: SubmissionRow) {
        val forum = store.find(row.forumId) ?: return
        send(
            setOfNotNull(row.submittedBy, row.presenterId) - setOfNotNull(actorOrNull()),
            NotificationKind.FORUM_SUBMISSION_TRIAGED,
            forum,
            row,
        )
    }

    private fun send(
        users: Set<UUID>,
        kind: NotificationKind,
        forum: ForumRow,
        row: SubmissionRow,
    ) {
        val tenant = registry.current()
        val link = "/committees/${forum.id}/submissions/${row.id}"
        users.forEach { user ->
            val recipient = recipients.recipient(user) ?: return@forEach
            notifications.notify(
                recipient,
                kind,
                mapOf(
                    "committee" to forum.name,
                    "status" to row.status.name,
                    "organisation" to tenant?.name.orEmpty(),
                    "link" to (tenant?.let { tenancy.publicUrl(it.slug) }.orEmpty() + link),
                ),
                link,
            )
        }
    }
}
