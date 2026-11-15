package com.memo.memo.internal

import com.memo.common.authz.Permission
import com.memo.common.authz.Policy
import com.memo.common.authz.SignedIn
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
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
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** The client of a decision, shown to `audit.read` holders only (ADR-0051: personal data). */
data class StampClientView(
    val ip: String? = null,
    val userAgent: String? = null,
)

/** A decision as its stamp shows it (ADR-0051): "authenticated approval", never a digital signature. */
data class StampView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    /** `APPROVE`, `RECOMMEND`, `RETURN` or `REJECT`. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val decision: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val round: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val person: Named,
    val actingFor: Named? = null,
    /** The position they held when deciding. */
    val position: String? = null,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val decidedAt: Instant,
    /** `PASSWORD`, `TOTP`. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val factors: List<String>,
    /** Seconds between the last second-factor proof and the decision; null without a second factor. */
    val secondFactorAgeSeconds: Long? = null,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val stepUpRequired: Boolean,
    val documentNumber: Int? = null,
    /** The first 8 hex characters of the document version's SHA-256. */
    val documentHash: String? = null,
    val client: StampClientView? = null,
)

data class StampCheckProblem(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val approvalId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val code: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val detail: String,
)

data class StampCheckView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val ok: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val checkedAt: Instant,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val problems: List<StampCheckProblem>,
)

/** Stamps of a memo's decisions, and their check (ADR-0051, design E03 §6). For every reader of the memo. */
@RestController
@Tag(name = "memo")
class MemoApprovalsController(
    private val approvals: MemoApprovals,
    private val memos: Memos,
    private val users: UserSummaries,
    private val directory: OrgDirectory,
    private val policy: Policy,
    private val transactions: TenantTransactions,
    private val clock: java.time.Clock,
) {
    @GetMapping("/api/v1/memos/{id}/approvals")
    @SignedIn
    @Operation(operationId = "listMemoApprovals", summary = "The memo's authenticated approvals, as stamps")
    fun list(
        @PathVariable id: UUID,
    ): ResponseEntity<List<StampView>> {
        val stamps =
            transactions.readInTenant(TenantContext.current()) {
                memos.get(id)
                val rows = approvals.of(id)
                val names = users.of(rows.flatMap { listOfNotNull(it.actorId, it.actingForId) }).mapValues { it.value.displayName }
                val titles = directory.positions(rows.mapNotNull { it.positionId }).mapValues { it.value.title }
                val auditor = policy.allows(Permission.AUDIT_READ)
                rows.map { stamp(it, names, titles, auditor) }
            }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(stamps)
    }

    @PostMapping("/api/v1/memos/{id}/approvals/check")
    @SignedIn
    @Operation(operationId = "checkMemoApprovals", summary = "Recompute the approvals' hashes and chain from storage")
    fun check(
        @PathVariable id: UUID,
    ): StampCheckView =
        transactions.inTenant(TenantContext.current()) {
            memos.get(id)
            val problems = approvals.check(id)
            StampCheckView(problems.isEmpty(), clock.instant(), problems.map { StampCheckProblem(it.approvalId, it.code, it.detail) })
        }

    private fun stamp(
        a: MemoApproval,
        names: Map<UUID, String>,
        titles: Map<UUID, String>,
        auditor: Boolean,
    ): StampView {
        val second =
            a.auth["secondFactorAt"]
                ?.takeIf { it.isString }
                ?.asString()
                ?.let(Instant::parse)
        return StampView(
            id = a.id,
            decision = a.decision,
            round = a.round,
            person = Named(a.actorId, names[a.actorId].orEmpty()),
            actingFor = a.actingForId?.let { Named(it, names[it].orEmpty()) },
            position = a.positionId?.let(titles::get),
            decidedAt = a.decidedAt,
            factors = a.auth["factors"]?.let { f -> (0 until f.size()).map { f[it].asString() } }.orEmpty(),
            secondFactorAgeSeconds = second?.let { Duration.between(it, a.decidedAt).seconds },
            stepUpRequired = a.auth["stepUpRequired"]?.asBoolean() == true,
            documentNumber = a.content["documentNumber"]?.takeIf { it.isNumber }?.asInt(),
            documentHash =
                a.content["sha256"]
                    ?.takeIf { it.isString }
                    ?.asString()
                    ?.take(SHORT_HASH),
            client = if (auditor) StampClientView(a.client["ip"]?.asString(), a.client["userAgent"]?.asString()) else null,
        )
    }

    private companion object {
        const val SHORT_HASH = 8
    }
}
