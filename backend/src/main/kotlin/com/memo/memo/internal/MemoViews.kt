package com.memo.memo.internal

import com.memo.common.authz.Clearances
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.docedit.DocumentEditing
import com.memo.files.Files
import com.memo.identity.UserSummaries
import com.memo.org.OrgDirectory
import com.memo.org.OrgUnitDirectory
import com.memo.workflow.ActionKind
import com.memo.workflow.BlockedReason
import com.memo.workflow.GroupMode
import com.memo.workflow.MyTask
import com.memo.workflow.RouteEntry
import com.memo.workflow.TaskRole
import com.memo.workflow.WorkflowQueries
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/** Something the memo view names: an id with its label for display. */
data class Named(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
)

data class ClassificationView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val rank: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
)

/** The document's current version: what reviewers read and what an approval binds to (ADR-0036). */
data class DocumentView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val fileId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val fileVersion: Long,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val versionId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val number: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val sizeBytes: Long,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val sha256: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val uploadedAt: Instant,
)

/** One route entry (design E04 §4.7): a person ([approver]), a position (with its holder today, if any), or a group. */
data class RouteEntryView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val ordinal: Int,
    val approver: Named? = null,
    val position: RoutePositionView? = null,
    val group: RouteGroupView? = null,
)

data class RoutePositionView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val title: String,
    /** Who holds it today; the entry goes to whoever holds it when it is reached. */
    val holder: Named?,
)

data class RouteGroupView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val mode: GroupMode,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val members: List<RouteEntryView>,
)

/**
 * What the actor may do on the memo now, for buttons only; the server checks again on every request. EDIT_DOCUMENT and
 * VIEW_DOCUMENT appear only where editing in the browser is configured (ADR-0037).
 */
enum class MemoAction {
    EDIT,
    UPLOAD_DOCUMENT,
    EDIT_ROUTE,
    SUBMIT,
    DISCARD,
    RESUBMIT,
    WITHDRAW,
    APPROVE,
    RETURN,
    REJECT,
    EDIT_DOCUMENT,
    VIEW_DOCUMENT,

    // E03 (design §4.4): the positive action of a review step, hand-ons, and consultations.
    RECOMMEND,
    SEND_BACK,
    FORWARD,
    DELEGATE,
    ASK,
    REFER,
    ANSWER,
    OPINION,
    RECALL,

    /** Take, or give back, a step shared by several candidates (design E04 §4.2). */
    CLAIM,
    RELEASE,

    /** Declare an interest and stand aside (SOD-4). */
    RECUSE,

    /** Close an approved memo for good (Phase 3 E01). */
    CLOSE,
}

/**
 * The actor's open task on the memo; [blocked] set means it waits for reassignment and cannot be acted on. [role] says
 * whether it is a decision, a request for an opinion or a question to answer; a decision [waiting] for an answer cannot
 * be decided meanwhile.
 */
data class TaskRef(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    val blocked: BlockedReason?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val role: TaskRole = TaskRole.DECIDE,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val waiting: Boolean = false,
    val stepName: String? = null,
    /** Offered to several candidates; one of them may claim it (design E04 §4.2). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val shared: Boolean = false,
    /** Who claimed the shared step, if anyone. */
    val claimedBy: Named? = null,
)

/** An earlier step of this round the holder may send the memo back to, with who held it. */
data class SendBackTargetView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val stepId: UUID,
    val name: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val holder: Named,
)

/**
 * One detail of a memo (ADR-0043): a field of its type with the value in stored form ([MemoMetadata]) and, for units,
 * people and choices, the name or label to show.
 */
data class MemoDetailView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val key: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val label: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val type: FieldType,
    /** Text, a decimal string, `YYYY-MM-DD`, an id or a choice key; `{value, currency}` for an amount; a list of keys. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val value: Any,
    /** The unit's or person's name, or the chosen options' labels. */
    val display: String? = null,
)

