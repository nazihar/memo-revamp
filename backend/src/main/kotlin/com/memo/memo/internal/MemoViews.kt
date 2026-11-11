package com.memo.memo.internal

import com.memo.common.authz.Clearances
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.files.Files
import com.memo.identity.UserSummaries
import com.memo.workflow.BlockedReason
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

/** What the actor may do on the memo now, for buttons only; the server checks again on every request. */
enum class MemoAction { EDIT, UPLOAD_DOCUMENT, EDIT_ROUTE, SUBMIT, DISCARD, RESUBMIT, WITHDRAW, APPROVE, RETURN, REJECT }

/** The actor's open task on the memo; [blocked] set means it waits for reassignment and cannot be acted on. */
data class TaskRef(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    val blocked: BlockedReason?,
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
) {
    fun of(memo: Memo): MemoView {
        val route = routes.route(memo.id)
        val workflow = memo.workflowInstanceId?.let { workflows.viewOf(MemoWorkflowSubject.TYPE, memo.id) }
        val holders = workflow?.currentAssignees.orEmpty()
        val names = users.of(route + holders + memo.initiatorId).mapValues { it.value.displayName }
        val user = (ActorContext.current().actor as? Actor.User)?.userId
        val myTask = workflow?.openTaskId?.takeIf { user in holders }?.let { TaskRef(it, workflow.openTaskBlocked) }
        val level = clearances.level(memo.classificationId)
        return MemoView(
            id = memo.id,
            reference = memo.reference,
            subject = memo.subject,
            type = types.get(memo.typeId).let { Named(it.id, it.name) },
            classification = ClassificationView(memo.classificationId, memo.classificationRank, level?.name.orEmpty()),
            status = memo.status,
            initiator = Named(memo.initiatorId, names[memo.initiatorId].orEmpty()),
            document = document(memo.documentFileId),
            route = route.mapIndexed { index, id -> RouteEntryView(index + 1, Named(id, names[id].orEmpty())) },
            round = memo.currentRound,
            holders = holders.map { Named(it, names[it].orEmpty()) },
            myTask = myTask,
            allowedActions = actions(memo, user, myTask),
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
        task: TaskRef?,
    ): List<MemoAction> =
        when {
            task != null -> if (task.blocked == null) APPROVER else emptyList()
            user != memo.initiatorId -> emptyList()
            memo.status == MemoStatus.DRAFT -> DRAFT
            memo.status == MemoStatus.RETURNED -> RETURNED
            memo.status == MemoStatus.IN_REVIEW -> listOf(MemoAction.WITHDRAW)
            else -> emptyList()
        }

    private companion object {
        val APPROVER = listOf(MemoAction.APPROVE, MemoAction.RETURN, MemoAction.REJECT)
        val DRAFT = listOf(MemoAction.EDIT, MemoAction.UPLOAD_DOCUMENT, MemoAction.EDIT_ROUTE, MemoAction.SUBMIT, MemoAction.DISCARD)
        val RETURNED = listOf(MemoAction.EDIT, MemoAction.UPLOAD_DOCUMENT, MemoAction.EDIT_ROUTE, MemoAction.RESUBMIT, MemoAction.WITHDRAW)
    }
}
