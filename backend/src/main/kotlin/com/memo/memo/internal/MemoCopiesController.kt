package com.memo.memo.internal

import com.memo.common.authz.ForbiddenException
import com.memo.common.authz.Permission
import com.memo.common.authz.Policy
import com.memo.common.authz.SignedIn
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
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
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

data class CopiesRequest(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val copies: List<CopyRecipient> = emptyList(),
)

/** A recipient with its name: the person's, the position's title, or the list's name. */
data class RecipientView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val kind: RecipientKind,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
)

data class CopyView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val recipient: RecipientView,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val sendWhen: SendWhen,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val addedBy: Named,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val addedAt: Instant,
    /** Null: not sent yet (or never, when the memo was rejected or withdrawn before approval). */
    val sentAt: Instant? = null,
    /** People it reached (sent). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val sent: Int,
    /** Of those, who opened the memo. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val read: Int,
)

data class CopiesView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val copies: List<CopyView>,
    /** Whether the reader may change the list now (initiator or co-author of a draft or returned memo). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val canEdit: Boolean,
    /** Whether the reader may add recipients to the approved memo (`memo.copy.add`). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val canAdd: Boolean,
    /** The memo's version, for `If-Match` on the next change. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val memoVersion: Long,
)

/** One person a copy reached, or an approver: through what, whether it went, and when they first opened the memo. */
data class ReceiptView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val person: Named,
    /** The position or list the copy named; null when it named the person. */
    val via: String? = null,
    /** Null for approvers. */
    val outcome: DeliveryOutcome? = null,
    val openedAt: Instant? = null,
    val documentVersion: Int? = null,
)

data class ReceiptsView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val copies: List<ReceiptView>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val approvers: List<ReceiptView>,
)

/** Copy-to and read receipts of a memo (ADR-0050, design E03 §5). */
@RestController
@Tag(name = "memo")
class MemoCopiesController(
    private val copies: MemoCopies,
    private val copyStore: MemoCopyStore,
    private val lists: DistributionListStore,
    private val memos: Memos,
    private val coauthors: MemoCoauthorStore,
    private val routes: MemoRouteStore,
    private val directory: OrgDirectory,
    private val users: UserSummaries,
    private val policy: Policy,
    private val transactions: TenantTransactions,
) {
    @GetMapping("/api/v1/memos/{id}/copies")
    @SignedIn
    @Operation(operationId = "listMemoCopies", summary = "Who the memo is copied to, when, and how many have read it")
    fun list(
        @PathVariable id: UUID,
    ): ResponseEntity<CopiesView> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(read { view(id) })

    @PutMapping("/api/v1/memos/{id}/copies")
    @SignedIn
    @Operation(operationId = "replaceMemoCopies", summary = "Set the copy list of a draft or returned memo")
    fun replace(
        @PathVariable id: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: CopiesRequest,
    ): ResponseEntity<CopiesView> {
        val version = ifMatchVersion(ifMatch)
        val view =
            transactions.inTenant(TenantContext.current()) {
                copies.replace(id, version, body.copies)
                view(id)
            }
        return ResponseEntity.ok().eTag(etagOf(view.memoVersion)).body(view)
    }

    @PostMapping("/api/v1/memos/{id}/copies/additions")
    @SignedIn
    @Operation(operationId = "addMemoCopies", summary = "Copy an approved memo to more people (memo.copy.add); sent at once")
    fun add(
        @PathVariable id: UUID,
        @RequestBody body: CopiesRequest,
    ): CopiesView =
        transactions.inTenant(TenantContext.current()) {
            copies.addAfterApproval(id, body.copies)
            view(id)
        }

    @GetMapping("/api/v1/memos/{id}/receipts")
    @SignedIn
    @Operation(operationId = "listMemoReceipts", summary = "Who received a copy and who has opened the memo")
    fun receipts(
        @PathVariable id: UUID,
    ): ResponseEntity<ReceiptsView> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(read { receiptsOf(id) })

    private fun view(id: UUID): CopiesView {
        val memo = memos.get(id)
        val standing = copies.list(id)
        val deliveries = copyStore.deliveries(id)
        val receipts = copyStore.receipts(id)
        val names = names(standing)
        val people = users.of(standing.map { it.addedBy }).mapValues { it.value.displayName }
        return CopiesView(
            standing.map { c ->
                val sent = deliveries.filter { it.copyId == c.id && it.outcome == DeliveryOutcome.SENT }
                CopyView(
                    c.id,
                    RecipientView(c.kind, c.recipientId, names[c.recipientId].orEmpty()),
                    c.sendWhen,
                    Named(c.addedBy, people[c.addedBy].orEmpty()),
                    c.addedAt,
                    c.sentAt,
                    sent.size,
                    sent.count { it.userId in receipts },
                )
            },
            copies.mayEdit(memo),
            copies.mayAdd(memo),
            memo.version,
        )
    }

    /** For the initiator, co-authors and holders of `audit.read` (design E03 §7). */
    private fun receiptsOf(id: UUID): ReceiptsView {
        val memo = memos.get(id)
        val actor = (ActorContext.current().actor as? Actor.User)?.userId
        val allowed =
            actor == memo.initiatorId || (actor != null && coauthors.isCoauthor(memo.id, actor)) || policy.allows(Permission.AUDIT_READ)
        if (!allowed) throw ForbiddenException(Permission.AUDIT_READ)
        val standing = copies.list(id)
        val deliveries = copyStore.deliveries(id)
        val receipts = copyStore.receipts(id)
        val via = standing.filter { it.kind != RecipientKind.USER }.associate { it.id to it.recipientId }
        val viaNames = names(standing)
        val approvers = routes.route(memo.id).flatMap { it.people }.distinct()
        val people = users.of(deliveries.map { it.userId } + approvers).mapValues { it.value.displayName }
        return ReceiptsView(
            deliveries.map { d ->
                val r = receipts[d.userId]
                ReceiptView(
                    Named(d.userId, people[d.userId].orEmpty()),
                    via[d.copyId]?.let(viaNames::get),
                    d.outcome,
                    r?.openedAt,
                    r?.documentVersion,
                )
            },
            approvers.map { a ->
                val r = receipts[a]
                ReceiptView(Named(a, people[a].orEmpty()), openedAt = r?.openedAt, documentVersion = r?.documentVersion)
            },
        )
    }

    private fun names(standing: List<MemoCopy>): Map<UUID, String> {
        val people = users.of(standing.filter { it.kind == RecipientKind.USER }.map { it.recipientId }).mapValues { it.value.displayName }
        val positions =
            directory
                .positions(
                    standing
                        .filter {
                            it.kind == RecipientKind.POSITION
                        }.map { it.recipientId },
                ).mapValues { it.value.title }
        val listNames = lists.names(standing.filter { it.kind == RecipientKind.LIST }.map { it.recipientId })
        return people + positions + listNames
    }

    private fun <T> read(block: () -> T): T = transactions.readInTenant(TenantContext.current(), block)
}
