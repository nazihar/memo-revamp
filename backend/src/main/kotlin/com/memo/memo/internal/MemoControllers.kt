package com.memo.memo.internal

import com.memo.common.authz.Permission
import com.memo.common.authz.RequiresPermission
import com.memo.common.authz.SignedIn
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.etagOf
import com.memo.common.web.ifMatchVersion
import com.memo.system.Idempotency
import com.memo.workflow.DefinitionChoice
import com.memo.workflow.WorkflowDefinitions
import com.memo.workflow.Workflows
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.util.UUID

data class MemoRequest(
    val subject: String,
    val typeId: UUID,
    /** Null: the type's default classification. */
    val classificationId: UUID? = null,
)

/**
 * Memos (design E01 §5). Creating and discarding need an `Idempotency-Key`, bound to the actor, so a key replayed by
 * someone else is refused; edits need `If-Match`. Memos the actor may not see answer 404.
 */
@RestController
@RequestMapping("/api/v1/memos")
@Tag(name = "memo")
class MemosController(
    private val memos: Memos,
    private val routes: MemoRoutes,
    private val reviews: MemoReviews,
    private val views: MemoViews,
    private val idempotency: Idempotency,
    private val transactions: TenantTransactions,
    private val json: JsonMapper,
) {
    @PostMapping
    @RequiresPermission(Permission.MEMO_CREATE)
    @Operation(operationId = "createMemo", summary = "Create a draft memo")
    fun create(
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: MemoRequest,
    ): ResponseEntity<MemoView> {
        val view =
            transactions.inTenant(TenantContext.current()) {
                val id =
                    idempotency.once("memo.create", key, fingerprint(json.writeValueAsString(body))) {
                        memos.create(MemoFields(body.subject, body.typeId, body.classificationId)).id.toString()
                    }
                views.of(memos.get(UUID.fromString(id)))
            }
        return ResponseEntity
            .created(URI.create("/api/v1/memos/${view.id}"))
            .eTag(etagOf(view.version))
            .body(view)
    }

    @GetMapping("/{id}")
    @SignedIn
    @Operation(operationId = "getMemo", summary = "A memo the actor takes part in")
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<MemoView> {
        val view = transactions.readInTenant(TenantContext.current()) { views.of(memos.get(id)) }
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .eTag(etagOf(view.version))
            .body(view)
    }

    @PutMapping("/{id}")
    @SignedIn
    @Operation(operationId = "updateMemo", summary = "Change a memo's subject, type or classification")
    fun update(
        @PathVariable id: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: MemoRequest,
    ): ResponseEntity<MemoView> {
        val version = ifMatchVersion(ifMatch)
        val view =
            transactions.inTenant(TenantContext.current()) {
                views.of(memos.update(id, version, MemoFields(body.subject, body.typeId, body.classificationId)))
            }
        return ResponseEntity.ok().eTag(etagOf(view.version)).body(view)
    }

    @PutMapping("/{id}/route")
    @SignedIn
    @Operation(operationId = "replaceMemoRoute", summary = "Set the ordered approvers of a memo")
    fun route(
        @PathVariable id: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: RouteRequest,
    ): ResponseEntity<MemoView> {
        val version = ifMatchVersion(ifMatch)
        val view = transactions.inTenant(TenantContext.current()) { views.of(routes.replace(id, version, body.approvers)) }
        return ResponseEntity.ok().eTag(etagOf(view.version)).body(view)
    }

    @PostMapping("/{id}/submit")
    @SignedIn
    @Operation(operationId = "submitMemo", summary = "Send a draft to its first approver")
    fun submit(
        @PathVariable id: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody(required = false) body: RemarkRequest?,
    ): ResponseEntity<MemoView> = act(key, "memo.submit", id, body) { reviews.submit(id, body?.remark) }

    @PostMapping("/{id}/resubmit")
    @SignedIn
    @Operation(operationId = "resubmitMemo", summary = "Start the next round after a return")
    fun resubmit(
        @PathVariable id: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody(required = false) body: RemarkRequest?,
    ): ResponseEntity<MemoView> = act(key, "memo.resubmit", id, body) { reviews.resubmit(id, body?.remark) }

    @PostMapping("/{id}/withdraw")
    @SignedIn
    @Operation(operationId = "withdrawMemo", summary = "Withdraw a memo from review")
    fun withdraw(
        @PathVariable id: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody(required = false) body: RemarkRequest?,
    ): ResponseEntity<MemoView> = act(key, "memo.withdraw", id, body) { reviews.withdraw(id, body?.remark) }

    @PostMapping("/{id}/recall")
    @SignedIn
    @Operation(operationId = "recallMemo", summary = "Pull a memo back before any approver acted")
    fun recall(
        @PathVariable id: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody(required = false) body: RemarkRequest?,
    ): ResponseEntity<MemoView> = act(key, "memo.recall", id, body) { reviews.recall(id, body?.remark) }

    @PostMapping("/{id}/discard")
    @SignedIn
    @Operation(operationId = "discardMemo", summary = "Discard a draft")
    fun discard(
        @PathVariable id: UUID,
        @RequestHeader("Idempotency-Key") key: String,
    ): ResponseEntity<MemoView> = act(key, "memo.discard", id, null) { memos.discard(id) }

    /** Runs a memo action once per key, then answers with the memo as it is now. */
    private fun act(
        key: String,
        operation: String,
        id: UUID,
        body: Any?,
        action: () -> Memo,
    ): ResponseEntity<MemoView> {
        val view =
            transactions.inTenant(TenantContext.current()) {
                idempotency.once(operation, key, fingerprint("$id\n${json.writeValueAsString(body)}")) { action().id.toString() }
                views.of(memos.get(id))
            }
        return ResponseEntity.ok().eTag(etagOf(view.version)).body(view)
    }
}

