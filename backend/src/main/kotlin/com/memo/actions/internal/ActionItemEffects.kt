package com.memo.actions.internal

import com.memo.actions.ActionChange
import com.memo.actions.ActionChanged
import com.memo.audit.AuditLog
import com.memo.common.tenant.TenantContext
import com.memo.forum.ForumDirectory
import com.memo.notification.NotificationKind
import com.memo.notification.Notifications
import com.memo.notification.RecipientLookup
import com.memo.tenancy.TenancyProperties
import com.memo.tenancy.TenantRegistry
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * What follows a change of an action (design E07 §2.3, §5), in its transaction: the audit entry with ids, kinds, dates
 * and the SHA-256 of what people wrote (never the text), the notices (the committee, the reference and the date, never
 * the action's title or text), and [ActionChanged] for E08.
 */
@Component
class ActionItemEffects(
    private val audit: AuditLog,
    private val events: ApplicationEventPublisher,
    private val recipients: RecipientLookup,
    private val notifications: Notifications,
    private val registry: TenantRegistry,
    private val tenancy: TenancyProperties,
    private val directory: ForumDirectory,
) {
    /** A draft was raised. */
    fun drafted(
        row: ActionRow,
        contributors: List<UUID>,
    ) = audit.record(
        "actions.action.drafted",
        OBJECT_TYPE,
        row.id.toString(),
        after =
            mapOf(
                "forumId" to row.forumId.toString(),
                "source" to row.source.name,
                "meetingId" to row.meetingId?.toString(),
                "itemId" to row.itemId?.toString(),
                "decisionId" to row.decisionId?.toString(),
                "conditionIndex" to row.conditionIndex,
                "classificationId" to row.classificationId.toString(),
            ) + people(row, contributors) + texts(row),
    )

    /** A draft was edited: what it was and what it is, as ids, dates and hashes. */
    fun edited(
        before: ActionRow,
        after: ActionRow,
        contributorsBefore: List<UUID>,
        contributorsAfter: List<UUID>,
        /** Why an issued action was edited (a draft needs none). */
        reason: String? = null,
    ) {
        val was = people(before, contributorsBefore) + texts(before) + ("classificationId" to before.classificationId.toString())
        val now = people(after, contributorsAfter) + texts(after) + ("classificationId" to after.classificationId.toString())
        val changed = now.keys.filter { now[it] != was[it] }
        audit.record(
            "actions.action.edited",
            OBJECT_TYPE,
            after.id.toString(),
            before = was.filterKeys { it in changed },
            after = now.filterKeys { it in changed } + ("fields" to changed) + ("reasonSha256" to reason?.let(ActionRules::sha256)),
        )
    }

    /** A draft was discarded (kept, never deleted). */
    fun discarded(
        row: ActionRow,
        reason: String?,
    ) = audit.record(
        "actions.action.discarded",
        OBJECT_TYPE,
        row.id.toString(),
        after = mapOf("reasonSha256" to reason?.let(ActionRules::sha256)),
    )

    /** The draft was issued: its reference, its instance, and the holder of the owner's task is told. */
    fun issued(
        row: ActionRow,
        definitionKey: String,
    ) {
        audit.record(
            "actions.action.issued",
            OBJECT_TYPE,
            row.id.toString(),
            after =
                mapOf(
                    "reference" to row.reference,
                    "instanceId" to row.workflowInstanceId?.toString(),
                    "definitionKey" to definitionKey,
                    "dueDate" to row.dueDate?.toString(),
                    "holderId" to row.holderUserId?.toString(),
                ),
        )
        row.holderUserId?.let { holder ->
            notify(holder, NotificationKind.ACTION_ASSIGNED, row, mapOf("date" to row.dueDate.toString()))
        }
        changed(row, ActionChange.ISSUED)
    }

    /** The decision [row] came from was corrected (entry [seq]): recorded on the action, and [secretaries] are told in the app. */
    fun sourceCorrected(
        row: ActionRow,
        seq: Int,
        secretaries: Collection<UUID>,
    ) {
        audit.record(
            "actions.action.source_corrected",
            OBJECT_TYPE,
            row.id.toString(),
            after = mapOf("decisionId" to row.decisionId?.toString(), "seq" to seq),
        )
        secretaries.forEach { notify(it, NotificationKind.ACTION_SOURCE_CORRECTED, row) }
    }

    /** E08's event. */
    fun changed(
        row: ActionRow,
        kind: ActionChange,
    ) = events.publishEvent(ActionChanged(TenantContext.current().value, row.id, row.forumId, kind))

    /** Tells [user] of [kind] on [row]: the committee, the reference and [details] (dates, words), never its text. */
    fun notify(
        user: UUID,
        kind: NotificationKind,
        row: ActionRow,
        details: Map<String, String> = emptyMap(),
    ) {
        val recipient = recipients.recipient(user) ?: return
        val tenant = registry.current()
        val link = "/actions/${row.id}"
        notifications.notify(
            recipient,
            kind,
            details +
                mapOf(
                    "committee" to directory.forum(row.forumId)?.name.orEmpty(),
                    "reference" to row.reference.orEmpty(),
                    "organisation" to tenant?.name.orEmpty(),
                    "link" to (tenant?.let { tenancy.publicUrl(it.slug) }.orEmpty() + link),
                ),
            link,
        )
    }

    private fun people(
        row: ActionRow,
        contributors: List<UUID>,
    ): Map<String, Any?> =
        mapOf(
            "ownerKind" to row.owner?.kind?.name,
            "ownerUserId" to row.owner?.userId?.toString(),
            "ownerPositionId" to row.owner?.positionId?.toString(),
            "contributors" to contributors.map { it.toString() },
            "dueDate" to row.dueDate?.toString(),
            "priority" to row.priority.name,
        )

    private fun texts(row: ActionRow): Map<String, Any?> =
        mapOf(
            "titleSha256" to ActionRules.sha256(row.title),
            "descriptionSha256" to row.description?.let(ActionRules::sha256),
            "contextSha256" to row.context?.let(ActionRules::sha256),
            "sourceNoteSha256" to row.sourceNote?.let(ActionRules::sha256),
        )

    companion object {
        const val OBJECT_TYPE = "action"
    }
}
