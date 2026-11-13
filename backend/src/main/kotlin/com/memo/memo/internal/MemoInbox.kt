package com.memo.memo.internal

import com.memo.common.authz.Policy
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.web.Cursors
import com.memo.identity.UserSummaries
import com.memo.workflow.ActionEntry
import com.memo.workflow.ActionKind
import com.memo.workflow.BlockedReason
import com.memo.workflow.TaskRole
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
    /** A decision, an opinion asked for, or a question to answer (design E03 §12). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val kind: TaskRole = TaskRole.DECIDE,
    /** The step the memo is at, by name. */
    val stepName: String? = null,
    /** Whom the holder acts for (a delegator, or the principal of a substitution). */
    val actingFor: Named? = null,
    /** Offered to several people: the first decision completes the step (design E04 §4.2). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val shared: Boolean = false,
    /** Who claimed the shared task, if anyone has. */
    val claimedBy: Named? = null,
    /** When the decision is due (design E05 §4.4); the inbox flags it once past. */
    val dueAt: Instant? = null,
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
    CLOSED(setOf(MemoStatus.APPROVED, MemoStatus.REJECTED, MemoStatus.WITHDRAWN, MemoStatus.DISCARDED, MemoStatus.CANCELLED)),
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
    /** The step the action concerns, by name (design E03 §12). */
    val step: String? = null,
    /** Who the task was handed to: forwarded, delegated, asked or referred. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val to: List<Named> = emptyList(),
    /**
     * What an automatic action was based on: `taken` and `inputs` of a branch (`ROUTE`), `message` of a notification,
     * `forum` of an escalation, `from` and `to` versions of a migration.
     */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val details: Map<String, Any?> = emptyMap(),
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
        val names = users.of(page.items.flatMap { listOfNotNull(it.onBehalfOf, it.claimedBy) }.toSet()).mapValues { it.value.displayName }
        val named = { id: UUID? -> id?.let { Named(it, names[it].orEmpty()) } }
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
                    task.role,
                    task.stepName,
                    named(task.onBehalfOf),
                    task.shared,
                    named(task.claimedBy),
                    task.dueAt,
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
        val names = users.of(actions.flatMap { listOfNotNull(it.actorId, it.actingForId) + handedTo(it) }.toSet())

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
                                step = it.stepName,
                                to = handedTo(it).map(::named),
                                details = it.details.filterKeys { key -> key !in INTERNAL && !(key == "to" && it.kind in HANDED_ON) },
                            )
                        },
                )
            }
    }

    /** The people a hand-on gave the task to, from its details (ids, ADR-0017). */
    private fun handedTo(entry: ActionEntry): List<UUID> =
        if (entry.kind !in HANDED_ON) {
            emptyList()
        } else {
            when (val to = entry.details["to"]) {
                is String -> listOf(UUID.fromString(to))
                is List<*> -> to.filterIsInstance<String>().map(UUID::fromString)
                else -> emptyList()
            }
        }

    private fun currentUser(): UUID =
        (ActorContext.current().actor as? Actor.User)?.userId ?: throw IllegalStateException("the inbox needs a signed-in user")

    private companion object {
        const val MAX_LIMIT = 100
        val HANDED_ON = setOf(ActionKind.FORWARD, ActionKind.DELEGATE, ActionKind.ASK, ActionKind.REFER)

        /** Details the history shows otherwise (the step by name) or that only the engine needs. */
        val INTERNAL = setOf("step", "toStep", "toStepId")
    }
}
