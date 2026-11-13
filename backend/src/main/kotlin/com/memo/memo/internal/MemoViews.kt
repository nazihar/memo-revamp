package com.memo.memo.internal

import com.memo.common.authz.Clearances
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.docedit.DocumentEditing
import com.memo.files.Files
import com.memo.identity.UserSummaries
import com.memo.workflow.ActionKind
import com.memo.workflow.BlockedReason
import com.memo.workflow.MyTask
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

data class RouteEntryView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val ordinal: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val approver: Named,
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

data class MemoView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val reference: String,
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
) {
    fun of(memo: Memo): MemoView {
        val route = routes.route(memo.id)
        val workflow = memo.workflowInstanceId?.let { workflows.viewOf(MemoWorkflowSubject.TYPE, memo.id) }
        val holders = workflow?.currentAssignees.orEmpty()
        val user = (ActorContext.current().actor as? Actor.User)?.userId
        val open = if (workflow != null && user != null) workflows.myTask(MemoWorkflowSubject.TYPE, memo.id, user) else null
        val targets = open?.sendBackTargets.orEmpty()
        val names =
            users
                .of(route + holders + memo.initiatorId + targets.map { it.holderId } + listOfNotNull(open?.claimedBy))
                .mapValues { it.value.displayName }
        val myTask =
            open?.let { task ->
                val claimer = task.claimedBy?.let { Named(it, names[it].orEmpty()) }
                TaskRef(task.id, task.blocked, task.role, task.waiting, task.stepName, task.shared, claimer)
            }
        val level = clearances.level(memo.classificationId)
        val type = types.get(memo.typeId)
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
            route = route.mapIndexed { index, id -> RouteEntryView(index + 1, Named(id, names[id].orEmpty())) },
            round = memo.currentRound,
            holders = holders.map { Named(it, names[it].orEmpty()) },
            myTask = myTask,
            allowedActions = allowed,
            sendBackTargets = targets.map { SendBackTargetView(it.stepId, it.name, Named(it.holderId, names[it.holderId].orEmpty())) },
            createdAt = memo.createdAt,
            updatedAt = memo.updatedAt,
            version = memo.version,
        )
    }

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
            task != null -> taskActions(task) + claimActions(task, user)
            user != memo.initiatorId -> emptyList()
            memo.status == MemoStatus.DRAFT -> DRAFT
            memo.status == MemoStatus.RETURNED -> RETURNED
            memo.status == MemoStatus.IN_REVIEW -> listOfNotNull(MemoAction.WITHDRAW, MemoAction.RECALL.takeIf { recallable })
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
