package com.memo.memo.internal

import com.memo.common.authz.Policy
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.web.Cursors
import com.memo.identity.UserSummaries
import com.memo.workflow.ActionKind
import com.memo.workflow.BlockedReason
import com.memo.workflow.WorkflowQueries
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/** A memo as an inbox row shows it; a memo the reader may no longer see is a restricted placeholder (no subject). */
data class InboxMemo(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    val reference: String?,
    val subject: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val restricted: Boolean,
)

data class InboxItem(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val taskId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val memo: InboxMemo,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val round: Int,
    val blocked: BlockedReason?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val assignedAt: Instant,
)

data class InboxPage(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val items: List<InboxItem>,
    val nextCursor: String?,
)

data class BoxItem(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val reference: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val subject: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val status: MemoStatus,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val round: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val holders: List<Named>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val updatedAt: Instant,
)

data class BoxPage(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val items: List<BoxItem>,
    val nextCursor: String?,
)

/** The initiator's boxes (design E01 §5). Discarded drafts are closed. */
enum class MemoBox(
    val statuses: Set<MemoStatus>,
) {
    DRAFTS(setOf(MemoStatus.DRAFT)),
    SENT(setOf(MemoStatus.IN_REVIEW, MemoStatus.RETURNED)),
    CLOSED(setOf(MemoStatus.APPROVED, MemoStatus.REJECTED, MemoStatus.WITHDRAWN, MemoStatus.DISCARDED)),
}

data class HistoryAction(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val seq: Long,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val kind: ActionKind,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val actor: Named,
    val actingFor: Named?,
    val remark: String?,
    val documentNumber: Int?,
    val sha256: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val occurredAt: Instant,
)

/** One round of a memo's history; [current] is false for earlier rounds, whose approvals no longer count. */
data class HistoryRound(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val round: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val current: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val actions: List<HistoryAction>,
)

/**
 * The inbox, the initiator's boxes and a memo's history (design E01 §5, §10). Each page is a fixed number of queries:
 * the tasks or memos, one batch of summaries or holders, one batch of names. Inside a tenant transaction.
 */
@Service
class MemoInbox(
    private val memos: Memos,
    private val lists: MemoListStore,
    private val queries: WorkflowQueries,
    private val users: UserSummaries,
    private val policy: Policy,
) {
    fun tasks(
        cursor: String?,
        limit: Int,
    ): InboxPage {
        val page = queries.myTasks(cursor, limit)
        val memoIds =
            page.items
                .filter { it.subject.type == MemoWorkflowSubject.TYPE }
                .map { it.subject.id }
                .toSet()
        val summaries = lists.summaries(memoIds)
        val visible = lists.visibleAmong(memoIds, currentUser(), policy.clearanceRank())
        val items =
            page.items.map { task ->
                val id = task.subject.id
                val summary = summaries[id]?.takeIf { id in visible }
                InboxItem(
                    task.taskId,
                    InboxMemo(id, summary?.reference, summary?.title, summary == null),
                    task.round,
                    task.blocked,
                    task.createdAt,
                )
            }
        return InboxPage(items, page.nextCursor)
    }

    fun box(
        box: MemoBox,
        cursor: String?,
        limit: Int,
    ): BoxPage {
        val size = limit.coerceIn(1, MAX_LIMIT)
        val rows = lists.box(currentUser(), box.statuses, cursor?.let(Cursors::decode), size + 1)
        val items = rows.take(size)
        val holders = queries.holders(MemoWorkflowSubject.TYPE, items.filter { it.status == MemoStatus.IN_REVIEW }.map { it.id })
        val names = users.of(holders.values.flatten().toSet())
        return BoxPage(
            items.map { memo ->
                val held = holders[memo.id].orEmpty().map { Named(it, names[it]?.displayName.orEmpty()) }
                BoxItem(memo.id, memo.reference, memo.subject, memo.status, memo.round, held, memo.updatedAt)
            },
            if (rows.size > size) items.last().let { Cursors.encode(it.updatedAt, it.id) } else null,
        )
    }

    /** The memo's actions by round, newest round first; visible to whoever may see the memo. */
    fun history(id: UUID): List<HistoryRound> {
        val memo = memos.get(id)
        val instance = memo.workflowInstanceId ?: return emptyList()
        val actions = queries.history(instance)
        val names = users.of(actions.flatMap { listOfNotNull(it.actorId, it.actingForId) }.toSet())

        fun named(user: UUID) = Named(user, names[user]?.displayName.orEmpty())
        return actions
            .groupBy { it.round }
            .toSortedMap(compareByDescending { it })
            .map { (round, entries) ->
                HistoryRound(
                    round = round,
                    current = round == memo.currentRound,
                    actions =
                        entries.map {
                            HistoryAction(
                                seq = it.seq,
                                kind = it.kind,
                                actor = named(it.actorId),
                                actingFor = it.actingForId?.let(::named),
                                remark = it.remark,
                                documentNumber = (it.snapshot["documentNumber"] as? Number)?.toInt(),
                                sha256 = it.snapshot["sha256"] as? String,
                                occurredAt = it.occurredAt,
                            )
                        },
                )
            }
    }

    private fun currentUser(): UUID =
        (ActorContext.current().actor as? Actor.User)?.userId ?: throw IllegalStateException("the inbox needs a signed-in user")

    private companion object {
        const val MAX_LIMIT = 100
    }
}
