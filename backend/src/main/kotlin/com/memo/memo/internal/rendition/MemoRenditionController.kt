package com.memo.memo.internal.rendition

import com.memo.common.authz.Clearances
import com.memo.common.authz.Permission
import com.memo.common.authz.Policy
import com.memo.common.authz.RequiresPermission
import com.memo.common.authz.SignedIn
import com.memo.common.authz.StepUpCheck
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.memo.internal.Memos
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/** Where a memo's final record is, as one word for the memo page. */
enum class RecordState {
    NONE,
    PENDING,
    READY,
    FAILED,
    ;

    companion object {
        /** Being made wins, then a made record, then a failure; nothing asked for yet is NONE. */
        fun of(states: Collection<RenditionState>): RecordState =
            when {
                states.any { it == RenditionState.PENDING || it == RenditionState.BUILDING } -> PENDING
                RenditionState.READY in states -> READY
                states.isNotEmpty() -> FAILED
                else -> NONE
            }
    }
}

/** What auditors see of a record version (design E04 §7): its hash, its validation, how it was made. */
data class RecordAuditView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val sha256: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val compliant: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val profile: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val validator: String,
    /** Failed PDF/A rules (clause-test) with how often each failed. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val failedRules: Map<String, Int>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val rasterisedPages: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val placeholders: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val renderer: String,
)

data class RecordVersionView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val version: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val state: RenditionState,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val trigger: RenditionTrigger,
    val reason: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val createdAt: Instant,
    val finishedAt: Instant?,
    val pages: Int?,
    val sizeBytes: Long?,
    /** The record's classification, once made. */
    val classification: String?,
    /** Whether the reader is cleared to open it (and it is made). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val readable: Boolean,
    /** Present when [readable]: download through the files API. */
    val fileId: UUID?,
    val versionId: UUID?,
    /** Holders of `audit.read` only. */
    val audit: RecordAuditView?,
)

data class RenditionView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val state: RecordState,
    /** The highest READY version: the record of reference. */
    val current: RecordVersionView?,
    /** Every version, newest first. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val versions: List<RecordVersionView>,
)

/** Why a new version of a final record is made. */
data class RegenerateRequest(
    /** 1 to 500 characters. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val reason: String,
)

/** A memo's final record (ADR-0053, design E04 §5). Readers of the memo only; unknown or unreadable memos are 404. */
@RestController
@Tag(name = "memo")
class MemoRenditionController(
    private val memos: Memos,
    private val renditions: MemoRenditions,
    private val clearances: Clearances,
    private val policy: Policy,
    private val transactions: TenantTransactions,
    private val lifecycle: RecordLifecycle,
    private val stepUp: StepUpCheck,
) {
    @GetMapping("/api/v1/memos/{id}/rendition")
    @SignedIn
    @Operation(operationId = "getMemoRendition", summary = "The memo's final record: its state and versions")
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<RenditionView> {
        val view =
            transactions.readInTenant(TenantContext.current()) {
                val memo = memos.get(id)
                viewOf(renditions.of(memo.id))
            }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view)
    }

    @PostMapping("/api/v1/memos/{id}/rendition/regenerate")
    @RequiresPermission(Permission.MEMO_RENDITION_REGENERATE)
    @Operation(operationId = "regenerateMemoRendition", summary = "Make a new version of the final record, with a reason (step-up)")
    fun regenerate(
        @PathVariable id: UUID,
        @RequestBody body: RegenerateRequest,
    ): ResponseEntity<RenditionView> {
        stepUp.require()
        val view =
            transactions.inTenant(TenantContext.current()) {
                lifecycle.regenerate(id, body.reason)
                viewOf(renditions.of(id))
            }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(view)
    }

    @PostMapping("/api/v1/memos/{id}/rendition/retry")
    @RequiresPermission(Permission.MEMO_RENDITION_REGENERATE)
    @Operation(operationId = "retryMemoRendition", summary = "Try a failed final record again")
    fun retry(
        @PathVariable id: UUID,
    ): ResponseEntity<RenditionView> {
        val view =
            transactions.inTenant(TenantContext.current()) {
                lifecycle.retry(id)
                viewOf(renditions.of(id))
            }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(view)
    }

    private fun viewOf(versions: List<MemoRendition>): RenditionView {
        val names = clearances.levels().associate { it.id to it.name }
        val rank = policy.clearanceRank()
        val auditor = policy.allows(Permission.AUDIT_READ)
        val views = versions.map { versionOf(it, names, rank, auditor) }
        return RenditionView(RecordState.of(versions.map { it.state }), views.firstOrNull { it.state == RenditionState.READY }, views)
    }

    private fun versionOf(
        r: MemoRendition,
        names: Map<UUID, String>,
        rank: Int,
        auditor: Boolean,
    ): RecordVersionView {
        val readable = r.state == RenditionState.READY && rank >= (r.classificationRank ?: Int.MAX_VALUE)
        return RecordVersionView(
            version = r.version,
            state = r.state,
            trigger = r.trigger,
            reason = r.reason,
            createdAt = r.createdAt,
            finishedAt = r.finishedAt,
            pages = r.pages,
            sizeBytes = r.sizeBytes,
            classification = r.classificationId?.let(names::get),
            readable = readable,
            fileId = r.fileId.takeIf { readable },
            versionId = r.fileVersionId.takeIf { readable },
            audit = if (auditor && r.state == RenditionState.READY) auditOf(r) else null,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun auditOf(r: MemoRendition) =
        RecordAuditView(
            sha256 = r.sha256.orEmpty(),
            compliant = r.compliant == true,
            profile = r.validation["profile"] as? String ?: "",
            validator = r.validation["validator"] as? String ?: "",
            failedRules = (r.validation["failedRules"] as? Map<String, Number>).orEmpty().mapValues { it.value.toInt() },
            rasterisedPages = (r.flags["rasterisedPages"] as? Number)?.toInt() ?: 0,
            placeholders = (r.flags["placeholders"] as? Number)?.toInt() ?: 0,
            renderer = r.renderer["renderer"] as? String ?: "",
        )
}