data class RouteRequest(
    val approvers: List<UUID>,
)

data class RemarkRequest(
    val remark: String? = null,
)

/** The request as [Idempotency] compares it: with the actor, so another user's replay of a key is refused (422). */
internal fun fingerprint(request: String): String {
    val user = (ActorContext.current().actor as? Actor.User)?.userId
    return "$user\n$request"
}

/** Where to send the memo back to: a step listed in the memo's `sendBackTargets`. */
data class SendBackRequest(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val targetStepId: UUID,
    val remark: String?,
)

/** Forward, delegate or ask: the person, and the remark (the reason, or the question). */
data class HandOnRequest(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val to: UUID,
    val remark: String?,
)

/** Refer for opinion: up to ten people, and what they should comment on. */
data class ReferRequest(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val to: List<UUID>,
    val remark: String?,
)

/** A memo type as the create form needs it. */
data class MemoTypeOption(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
    val defaultClassificationId: UUID?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val stepUpOnApprove: Boolean,
)

@RestController
@RequestMapping("/api/v1/memo-types")
@Tag(name = "memo")
class MemoTypesController(
    private val types: MemoTypes,
    private val transactions: TenantTransactions,
) {
    @GetMapping
    @SignedIn
    @Operation(operationId = "listMemoTypes", summary = "Active memo types")
    fun list(): List<MemoTypeOption> =
        transactions.readInTenant(TenantContext.current()) {
            types.active().map { MemoTypeOption(it.id, it.name, it.defaultClassificationId, it.stepUpOnApprove) }
        }
}

data class MemoTypeRequest(
    val name: String,
    val defaultClassificationId: UUID? = null,
    val stepUpOnApprove: Boolean = true,
    val active: Boolean = true,
    /** A published workflow definition (design E03 §4.7); omitted: unchanged (`sequential-approval` for a new type). */
    val definitionKey: String? = null,
    /** `INITIATOR_DEFINED`, `FIXED` or `BOTH`; it has to fit the definition. Omitted: unchanged (`INITIATOR_DEFINED` when new). */
    val routePolicy: String? = null,
)

