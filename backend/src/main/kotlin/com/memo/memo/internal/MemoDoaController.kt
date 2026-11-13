package com.memo.memo.internal

import com.memo.common.authz.SignedIn
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.identity.UserSummaries
import com.memo.workflow.WorkflowAuthority
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class DoaPreviewGroupView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val needed: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val people: List<Named>,
    /** How many of the route's entries reach someone in the group; at least [needed] when the route covers it. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val covered: Int,
)

/** Who the DoA would ask to approve the memo (design E04 §4.7); no groups, with [reason], when none applies. */
data class DoaPreviewView(
    val version: Int?,
    val ruleName: String?,
    /** `NO_DOA` (no version in force) or `NO_RULE` (no rule for this memo). */
    val reason: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val groups: List<DoaPreviewGroupView>,
)

/** The initiator's read-only DoA preview (design E04 §4.7, §5): who must approve the memo, and whether the route does. */
@RestController
@RequestMapping("/api/v1/memos/{id}/doa-preview")
@Tag(name = "memo")
class MemoDoaController(
    private val memos: Memos,
    private val routes: MemoRoutes,
    private val authority: WorkflowAuthority,
    private val users: UserSummaries,
    private val transactions: TenantTransactions,
) {
    @PostMapping
    @SignedIn
    @Operation(operationId = "previewMemoDoa", summary = "Who the delegation of authority asks to approve the memo")
    fun preview(
        @PathVariable id: UUID,
    ): DoaPreviewView =
        transactions.readInTenant(TenantContext.current()) {
            val memo = memos.own(id)
            val preview = authority.preview(MemoWorkflowSubject.TYPE, memo.id, memo.initiatorId)
            val route = routes.route(memo.id)
            val gaps = authority.shortfalls(MemoWorkflowSubject.TYPE, memo.id, memo.initiatorId, route).associateBy { it.group }
            val names = users.of(preview.groups.flatMap { it.people }.toSet()).mapValues { it.value.displayName }
            DoaPreviewView(
                preview.version,
                preview.ruleName,
                preview.reason,
                preview.groups.mapIndexed { index, group ->
                    DoaPreviewGroupView(
                        group.needed,
                        group.people.map { Named(it, names[it].orEmpty()) },
                        gaps[index]?.covered ?: group.needed,
                    )
                },
            )
        }
}
