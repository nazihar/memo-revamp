package com.memo.actions.internal

import com.memo.actions.ActionChange
import com.memo.audit.AuditLog
import com.memo.notification.NotificationKind
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * What follows marking an action as done and verifying it (design E07 §1.9, §2.3, §5), in its transaction: the audit
 * entry with ids and the SHA-256 of what people wrote (never the text), the notices (the committee, the reference and the
 * date, never the action's title or the remark) and [com.memo.actions.ActionChanged] for E08. The verifiers are told by
 * [ActionWorkflowListener] when the engine gives them the verify task.
 */
@Component
class ActionClosureEffects(
    private val audit: AuditLog,
    private val items: ActionItemEffects,
    private val lines: ActionLines,
) {
    /** The holder marked it done (entry [seq]) with [note] and the evidence [fileIds]. */
    fun done(
        row: ActionRow,
        seq: Int,
        note: String,
        fileIds: List<UUID>,
    ) {
        audit.record(
            "actions.done",
            ActionItemEffects.OBJECT_TYPE,
            row.id.toString(),
            before = mapOf("status" to row.status.name),
            after =
                mapOf(
                    "seq" to seq,
                    "instanceId" to row.workflowInstanceId?.toString(),
                    "fileIds" to fileIds.map { it.toString() },
                    "noteSha256" to ActionRules.sha256(note),
                ),
        )
        items.changed(row, ActionChange.DONE)
    }

    /**
     * The verifier closed it (entry [seq]), at the meeting [agreedAt] when the committee agreed; the owner and the
     * contributors are told in the app (the email waits for the digest). The engine's end publishes CLOSED.
     */
    fun closed(
        row: ActionRow,
        seq: Int,
        remark: String?,
        agreedAt: UUID?,
        by: UUID,
    ) {
        audit.record(
            "actions.closed",
            ActionItemEffects.OBJECT_TYPE,
            row.id.toString(),
            before = mapOf("status" to row.status.name),
            after =
                mapOf(
                    "seq" to seq,
                    "instanceId" to row.workflowInstanceId?.toString(),
                    "closeAgreedAt" to agreedAt?.toString(),
                    "remarkSha256" to remark?.let(ActionRules::sha256),
                ),
        )
        (people(row) - by).forEach { items.notify(it, NotificationKind.ACTION_CLOSED, row, mapOf("date" to row.dueDate.toString())) }
    }

    /** The verifier sent it back (entry [seq]) with [remark]: the holder is told at once, by email too, without the remark. */
    fun sentBack(
        row: ActionRow,
        seq: Int,
        remark: String,
    ) {
        audit.record(
            "actions.sent_back",
            ActionItemEffects.OBJECT_TYPE,
            row.id.toString(),
            before = mapOf("status" to ActionStatus.DONE_PENDING.name),
            after =
                mapOf(
                    "seq" to seq,
                    "instanceId" to row.workflowInstanceId?.toString(),
                    "holderId" to row.holderUserId?.toString(),
                    "remarkSha256" to ActionRules.sha256(remark),
                ),
        )
        (row.holderUserId ?: row.owner?.userId)?.let {
            items.notify(it, NotificationKind.ACTION_SENT_BACK, row, mapOf("date" to row.dueDate.toString()))
        }
        items.changed(row, ActionChange.UPDATED)
    }

    /** The owner (the holder of the owner's task, else the person) and the contributors. */
    private fun people(row: ActionRow): Set<UUID> = setOfNotNull(row.holderUserId ?: row.owner?.userId) + lines.contributorsOf(row.id)
}