/** Memo-type administration (design E01 §5): since E03 a type chooses its workflow definition and route policy. */
@RestController
@RequestMapping("/api/v1/admin/memo-types")
@Tag(name = "memo")
class AdminMemoTypesController(
    private val types: MemoTypes,
    private val definitions: WorkflowDefinitions,
    private val idempotency: Idempotency,
    private val transactions: TenantTransactions,
    private val json: JsonMapper,
) {
    @GetMapping
    @RequiresPermission(Permission.MEMO_TYPES_MANAGE)
    @Operation(operationId = "listAllMemoTypes", summary = "Every memo type, active or not")
    fun list(): ResponseEntity<List<MemoType>> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(transactions.readInTenant(TenantContext.current()) { types.all() })

    @GetMapping("/definitions")
    @RequiresPermission(Permission.MEMO_TYPES_MANAGE)
    @Operation(operationId = "listMemoTypeDefinitions", summary = "Published workflow definitions a memo type can run on")
    fun definitions(): List<DefinitionChoice> = transactions.readInTenant(TenantContext.current()) { definitions.published() }

    @PostMapping
    @RequiresPermission(Permission.MEMO_TYPES_MANAGE)
    @Operation(operationId = "createMemoType", summary = "Create a memo type")
    fun create(
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: MemoTypeRequest,
    ): ResponseEntity<MemoType> {
        val type =
            transactions.inTenant(TenantContext.current()) {
                val id =
                    idempotency.once("memo.type.create", key, fingerprint(json.writeValueAsString(body))) {
                        types.create(body.fields()).id.toString()
                    }
                types.get(UUID.fromString(id))
            }
        return ResponseEntity
            .created(URI.create("/api/v1/admin/memo-types/${type.id}"))
            .eTag(etagOf(type.version))
            .body(type)
    }

    @PutMapping("/{id}")
    @RequiresPermission(Permission.MEMO_TYPES_MANAGE)
    @Operation(operationId = "updateMemoType", summary = "Change a memo type")
    fun update(
        @PathVariable id: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: MemoTypeRequest,
    ): ResponseEntity<MemoType> {
        val version = ifMatchVersion(ifMatch)
        val type = transactions.inTenant(TenantContext.current()) { types.update(id, version, body.fields(types.get(id))) }
        return ResponseEntity.ok().eTag(etagOf(type.version)).body(type)
    }

    /** The fields as the request gives them; the definition and route policy default to [current]'s (or the built-in ones). */
    private fun MemoTypeRequest.fields(current: MemoType? = null): MemoTypeFields {
        val trimmed = name.trim()
        require(trimmed.length in 1..MAX_NAME) { "A name of 1 to $MAX_NAME characters is required." }
        val policy = routePolicy ?: current?.routePolicy?.name ?: RoutePolicy.INITIATOR_DEFINED.name
        require(RoutePolicy.entries.any { it.name == policy }) { "The route policy is INITIATOR_DEFINED, FIXED or BOTH." }
        return MemoTypeFields(
            trimmed,
            defaultClassificationId,
            stepUpOnApprove,
            active,
            definitionKey?.trim() ?: current?.definitionKey ?: Workflows.SEQUENTIAL_APPROVAL,
            RoutePolicy.valueOf(policy),
        )
    }

    private companion object {
        const val MAX_NAME = 200
    }
}

/**
 * An approver's actions on a memo task (design E01 §5). Each needs an `Idempotency-Key` bound to the actor; the answer is
 * the memo as it is now, so the client needs no second request. Approving may need a fresh step-up (401).
 */
