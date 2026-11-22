package com.memo.actions.internal

import com.memo.common.authz.Permission
import com.memo.common.authz.Policy
import com.memo.memo.MemoReaders
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * A follow-up memo on the action page (design E07 §1.11, flow AP), by the memo's own rule: its number, subject and status
 * for a viewer who may read the memo; for anyone else "A follow-up memo (you do not have access)", with its status only
 * for the committee's secretaries and the action's verifier, who need the outcome to verify.
 */
data class ActionMemoResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val memoId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val kind: MemoLinkKind,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val linkedAt: Instant,
    /** Whether the viewer may open the memo; without it, no number and no subject. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val readable: Boolean,
    /** The memo's number; null until numbered, or when the viewer may not read it. */
    val reference: String? = null,
    val subject: String? = null,
    /** The memo's status (DRAFT, IN_REVIEW, RETURNED, APPROVED, REJECTED, WITHDRAWN, DISCARDED, CANCELLED, CLOSED). */
    val status: String? = null,
)

/** The follow-up memos of an action as a viewer may see them, and whether they may raise or link one now. */
@Component
class ActionMemoViews(
    private val memoLinks: ActionMemoStore,
    private val readers: MemoReaders,
    private val policy: Policy,
) {
    /** [row]'s memos for [viewer]; [officer]: a secretary of the committee or `forums.manage`, not recused from the item. */
    fun of(
        viewer: ActionViewer,
        row: ActionRow,
        officer: Boolean,
    ): List<ActionMemoResponse> {
        val links = memoLinks.of(row.id)
        if (links.isEmpty()) return emptyList()
        val ids = links.map { it.memoId }
        val readable = readers.readable(ids)
        val verifier = row.status == ActionStatus.DONE_PENDING && row.id in viewer.tasks()
        val statuses = if (officer || verifier) readers.statuses(ids - readable.keys) else emptyMap()
        return links.map { link ->
            val memo = readable[link.memoId]
            ActionMemoResponse(
                memoId = link.memoId,
                kind = link.kind,
                linkedAt = link.linkedAt,
                readable = memo != null,
                reference = memo?.reference,
                subject = memo?.subject,
                status = memo?.status ?: statuses[link.memoId],
            )
        }
    }

    /** Whether [viewer] may raise a follow-up memo from [row] now. */
    fun mayRaise(
        viewer: ActionViewer,
        row: ActionRow,
        contributors: List<UUID>,
    ): Boolean =
        row.status in ActionFollowUps.RAISE_STATES &&
            (ActionFollowUps.owns(viewer, row) || viewer.userId in contributors) &&
            policy.allows(Permission.MEMO_CREATE)

    /** Whether [viewer] may link a memo to [row] now; [officer] as for [of]. */
    fun mayLink(
        viewer: ActionViewer,
        row: ActionRow,
        contributors: List<UUID>,
        officer: Boolean,
    ): Boolean =
        row.status in ActionFollowUps.LINK_STATES &&
            (ActionFollowUps.owns(viewer, row) || viewer.userId in contributors || officer)
}