data class MemoView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    /** The memo's number; null until it is numbered (ADR-0044), see [numberedWhen]. */
    val reference: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val subject: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val type: Named,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val classification: ClassificationView,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val status: MemoStatus,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val initiator: Named,
    val document: DocumentView?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val route: List<RouteEntryView>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val round: Int,
    /** Who holds the memo now: the approver of the open task while in review. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val holders: List<Named>,
    val myTask: TaskRef?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val allowedActions: List<MemoAction>,
    /** Where the holder may send the memo back to (with SEND_BACK in [allowedActions]). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val sendBackTargets: List<SendBackTargetView> = emptyList(),
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val createdAt: Instant,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val updatedAt: Instant,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val version: Long,
    /** People with a declared interest, who are never asked to decide on the memo (SOD-4, design E04 §4.4). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val interests: List<Named> = emptyList(),
    /** When the current step is due, from its SLA in working time (design E05 §4.4); null without one. */
    val dueAt: Instant? = null,
    /** The values of the type's fields, in the order of the form (the pinned version's once submitted). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val details: List<MemoDetailView> = emptyList(),
    /** A draft's values for fields its type no longer has; dropped when it is submitted (ADR-0043). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val staleDetails: List<MemoDetailView> = emptyList(),
    /** The type version the memo was submitted under; null for a draft. */
    val typeVersion: Int? = null,
    /** The unit the memo comes from: the initiator's unit when it was created. */
    val originUnit: Named? = null,
    /** For a memo without a number: when it will get one (at submit or at final approval). */
    val numberedWhen: NumberMoment? = null,
    /** Links from this memo to memos the reader may open (Phase 3 E01). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val links: List<MemoLinkView> = emptyList(),
    /** Links to this memo from memos the reader may open ("related files"). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val linkedFrom: List<MemoLinkView> = emptyList(),
    /** Whether the reader may link this memo to another one (it is not closed or discarded). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val canLink: Boolean = false,
    /** When it was closed; null unless closed. */
    val closedAt: Instant? = null,
)

