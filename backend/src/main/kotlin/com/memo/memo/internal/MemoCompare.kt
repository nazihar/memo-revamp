package com.memo.memo.internal

import com.memo.audit.AuditLog
import com.memo.common.authz.Policy
import com.memo.common.authz.SignedIn
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.files.FileRenditions
import com.memo.files.FileVersionInfo
import com.memo.files.Files
import com.memo.files.RenditionKind
import com.memo.files.RenditionState
import com.memo.identity.UserSummaries
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID

/** One side of a comparison: the version, who uploaded it, and its text when the text rendition is done. */
data class VersionText(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val number: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val versionId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val sha256: String,
    val uploadedBy: Named?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val uploadedAt: Instant,
    /** The text rendition's state; [text] only when `DONE`. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val state: RenditionState,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val truncated: Boolean,
    val text: String? = null,
)

/** Two versions of a memo's document or attachment, to compare in the browser (design E02 §3). */
data class VersionComparison(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val from: VersionText,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val to: VersionText,
)

/**
 * Serves the texts of two versions of a memo's document or one of its attachments (design E02 §3, T6). The memo's
 * readers may compare its document; an attachment also needs its effective level. Both reads are audited as opens with
 * `channel: compare`. The diff itself is computed in the browser.
 */
@Service
class MemoCompare(
    private val memos: Memos,
    private val attachments: MemoAttachmentStore,
    private val files: Files,
    private val renditions: FileRenditions,
    private val users: UserSummaries,
    private val policy: Policy,
    private val audit: AuditLog,
    private val transactions: TenantTransactions,
) {
    fun compare(
        memoId: UUID,
        fileId: UUID,
        from: Int,
        to: Int,
    ): VersionComparison {
        val (left, right) = transactions.inTenant(TenantContext.current()) { resolve(memoId, fileId, from, to) }
        return VersionComparison(withText(left), withText(right))
    }

    /** Both versions as the reader may see them, with their states; the reads are audited here. */
    private fun resolve(
        memoId: UUID,
        fileId: UUID,
        from: Int,
        to: Int,
    ): Pair<VersionText, VersionText> {
        val memo = memos.get(memoId)
        val attachment = attachments.byFile(fileId)?.takeIf { it.memoId == memoId }
        val readable =
            fileId == memo.documentFileId ||
                (attachment != null && attachment.effectiveRank(memo.classificationRank) <= policy.clearanceRank())
        if (!readable) throw MemoNotFoundException()
        val versions = listOf(from, to).map { number -> versionOf(fileId, number) ?: throw MemoNotFoundException() }
        val names = users.of(versions.mapNotNull { it.uploadedBy }).mapValues { it.value.displayName }
        val texts =
            versions.map { version ->
                val text = renditions.of(version.id).firstOrNull { it.kind == RenditionKind.TEXT }
                audit.record(
                    "file.opened",
                    "file.version",
                    version.id.toString(),
                    after = mapOf("fileId" to fileId.toString(), "channel" to "compare", "memoId" to memoId.toString()),
                )
                VersionText(
                    number = version.number,
                    versionId = version.id,
                    name = version.name,
                    sha256 = version.sha256,
                    uploadedBy = version.uploadedBy?.let { Named(it, names[it].orEmpty()) },
                    uploadedAt = version.uploadedAt,
                    state = text?.state ?: RenditionState.NOT_POSSIBLE,
                    truncated = text?.truncated == true,
                )
            }
        return texts[0] to texts[1]
    }

    private fun versionOf(
        fileId: UUID,
        number: Int,
    ): FileVersionInfo? = files.versions(fileId, 1, number + 1).firstOrNull()?.takeIf { it.number == number }

    private fun withText(side: VersionText): VersionText {
        if (side.state != RenditionState.DONE) return side
        val out = ByteArrayOutputStream()
        return if (renditions.write(side.versionId, RenditionKind.TEXT, out)) side.copy(text = out.toString(Charsets.UTF_8)) else side
    }
}

@RestController
@RequestMapping("/api/v1/memos/{id}")
@Tag(name = "memo")
class MemoCompareController(
    private val compare: MemoCompare,
) {
    @GetMapping("/files/{fileId}/compare")
    @SignedIn
    @Operation(operationId = "compareMemoFileVersions", summary = "The texts of two versions of the memo's document or an attachment")
    fun compare(
        @PathVariable id: UUID,
        @PathVariable fileId: UUID,
        @RequestParam from: Int,
        @RequestParam to: Int,
    ): VersionComparison = compare.compare(id, fileId, from, to)
}