@RestController
@RequestMapping("/api/v1/tasks")
@Tag(name = "memo")
class MemoTasksController(
    private val tasks: MemoTasks,
    private val memos: Memos,
    private val views: MemoViews,
    private val idempotency: Idempotency,
    private val transactions: TenantTransactions,
    private val json: JsonMapper,
) {
    @PostMapping("/{taskId}/approve")
    @SignedIn
    @Operation(operationId = "approveTask", summary = "Approve the memo of a task")
    fun approve(
        @PathVariable taskId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody(required = false) body: RemarkRequest?,
    ): ResponseEntity<MemoView> = act(key, "workflow.task.approve", taskId, body) { tasks.approve(taskId, body?.remark) }

    @PostMapping("/{taskId}/return")
    @SignedIn
    @Operation(operationId = "returnTask", summary = "Return the memo of a task to its initiator")
    fun returnToInitiator(
        @PathVariable taskId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: RemarkRequest,
    ): ResponseEntity<MemoView> = act(key, "workflow.task.return", taskId, body) { tasks.returnToInitiator(taskId, body.remark) }

    @PostMapping("/{taskId}/reject")
    @SignedIn
    @Operation(operationId = "rejectTask", summary = "Reject the memo of a task")
    fun reject(
        @PathVariable taskId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: RemarkRequest,
    ): ResponseEntity<MemoView> = act(key, "workflow.task.reject", taskId, body) { tasks.reject(taskId, body.remark) }

    @PostMapping("/{taskId}/send-back")
    @SignedIn
    @Operation(operationId = "sendBackTask", summary = "Send the memo back to an earlier step of this round")
    fun sendBack(
        @PathVariable taskId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: SendBackRequest,
    ): ResponseEntity<MemoView> =
        act(key, "workflow.task.send_back", taskId, body) { tasks.handOn(taskId) { it.sendBack(taskId, body.targetStepId, body.remark) } }

    @PostMapping("/{taskId}/forward")
    @SignedIn
    @Operation(operationId = "forwardTask", summary = "Hand the task to someone else, who then holds the step")
    fun forward(
        @PathVariable taskId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: HandOnRequest,
    ): ResponseEntity<MemoView> =
        act(key, "workflow.task.forward", taskId, body) {
            tasks.handOn(taskId) { it.forward(taskId, body.to, body.remark) }
        }

    @PostMapping("/{taskId}/delegate")
    @SignedIn
    @Operation(operationId = "delegateTask", summary = "Hand the task to someone who acts for you")
    fun delegate(
        @PathVariable taskId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: HandOnRequest,
    ): ResponseEntity<MemoView> =
        act(key, "workflow.task.delegate", taskId, body) {
            tasks.handOn(taskId) { it.delegate(taskId, body.to, body.remark) }
        }

    @PostMapping("/{taskId}/ask")
    @SignedIn
    @Operation(operationId = "askOnTask", summary = "Ask someone a question; the task waits for the answer")
    fun ask(
        @PathVariable taskId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: HandOnRequest,
    ): ResponseEntity<MemoView> =
        act(key, "workflow.task.ask", taskId, body) { tasks.handOn(taskId) { it.ask(taskId, body.to, body.remark) } }

    @PostMapping("/{taskId}/answer")
    @SignedIn
    @Operation(operationId = "answerTask", summary = "Answer the question this task asks")
    fun answer(
        @PathVariable taskId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: RemarkRequest,
    ): ResponseEntity<MemoView> = act(key, "workflow.task.answer", taskId, body) { tasks.handOn(taskId) { it.answer(taskId, body.remark) } }

    @PostMapping("/{taskId}/refer")
    @SignedIn
    @Operation(operationId = "referTask", summary = "Ask people for their opinions; you keep the task")
    fun refer(
        @PathVariable taskId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: ReferRequest,
    ): ResponseEntity<MemoView> =
        act(key, "workflow.task.refer", taskId, body) {
            tasks.handOn(taskId) { it.refer(taskId, body.to, body.remark) }
        }

    @PostMapping("/{taskId}/opinion")
    @SignedIn
    @Operation(operationId = "giveOpinion", summary = "Give the opinion this task asks for")
    fun opinion(
        @PathVariable taskId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: RemarkRequest,
    ): ResponseEntity<MemoView> =
        act(key, "workflow.task.opinion", taskId, body) {
            tasks.handOn(taskId) { it.giveOpinion(taskId, body.remark) }
        }

    private fun act(
        key: String,
        operation: String,
        taskId: UUID,
        body: Any?,
        action: () -> Memo,
    ): ResponseEntity<MemoView> {
        val view =
            transactions.inTenant(TenantContext.current()) {
                val memo =
                    idempotency.once(
                        operation,
                        key,
                        fingerprint("$taskId\n${json.writeValueAsString(body)}"),
                    ) { action().id.toString() }
                views.of(memos.get(UUID.fromString(memo)))
            }
        return ResponseEntity.ok().eTag(etagOf(view.version)).body(view)
    }
}

/** The initiator's boxes and a memo's history (design E01 §5). */
@RestController
@RequestMapping("/api/v1/memos")
@Tag(name = "memo")
class MemoListsController(
    private val inbox: MemoInbox,
    private val transactions: TenantTransactions,
) {
    @GetMapping
    @SignedIn
    @Operation(operationId = "listMyMemos", summary = "My drafts, sent or closed memos")
    fun box(
        @RequestParam box: String,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "25") limit: Int,
    ): ResponseEntity<BoxPage> {
        val which =
            MemoBox.entries.firstOrNull { it.name.equals(box, ignoreCase = true) }
                ?: throw IllegalArgumentException("box is drafts, sent or closed")
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(transactions.readInTenant(TenantContext.current()) { inbox.box(which, cursor, limit) })
    }

    @GetMapping("/{id}/history")
    @SignedIn
    @Operation(operationId = "getMemoHistory", summary = "A memo's actions by round")
    fun history(
        @PathVariable id: UUID,
    ): ResponseEntity<List<HistoryRound>> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(transactions.readInTenant(TenantContext.current()) { inbox.history(id) })
}

