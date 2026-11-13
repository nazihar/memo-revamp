package com.memo.workflow.internal

import com.memo.common.authz.Permission
import com.memo.common.authz.RequiresPermission
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.etagOf
import com.memo.common.web.ifMatchVersion
import com.memo.identity.UserSummaries
import com.memo.org.OrgDirectory
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class QueueMemberView(
    /** A person, or null when the member is a position. */
    val user: Person?,
    /** A position, by id and title, or null when the member is a person. */
    val positionId: UUID?,
    val positionTitle: String?,
)

data class QueueResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val key: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val members: List<QueueMemberView>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Send as If-Match on changes") val version: Long,
)

data class QueueMemberRequest(
    val userId: UUID? = null,
    val positionId: UUID? = null,
)

data class QueueRequest(
    /** Lower case letters, digits and dashes, starting with a letter; never changes (ignored on update). */
    val key: String? = null,
    val name: String,
    val members: List<QueueMemberRequest> = emptyList(),
)

/** Queues workflow steps can go to (design E04 §5), for holders of `workflow.queues.manage`. */
@RestController
@RequestMapping("/api/v1/admin/workflow/queues")
@Tag(name = "workflow")
class QueuesAdminController(
    private val queues: Queues,
    private val users: UserSummaries,
    private val directory: OrgDirectory,
    private val transactions: TenantTransactions,
) {
    @GetMapping
    @RequiresPermission(Permission.WORKFLOW_QUEUES_MANAGE)
    @Operation(operationId = "listWorkflowQueues", summary = "Queues and their members")
    fun list(): ResponseEntity<List<QueueResponse>> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(transactions.readInTenant(TenantContext.current()) { responses(queues.all()) })

    @PostMapping
    @RequiresPermission(Permission.WORKFLOW_QUEUES_MANAGE)
    @Operation(operationId = "createWorkflowQueue", summary = "Create a queue")
    fun create(
        @RequestBody body: QueueRequest,
    ): ResponseEntity<QueueResponse> {
        val key = body.key?.trim().orEmpty()
        require(KEY.matches(key)) { "The key is lower case letters, digits and dashes, starting with a letter." }
        val created =
            transactions.inTenant(
                TenantContext.current(),
            ) { responses(listOf(queues.create(key, name(body), members(body)))).single() }
        return ResponseEntity.status(HttpStatus.CREATED).eTag(etagOf(created.version)).body(created)
    }

    @PutMapping("/{key}")
    @RequiresPermission(Permission.WORKFLOW_QUEUES_MANAGE)
    @Operation(operationId = "updateWorkflowQueue", summary = "Rename a queue and replace its members")
    fun update(
        @PathVariable key: String,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: QueueRequest,
    ): ResponseEntity<QueueResponse> {
        val version = ifMatchVersion(ifMatch)
        val updated =
            transactions.inTenant(TenantContext.current()) {
                responses(listOf(queues.update(key, version, name(body), members(body)))).single()
            }
        return ResponseEntity.ok().eTag(etagOf(updated.version)).body(updated)
    }

    private fun name(body: QueueRequest): String =
        body.name.trim().also { require(it.length in 1..MAX_NAME) { "The name is 1 to $MAX_NAME characters." } }

    private fun members(body: QueueRequest): List<QueueMember> {
        require(body.members.size <= MAX_MEMBERS) { "A queue has at most $MAX_MEMBERS members." }
        return body.members.map {
            require((it.userId == null) != (it.positionId == null)) { "A member is a person or a position." }
            QueueMember(it.userId, it.positionId)
        }
    }

    private fun responses(rows: List<QueueRow>): List<QueueResponse> {
        val people = users.of(rows.flatMap { row -> row.members.mapNotNull { it.userId } }.toSet())
        val positions = directory.positions(rows.flatMap { row -> row.members.mapNotNull { it.positionId } }.toSet())
        return rows.map { row ->
            QueueResponse(
                key = row.key,
                name = row.name,
                members =
                    row.members.map { member ->
                        QueueMemberView(
                            user = member.userId?.let { Person(it, people[it]?.displayName.orEmpty()) },
                            positionId = member.positionId,
                            positionTitle = member.positionId?.let { positions[it]?.title },
                        )
                    },
                version = row.version,
            )
        }
    }

    private companion object {
        val KEY = Regex("^[a-z][a-z0-9-]{0,99}$")
        const val MAX_NAME = 200
        const val MAX_MEMBERS = 50
    }
}
