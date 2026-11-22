package com.memo.actions.internal

import com.memo.audit.AuditLog
import com.memo.notification.NotificationKind
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * What follows a follow-up memo's link or decision on an action (design E07 §1.11, §5), in its transaction: the audit entry
 * with ids and the memo's outcome (never the memo's or the action's text), and the notice to the holder of the owner's
 * task when a linked memo is decided (the committee, the reference and the outcome; in the app, its email in the daily
 * digest of T5).
 */
@Component
class ActionMemoEffects(
    private val audit: AuditLog,
    private val effects: ActionItemEffects,
) {
    /** A draft memo of [typeId] was raised from [row] (entry [seq]). */
    fun raised(
        row: ActionRow,
        memoId: UUID,
        typeId: UUID,
        seq: Int,
    ) = audit.record(
        "actions.memo.raised",
        ActionItemEffects.OBJECT_TYPE,
        row.id.toString(),
        after = mapOf("memoId" to memoId.toString(), "typeId" to typeId.toString(), "seq" to seq),
    )

    /** An existing memo was linked to [row] (entry [seq]). */
    fun linked(
        row: ActionRow,
        memoId: UUID,
        seq: Int,
    ) = audit.record(
        "actions.memo.linked",
        ActionItemEffects.OBJECT_TYPE,
        row.id.toString(),
        after = mapOf("memoId" to memoId.toString(), "seq" to seq),
    )

    /**
     * The follow-up memo [memoId] of [row] was decided ([outcome]: APPROVED, REJECTED, WITHDRAWN or CANCELLED; entry
     * [seq]). The holder of an open action is told; the action itself does not change.
     */
    fun decided(
        row: ActionRow,
        memoId: UUID,
        outcome: String,
        seq: Int,
    ) {
        audit.record(
            "actions.memo.decided",
            ActionItemEffects.OBJECT_TYPE,
            row.id.toString(),
            after = mapOf("memoId" to memoId.toString(), "outcome" to outcome, "seq" to seq),
        )
        val holder = row.holderUserId?.takeIf { row.status.open } ?: return
        effects.notify(holder, NotificationKind.ACTION_MEMO_DECIDED, row, mapOf("outcome" to outcome))
    }
}
