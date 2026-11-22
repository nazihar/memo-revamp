package com.memo.actions.internal

import com.memo.actions.ActionChange
import com.memo.audit.AuditLog
import com.memo.notification.NotificationKind
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.UUID

/**
 * What follows a change of an issued action (design E07 §2.3, §5), in its transaction: the audit entry with ids, kinds,
 * dates and the SHA-256 of what people wrote (never the text), the notices (the committee, the reference and dates,
 * never the action's title or text) and [com.memo.actions.ActionChanged] for E08. The writing services are
 * [ActionProgress], [ActionOwnership] and [ActionEnding]; the dates' are [ActionDateEffects].
 */
@Component
class ActionProgressEffects(
    private val audit: AuditLog,
    private val items: ActionItemEffects,
    private val lines: ActionLines,
) {
    /** An update was posted (entry [seq]); [clears] when it cleared an override without a date. */
    fun updated(
        row: ActionRow,
        seq: Int,
        more: EntryMore,
        clears: Boolean,
    ) {
        audit.record(
            "actions.update.posted",
            ActionItemEffects.OBJECT_TYPE,
            row.id.toString(),
            after =
                mapOf(
                    "seq" to seq,
                    "confidence" to more.confidence?.name,
                    "fileIds" to more.fileIds.map { it.toString() },
                    "onBehalfOf" to more.onBehalfOf?.toString(),
                    "how" to more.how?.name,
                    "corrects" to more.corrects,
                    "textSha256" to more.text?.let(ActionRules::sha256),
                    "startedNow" to (row.status == ActionStatus.OPEN),
                ),
        )
        if (clears) cleared(row, seq, "UPDATE")
        items.changed(row, ActionChange.UPDATED)
    }

    /** "I have started". */
    fun started(
        row: ActionRow,
        more: EntryMore,
    ) {
        audit.record(
            "actions.started",
            ActionItemEffects.OBJECT_TYPE,
            row.id.toString(),
            after = mapOf("onBehalfOf" to more.onBehalfOf?.toString(), "how" to more.how?.name),
        )
        items.changed(row, ActionChange.UPDATED)
    }

    /** The colour was overridden (entry [seq]). */
    fun overridden(
        row: ActionRow,
        seq: Int,
        colour: RagColour,
        until: LocalDate?,
        reason: String,
    ) = audit.record(
        "actions.rag.overridden",
        ActionItemEffects.OBJECT_TYPE,
        row.id.toString(),
        before = mapOf("rag" to row.ragOverride?.name, "until" to row.ragOverrideUntil?.toString()),
        after = mapOf("seq" to seq, "rag" to colour.name, "until" to until?.toString(), "reasonSha256" to ActionRules.sha256(reason)),
    )

    /** The override was cleared, by an officer or by [cause] (`UPDATE`: the next update after one without a date). */
    fun cleared(
        row: ActionRow,
        seq: Int,
        cause: String,
    ) = audit.record(
        "actions.rag.cleared",
        ActionItemEffects.OBJECT_TYPE,
        row.id.toString(),
        before = mapOf("rag" to row.ragOverride?.name, "until" to row.ragOverrideUntil?.toString()),
        after = mapOf("seq" to seq, "cause" to cause),
    )

    /** The owner changed from [before]'s to [after]'s; both people are told (in the app and by email). */
    fun ownerChanged(
        before: ActionRow,
        after: ActionRow,
        reason: String,
        told: Collection<UUID>,
        by: UUID,
    ) {
        audit.record(
            "actions.action.owner_changed",
            ActionItemEffects.OBJECT_TYPE,
            after.id.toString(),
            before = owner(before),
            after = owner(after) + ("reasonSha256" to ActionRules.sha256(reason)),
        )
        (told.toSet() - by).forEach {
            items.notify(it, NotificationKind.ACTION_OWNER_CHANGED, after, mapOf("date" to after.dueDate.toString()))
        }
        items.changed(after, ActionChange.UPDATED)
    }

    /** People were added to and removed from the action's contributors. */
    fun contributors(
        row: ActionRow,
        added: Collection<UUID>,
        removed: Collection<UUID>,
    ) {
        added.forEach {
            audit.record(
                "actions.action.contributor_added",
                ActionItemEffects.OBJECT_TYPE,
                row.id.toString(),
                after = mapOf("userId" to it.toString()),
            )
        }
        removed.forEach {
            audit.record(
                "actions.action.contributor_removed",
                ActionItemEffects.OBJECT_TYPE,
                row.id.toString(),
                before = mapOf("userId" to it.toString()),
            )
        }
    }

    /**
     * The action was cancelled as [kind]; the owner and the contributors are told in the app, and the chair at once when
     * a secretary recorded the risk accepted on their behalf ([chair]).
     */
    @Suppress("LongParameterList")
    fun cancelled(
        row: ActionRow,
        kind: CancelKind,
        reason: String,
        supersededBy: UUID?,
        more: EntryMore,
        chair: UUID?,
        by: UUID,
    ) {
        audit.record(
            "actions.action.cancelled",
            ActionItemEffects.OBJECT_TYPE,
            row.id.toString(),
            before = mapOf("status" to row.status.name),
            after =
                mapOf(
                    "kind" to kind.name,
                    "supersededBy" to supersededBy?.toString(),
                    "onBehalfOf" to more.onBehalfOf?.toString(),
                    "how" to more.how?.name,
                    "instanceId" to row.workflowInstanceId?.toString(),
                    "reasonSha256" to ActionRules.sha256(reason),
                ),
        )
        (people(row) - by).forEach { items.notify(it, NotificationKind.ACTION_CANCELLED, row) }
        chair?.takeIf { it != by }?.let { items.notify(it, NotificationKind.ACTION_RISK_ACCEPTED, row) }
        items.changed(row, ActionChange.CANCELLED)
    }

    /** The action was reopened on a new instance, due [due]; its owner is told in the app. */
    fun reopened(
        before: ActionRow,
        after: ActionRow,
        reason: String,
        by: UUID,
    ) {
        audit.record(
            "actions.action.reopened",
            ActionItemEffects.OBJECT_TYPE,
            after.id.toString(),
            before = mapOf("status" to before.status.name, "instanceId" to before.workflowInstanceId?.toString()),
            after =
                mapOf(
                    "instanceId" to after.workflowInstanceId?.toString(),
                    "dueDate" to after.dueDate?.toString(),
                    "reopenCount" to after.reopenCount,
                    "reasonSha256" to ActionRules.sha256(reason),
                ),
        )
        (people(after, contributors = false) - by).forEach {
            items.notify(it, NotificationKind.ACTION_REOPENED, after, mapOf("date" to after.dueDate.toString()))
        }
        items.changed(after, ActionChange.REOPENED)
    }

    /** Who is told of an action's changes: its owner (the holder of the owner's task, else the person) and contributors. */
    private fun people(
        row: ActionRow,
        contributors: Boolean = true,
    ): Set<UUID> = setOfNotNull(row.holderUserId ?: row.owner?.userId) + (if (contributors) lines.contributorsOf(row.id) else emptyList())

    private fun owner(row: ActionRow): Map<String, Any?> =
        mapOf(
            "ownerKind" to row.owner?.kind?.name,
            "ownerUserId" to row.owner?.userId?.toString(),
            "ownerPositionId" to row.owner?.positionId?.toString(),
            "holderId" to row.holderUserId?.toString(),
        )
}