/** The signed-in user's open tasks, newest first (design E01 §5). */
@RestController
@RequestMapping("/api/v1/inbox")
@Tag(name = "memo")
class InboxController(
    private val inbox: MemoInbox,
    private val transactions: TenantTransactions,
) {
    @GetMapping("/tasks")
    @SignedIn
    @Operation(operationId = "listMyTasks", summary = "My open tasks")
    fun tasks(
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "25") limit: Int,
    ): ResponseEntity<InboxPage> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(transactions.readInTenant(TenantContext.current()) { inbox.tasks(cursor, limit) })
}

/**
 * Claiming and releasing a memo task shared by several candidates (design E04 §4.2): the candidate who claims it decides
 * it alone until they release it; and standing aside with a declared interest (§4.4). Each needs an `Idempotency-Key`;
 * the answer is the memo as it is now.
 */
@RestController
@RequestMapping("/api/v1/tasks")
@Tag(name = "memo")
class MemoTaskClaimsController(
    private val tasks: MemoTasks,
    private val interests: MemoInterests,
    private val memos: Memos,
    private val views: MemoViews,
    private val idempotency: Idempotency,
    private val transactions: TenantTransactions,
) {
    @PostMapping("/{taskId}/claim")
    @SignedIn
    @Operation(operationId = "claimTask", summary = "Take a step offered to several people; the others wait until you release it")
    fun claim(
        @PathVariable taskId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
    ): ResponseEntity<MemoView> = act(key, "workflow.task.claim", taskId) { tasks.handOn(taskId) { it.claim(taskId) } }

    @PostMapping("/{taskId}/release")
    @SignedIn
    @Operation(operationId = "releaseTask", summary = "Give a step you claimed back to everyone it was offered to")
    fun release(
        @PathVariable taskId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
    ): ResponseEntity<MemoView> = act(key, "workflow.task.release", taskId) { tasks.handOn(taskId) { it.release(taskId) } }

    @PostMapping("/{taskId}/recuse")
    @SignedIn
    @Operation(operationId = "recuseFromTask", summary = "Declare an interest in the memo and stand aside; the step goes to someone else")
    fun recuse(
        @PathVariable taskId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: RemarkRequest,
    ): ResponseEntity<MemoView> = act(key, "workflow.task.recuse", taskId) { interests.recuse(taskId, body.remark) }

    private fun act(
        key: String,
        operation: String,
        taskId: UUID,
        action: () -> Memo,
    ): ResponseEntity<MemoView> {
        val view =
            transactions.inTenant(TenantContext.current()) {
                val memo = idempotency.once(operation, key, fingerprint("$taskId")) { action().id.toString() }
                views.of(memos.get(UUID.fromString(memo)))
            }
        return ResponseEntity.ok().eTag(etagOf(view.version)).body(view)
    }
}

data class InterestRequest(
    val userId: UUID,
    /** What the interest is (kept with the memo, never in the audit log). */
    val reason: String? = null,
)

/** The people with a declared interest in a memo (design E04 §4.4), listed by its initiator while the memo is theirs to edit. */
@RestController
@RequestMapping("/api/v1/memos/{id}/interests")
@Tag(name = "memo")
class MemoInterestsController(
    private val interests: MemoInterests,
    private val views: MemoViews,
    private val transactions: TenantTransactions,
) {
    @PostMapping
    @SignedIn
    @Operation(operationId = "declareMemoInterest", summary = "Record that someone has an interest in the memo")
    fun declare(
        @PathVariable id: UUID,
        @RequestBody body: InterestRequest,
    ): MemoView = transactions.inTenant(TenantContext.current()) { views.of(interests.declare(id, body.userId, body.reason)) }

    @DeleteMapping("/{userId}")
    @SignedIn
    @Operation(operationId = "removeMemoInterest", summary = "Remove a declared interest")
    fun remove(
        @PathVariable id: UUID,
        @PathVariable userId: UUID,
    ): MemoView = transactions.inTenant(TenantContext.current()) { views.of(interests.remove(id, userId)) }
}
