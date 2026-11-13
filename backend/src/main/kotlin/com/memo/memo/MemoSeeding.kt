package com.memo.memo

import com.memo.common.authz.Clearances
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.files.FileUploads
import com.memo.files.Files
import com.memo.memo.internal.MemoDocumentPurpose
import com.memo.memo.internal.MemoFields
import com.memo.memo.internal.MemoListStore
import com.memo.memo.internal.MemoReviews
import com.memo.memo.internal.MemoRoutes
import com.memo.memo.internal.MemoTypeFields
import com.memo.memo.internal.MemoTypeStore
import com.memo.memo.internal.MemoTypes
import com.memo.memo.internal.MemoWorkflowSubject
import com.memo.memo.internal.Memos
import com.memo.memo.internal.RoutePolicy
import com.memo.workflow.RouteEntry
import com.memo.workflow.WorkflowQueries
import com.memo.workflow.Workflows
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Memos for seeders (the demo tenant, design E01 §15), through the same services as the API, as the user bound to the
 * current unit of work. Never called from requests. Approving skips the step-up a person would give: seeders have no
 * session. Each call is its own tenant transaction, except uploads, which run outside one as the files service needs.
 */
@Service
class MemoSeeding(
    private val memos: Memos,
    private val types: MemoTypes,
    private val routes: MemoRoutes,
    private val reviews: MemoReviews,
    private val lists: MemoListStore,
    private val workflows: Workflows,
    private val queries: WorkflowQueries,
    private val clearances: Clearances,
    private val files: Files,
    private val uploads: FileUploads,
    private val transactions: TenantTransactions,
) {
    /** Whether [initiator] has created any memo yet (seeders run once per tenant). */
    fun hasMemos(initiator: UUID): Boolean = tx { lists.anyOf(initiator) }

    /** A draft of the built-in General type, classified at [rank]. */
    fun draft(
        subject: String,
        rank: Int = 0,
    ): UUID =
        tx {
            val type = types.active().first { it.key == MemoTypeStore.GENERAL }
            memos.create(MemoFields(subject, type.id, clearances.levels()[rank].id)).id
        }

    /** The memo's document: its first file, or a new version of it. */
    fun document(
        memo: UUID,
        name: String,
        bytes: ByteArray,
    ) {
        val current = tx { memos.get(memo).documentFileId?.let { files.info(it) } }
        val key = UUID.randomUUID().toString()
        if (current == null) {
            uploads.upload(MemoDocumentPurpose.KEY, memo.toString(), name, bytes, key)
        } else {
            uploads.uploadVersion(current.id, current.version, name, bytes, key)
        }
    }

    fun route(
        memo: UUID,
        approvers: List<UUID>,
    ) {
        tx { routes.replace(memo, memos.get(memo).version, approvers.map(RouteEntry::person)) }
    }

    fun submit(
        memo: UUID,
        remark: String? = null,
    ) {
        tx { reviews.submit(memo, remark) }
    }

    fun resubmit(
        memo: UUID,
        remark: String? = null,
    ) {
        tx { reviews.resubmit(memo, remark) }
    }

    fun withdraw(
        memo: UUID,
        remark: String? = null,
    ) {
        tx { reviews.withdraw(memo, remark) }
    }

    /** Approves, returns or rejects the open task of [memo], which must be the current user's. */
    fun act(
        memo: UUID,
        action: String,
        remark: String? = null,
    ) {
        tx {
            val task = checkNotNull(queries.viewOf(MemoWorkflowSubject.TYPE, memo)?.openTaskId) { "memo $memo has no open task" }
            when (action) {
                APPROVE -> workflows.approve(task, remark)
                RETURN -> workflows.returnToInitiator(task, remark)
                REJECT -> workflows.reject(task, remark)
                else -> throw IllegalArgumentException("unknown action $action")
            }
        }
    }

    /** A memo type called [name] whose workflow [definitionKey] names every step (`FIXED`), unless the tenant has one. */
    fun fixedType(
        name: String,
        definitionKey: String,
    ) {
        tx {
            if (types.all().none { it.name == name }) {
                types.create(MemoTypeFields(name, null, stepUpOnApprove = true, active = true, definitionKey, RoutePolicy.FIXED))
            }
        }
    }

    private fun <T> tx(block: () -> T): T = transactions.inTenant(TenantContext.current(), block)

    companion object {
        const val APPROVE = "approve"
        const val RETURN = "return"
        const val REJECT = "reject"
    }
}