/** Builds [MemoView]s: names from identity, the document from files. Inside a tenant transaction. */
@Component
class MemoViews(
    private val routes: MemoRouteStore,
    private val types: MemoTypes,
    private val clearances: Clearances,
    private val files: Files,
    private val users: UserSummaries,
    private val workflows: WorkflowQueries,
    private val editing: DocumentEditing,
    private val interestStore: MemoInterestStore,
    private val directory: OrgDirectory,
    private val unitDirectory: OrgUnitDirectory,
    private val memoLinks: MemoLinks,
) {
    fun of(memo: Memo): MemoView {
        val interests = interestStore.of(memo.id)
        val route = routes.route(memo.id)
        val positions = route.flatMap { it.positions }
        val titles = directory.positions(positions)
        val holdersToday = directory.holders(positions)
        val workflow = memo.workflowInstanceId?.let { workflows.viewOf(MemoWorkflowSubject.TYPE, memo.id) }
        val holders = workflow?.currentAssignees.orEmpty()
        val user = (ActorContext.current().actor as? Actor.User)?.userId
        val open = if (workflow != null && user != null) workflows.myTask(MemoWorkflowSubject.TYPE, memo.id, user) else null
        val targets = open?.sendBackTargets.orEmpty()
        val names =
            users
                .of(
                    route.flatMap { it.people } + holdersToday.values + holders + memo.initiatorId + targets.map { it.holderId } +
                        listOfNotNull(open?.claimedBy) +
                        interests,
                ).mapValues { it.value.displayName }
        val level = clearances.level(memo.classificationId)
        val type = types.forMemo(memo)
        val details = details(memo)
        val links = memoLinks.of(memo)
        // A FIXED type's definition names every step: its memos have no route to edit (design E03 §12).
        val allowed =
            actions(memo, user, open, workflow?.recallable == true)
                .let { it + documentActions(memo, it) }
                .filterNot { it == MemoAction.EDIT_ROUTE && type.routePolicy == RoutePolicy.FIXED }
        return MemoView(
            id = memo.id,
            reference = memo.reference,
            subject = memo.subject,
            type = Named(type.id, type.name),
            classification = ClassificationView(memo.classificationId, memo.classificationRank, level?.name.orEmpty()),
            status = memo.status,
            initiator = Named(memo.initiatorId, names[memo.initiatorId].orEmpty()),
            document = document(memo.documentFileId),
            route = routeViews(route, names, titles.mapValues { it.value.title }, holdersToday),
            round = memo.currentRound,
            holders = holders.map { Named(it, names[it].orEmpty()) },
            myTask = open?.let { taskRef(it, names) },
            allowedActions = allowed,
            sendBackTargets = targets.map { SendBackTargetView(it.stepId, it.name, Named(it.holderId, names[it.holderId].orEmpty())) },
            interests = interests.map { Named(it, names[it].orEmpty()) }.sortedBy { it.name },
            dueAt = workflow?.let { workflows.dueOf(MemoWorkflowSubject.TYPE, memo.id) },
            details = details.current,
            staleDetails = details.stale,
            typeVersion = details.pinnedVersion,
            numberedWhen = details.numberedWhen.takeIf { memo.reference == null },
            links = links.first,
            linkedFrom = links.second,
            canLink = memo.status !in MemoViewsRules.UNLINKABLE,
            closedAt = memo.closedAt,
            originUnit = originUnit(memo.originUnitId),
            createdAt = memo.createdAt,
            updatedAt = memo.updatedAt,
            version = memo.version,
        )
    }

    private class Details(
        val current: List<MemoDetailView>,
        val stale: List<MemoDetailView>,
        val pinnedVersion: Int?,
        val numberedWhen: NumberMoment?,
    )

    /**
     * The memo's details: the fields of its version (pinned, else the type's published one) that have a value, in form
     * order; a draft's values for fields its type dropped, labelled by the newest version that had them.
     */
    private fun details(memo: Memo): Details {
        val version = types.versionFor(memo)
        val fields = version?.settings?.fields.orEmpty()
        val keys = fields.map { it.key }.toSet()
        val staleKeys = memo.metadata.keys.filterNot { it in keys }
        val older =
            if (staleKeys.isEmpty()) {
                emptyMap()
            } else {
                types
                    .versions(memo.typeId)
                    .flatMap { it.settings.fields }
                    .filter { it.key in staleKeys }
                    .distinctBy { it.key }
                    .associateBy { it.key }
            }
        val shown = fields.filter { it.key in memo.metadata } + staleKeys.mapNotNull { older[it] }
        val unitIds = shown.filter { it.type == FieldType.UNIT }.mapNotNull { uuid(memo.metadata[it.key]) }
        val personIds = shown.filter { it.type == FieldType.PERSON }.mapNotNull { uuid(memo.metadata[it.key]) }
        val units = unitDirectory.units(unitIds).mapValues { it.value.name }
        val people = users.of(personIds.toSet()).mapValues { it.value.displayName }

        fun view(field: MemoField): MemoDetailView? {
            val value = memo.metadata[field.key] ?: return null
            val display =
                when (field.type) {
                    FieldType.UNIT -> {
                        uuid(value)?.let(units::get)
                    }

                    FieldType.PERSON -> {
                        uuid(value)?.let(people::get)
                    }

                    FieldType.CHOICE -> {
                        field.rules.choices
                            .firstOrNull { it.key == value }
                            ?.label
                    }

                    FieldType.MULTI_CHOICE -> {
                        (value as? List<*>)
                            ?.mapNotNull { k ->
                                field.rules.choices
                                    .firstOrNull { it.key == k }
                                    ?.label
                            }?.joinToString(", ")
                    }

                    else -> {
                        null
                    }
                }
            return MemoDetailView(field.key, field.label, field.type, value, display)
        }
        return Details(
            current = fields.mapNotNull(::view),
            stale =
                staleKeys.mapNotNull { key ->
                    older[key]?.let(::view)
                        ?: memo.metadata[key]?.let { MemoDetailView(key, key, FieldType.TEXT, it) }
                },
            pinnedVersion = memo.memoTypeVersionId?.let { version?.number },
            numberedWhen = version?.settings?.numbering?.moment,
        )
    }

    private fun originUnit(id: UUID?): Named? = id?.let { unitDirectory.units(listOf(it))[it] }?.let { Named(it.id, it.name) }

    private fun uuid(value: Any?): UUID? = (value as? String)?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    private fun document(fileId: UUID?): DocumentView? {
        val file = fileId?.let { files.info(it) }
        val version = file?.currentVersionId?.let { files.version(it) }
        if (file == null || version == null) return null
        return DocumentView(
            file.id,
            file.version,
            version.id,
            version.number,
            version.name,
            version.sizeBytes,
            version.sha256,
            version.uploadedAt,
        )
    }

    private fun actions(
        memo: Memo,
        user: UUID?,
        task: MyTask?,
        recallable: Boolean,
    ): List<MemoAction> =
        when {
            task != null -> taskActions(task) + claimActions(task, user) + recusal(task, user)
            user != memo.initiatorId -> emptyList()
            memo.status == MemoStatus.DRAFT -> DRAFT
            memo.status == MemoStatus.RETURNED -> RETURNED
            memo.status == MemoStatus.IN_REVIEW -> listOfNotNull(MemoAction.WITHDRAW, MemoAction.RECALL.takeIf { recallable })
            memo.status == MemoStatus.APPROVED -> listOf(MemoAction.CLOSE)
            else -> emptyList()
        }

    /** What the holder can do with their task now (design E03 §4.4): the step's actions, an answer, or an opinion. */
    private fun taskActions(task: MyTask): List<MemoAction> =
        when {
            task.blocked != null -> {
                emptyList()
            }

            task.role == TaskRole.CLARIFY -> {
                listOf(MemoAction.ANSWER)
            }

            task.role == TaskRole.OPINION -> {
                listOf(MemoAction.OPINION)
            }

            task.waiting -> {
                emptyList()
            }

            else -> {
                task.actions.mapNotNull { DECISIONS[it] }.filterNot { action ->
                    (action == MemoAction.SEND_BACK && task.sendBackTargets.isEmpty()) ||
                        (task.delegated && (action == MemoAction.FORWARD || action == MemoAction.DELEGATE))
                }
            }
        }

    /** Claim an unclaimed shared step; release one the user claimed (design E04 §4.2). */
    private fun claimActions(
        task: MyTask,
        user: UUID?,
    ): List<MemoAction> =
        when {
            !task.shared || task.blocked != null || task.role != TaskRole.DECIDE -> emptyList()
            task.claimedBy == null -> listOf(MemoAction.CLAIM)
            task.claimedBy == user -> listOf(MemoAction.RELEASE)
            else -> emptyList()
        }

    /** A decision holder may always declare an interest and stand aside, unless another candidate claimed the step. */
    private fun recusal(
        task: MyTask,
        user: UUID?,
    ): List<MemoAction> =
        listOfNotNull(
            MemoAction.RECUSE.takeIf {
                task.role == TaskRole.DECIDE && task.blocked == null && (task.claimedBy == null || task.claimedBy == user)
            },
        )

    /** Edit in the browser wherever a new version may be uploaded; everyone who sees the memo may view it there. */
    private fun documentActions(
        memo: Memo,
        actions: List<MemoAction>,
    ): List<MemoAction> =
        if (!editing.enabled || memo.documentFileId == null) {
            emptyList()
        } else {
            listOfNotNull(MemoAction.EDIT_DOCUMENT.takeIf { MemoAction.UPLOAD_DOCUMENT in actions }, MemoAction.VIEW_DOCUMENT)
        }

    private companion object {
        val DECISIONS =
            mapOf(
                ActionKind.APPROVE to MemoAction.APPROVE,
                ActionKind.RECOMMEND to MemoAction.RECOMMEND,
                ActionKind.RETURN to MemoAction.RETURN,
                ActionKind.REJECT to MemoAction.REJECT,
                ActionKind.SEND_BACK to MemoAction.SEND_BACK,
                ActionKind.FORWARD to MemoAction.FORWARD,
                ActionKind.DELEGATE to MemoAction.DELEGATE,
                ActionKind.ASK to MemoAction.ASK,
                ActionKind.REFER to MemoAction.REFER,
            )
        val DRAFT = listOf(MemoAction.EDIT, MemoAction.UPLOAD_DOCUMENT, MemoAction.EDIT_ROUTE, MemoAction.SUBMIT, MemoAction.DISCARD)
        val RETURNED = listOf(MemoAction.EDIT, MemoAction.UPLOAD_DOCUMENT, MemoAction.EDIT_ROUTE, MemoAction.RESUBMIT, MemoAction.WITHDRAW)
    }
}

/** The route as shown: each entry with its person, or its position with today's holder, or its group. */
private fun routeViews(
    route: List<RouteEntry>,
    names: Map<UUID, String>,
    titles: Map<UUID, String>,
    holders: Map<UUID, UUID>,
): List<RouteEntryView> {
    fun view(
        ordinal: Int,
        e: RouteEntry,
    ): RouteEntryView =
        RouteEntryView(
            ordinal,
            e.userId?.let { Named(it, names[it].orEmpty()) },
            e.positionId?.let { p -> RoutePositionView(p, titles[p].orEmpty(), holders[p]?.let { Named(it, names[it].orEmpty()) }) },
            e.group?.let { g -> RouteGroupView(g.mode, g.members.mapIndexed { i, m -> view(i + 1, m) }) },
        )
    return route.mapIndexed { index, entry -> view(index + 1, entry) }
}

private fun taskRef(
    task: MyTask,
    names: Map<UUID, String>,
): TaskRef {
    val claimer = task.claimedBy?.let { Named(it, names[it].orEmpty()) }
    return TaskRef(task.id, task.blocked, task.role, task.waiting, task.stepName, task.shared, claimer)
}
