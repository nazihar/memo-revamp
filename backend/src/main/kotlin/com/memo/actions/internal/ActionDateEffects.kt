package com.memo.actions.internal

import com.memo.actions.ActionChange
import com.memo.audit.AuditLog
import com.memo.notification.NotificationKind
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.UUID

/**
 * What follows a due-date request, its answer or a change of the date (design E07 §1.7, §5), in its transaction: the
 * audit entry (dates, ids and the SHA-256 of the reasons), the notices in the app (the committee, the reference and the
 * date) and [com.memo.actions.ActionChanged] for E08. The writing service is [ActionDates].
 */
@Component
class ActionDateEffects(
    private val audit: AuditLog,
    private val items: ActionItemEffects,
    private val viewers: ActionViewers,
    private val lines: ActionLines,
) {
    /** The owner asked for a new date; [deciders] are told in the app. */
    fun dueRequested(
        row: ActionRow,
        request: DueRequestRow,
        deciders: Collection<UUID>,
    ) {
        audit.record(
            "actions.due_request.made",
            ActionItemEffects.OBJECT_TYPE,
            row.id.toString(),
            after =
                mapOf(
                    "requestId" to request.id.toString(),
                    "from" to request.oldDate.toString(),
                    "to" to request.newDate.toString(),
                    "reasonSha256" to ActionRules.sha256(request.reason),
                ),
        )
        deciders.forEach { items.notify(it, NotificationKind.ACTION_DUE_REQUESTED, row, mapOf("date" to request.newDate.toString())) }
    }

    /** A request was agreed or declined; the owner who asked is told in the app. */
    fun dueDecided(
        row: ActionRow,
        request: DueRequestRow,
        state: DueRequestState,
        note: String?,
    ) {
        val action = if (state == DueRequestState.AGREED) "actions.due_request.agreed" else "actions.due_request.declined"
        audit.record(
            action,
            ActionItemEffects.OBJECT_TYPE,
            row.id.toString(),
            after =
                mapOf(
                    "requestId" to request.id.toString(),
                    "to" to request.newDate.toString(),
                    "noteSha256" to note?.let(ActionRules::sha256),
                ),
        )
        items.notify(
            request.requestedBy,
            NotificationKind.ACTION_DUE_DECIDED,
            row,
            mapOf("date" to request.newDate.toString(), "decision" to state.name),
        )
    }

    /** A pending request was withdrawn, because of [cause] (`DUE_CHANGED`, `CANCELLED`, from T5 `DONE`). */
    fun dueWithdrawn(
        row: ActionRow,
        request: DueRequestRow,
        cause: String,
    ) = audit.record(
        "actions.due_request.withdrawn",
        ActionItemEffects.OBJECT_TYPE,
        row.id.toString(),
        after = mapOf("requestId" to request.id.toString(), "cause" to cause),
    )

    /**
     * The due date changed from [from] to [to], the [revisions]th time; the owner and the contributors are told, but
     * [excluded] (who changed it, and the owner who asked and was told of the answer), and [secretaries] too when the
     * owner moved it themselves (OWNER_WITH_REASON).
     */
    @Suppress("LongParameterList")
    fun dueChanged(
        row: ActionRow,
        from: LocalDate,
        to: LocalDate,
        revisions: Int,
        reason: String,
        requestId: UUID?,
        excluded: Set<UUID>,
        secretaries: Collection<UUID>,
    ) {
        audit.record(
            "actions.due.changed",
            ActionItemEffects.OBJECT_TYPE,
            row.id.toString(),
            before = mapOf("dueDate" to from.toString(), "revisions" to row.dueRevisions),
            after =
                mapOf(
                    "dueDate" to to.toString(),
                    "revisions" to revisions,
                    "originalDueDate" to row.originalDueDate?.toString(),
                    "requestId" to requestId?.toString(),
                    "reasonSha256" to ActionRules.sha256(reason),
                ),
        )
        (people(row) + secretaries - excluded).forEach {
            items.notify(
                it,
                NotificationKind.ACTION_DUE_CHANGED,
                row,
                mapOf("date" to to.toString()),
            )
        }
        items.changed(row, ActionChange.DUE_CHANGED)
    }

    /** The committee's secretaries in office who are not [excluded]. */
    fun secretaries(
        row: ActionRow,
        excluded: Collection<UUID>,
    ): List<UUID> = viewers.secretaries(row.forumId) - excluded.toSet()

    /** The owner (the holder of the owner's task, else the person) and the contributors. */
    private fun people(row: ActionRow): Set<UUID> = setOfNotNull(row.holderUserId ?: row.owner?.userId) + lines.contributorsOf(row.id)
}
