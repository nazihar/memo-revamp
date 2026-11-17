package com.memo.forum.internal

import com.memo.forum.ForumDirectory
import com.memo.forum.ForumRole
import com.memo.notification.NotificationKind
import com.memo.notification.Notifications
import com.memo.notification.RecipientLookup
import com.memo.org.OrgDirectory
import com.memo.tenancy.TenancyProperties
import com.memo.tenancy.TenantRegistry
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Tells people about membership changes and ending terms (design E01 §9). In the change's transaction (ADR-0030):
 * subjects carry nothing, bodies name the committee and link to it. The actor is never told about their own change.
 */
@Component
class ForumNotifier(
    private val directory: ForumDirectory,
    private val org: OrgDirectory,
    private val recipients: RecipientLookup,
    private val notifications: Notifications,
    private val registry: TenantRegistry,
    private val tenancy: TenancyProperties,
) {
    /** The chair and secretaries in office and the member concerned (the person, or the seat's holder). */
    fun membershipChanged(
        forum: ForumRow,
        row: ForumMemberRow,
    ) {
        val concerned = row.userId ?: row.positionId?.let { org.holders(listOf(it))[it] }
        val told = officers(forum.id) + listOfNotNull(concerned)
        send(told - setOfNotNull(actorOrNull()), NotificationKind.FORUM_MEMBERSHIP_CHANGED, forum)
    }

    /** A term ends in a few days: the secretaries in office, or [fallback] (administrators) when there are none. */
    fun termEnding(
        forum: ForumRow,
        member: String,
        fallback: () -> Set<UUID>,
    ) {
        val secretaries =
            directory
                .membersOn(forum.id)
                .filter { it.role == ForumRole.SECRETARY }
                .map { it.userId }
                .toSet()
        send(secretaries.ifEmpty { fallback() }, NotificationKind.FORUM_TERM_ENDING, forum, mapOf("member" to member))
    }

    private fun officers(forumId: UUID): Set<UUID> =
        directory
            .membersOn(forumId)
            .filter { it.role in Forums.TOLD }
            .map { it.userId }
            .toSet()

    private fun send(
        users: Set<UUID>,
        kind: NotificationKind,
        forum: ForumRow,
        extra: Map<String, String> = emptyMap(),
    ) {
        val tenant = registry.current()
        val link = "/committees/${forum.id}"
        users.forEach { user ->
            val recipient = recipients.recipient(user) ?: return@forEach
            notifications.notify(
                recipient,
                kind,
                mapOf(
                    "committee" to forum.name,
                    "organisation" to tenant?.name.orEmpty(),
                    "link" to (tenant?.let { tenancy.publicUrl(it.slug) }.orEmpty() + link),
                ) + extra,
                link,
            )
        }
    }
}
