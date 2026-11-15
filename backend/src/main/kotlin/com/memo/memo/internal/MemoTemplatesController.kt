package com.memo.memo.internal

import com.memo.common.authz.Permission
import com.memo.common.authz.RequiresPermission
import com.memo.common.authz.SignedIn
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.etagOf
import com.memo.common.web.ifMatchVersion
import com.memo.common.web.refuseIf
import com.memo.templating.TemplateProblemCode
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/** A tag an admin may copy into a template, with what it stands for. */
data class TemplateTagHelp(
    /** As typed in Word, for example `{{memo.number}}` or `[decidedAt]`. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val tag: String,
    /** The field's label, or the name of a standard value. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val label: String,
    /** `VALUE`, `SECTION` (a block shown only when the field has a value) or `ROW_FIELD` (a field of the approval row). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val kind: String,
)

/** One problem of a template, where it is and how to fix it. */
data class TemplateProblemView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val code: TemplateProblemCode,
    /** "Body", "Header 1", "Footer 2", "Footnotes" or "Endnotes". */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val part: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val paragraph: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val excerpt: String,
    val tag: String? = null,
    val suggestion: String? = null,
)

/** A version's template file and what its check found. */
data class TemplateFileView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val fileId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val versionId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val sizeBytes: Long,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val uploadedAt: Instant,
    /** The tag names it uses, in document order, once each. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val tags: List<String>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val problems: List<TemplateProblemView>,
    /** Whether this template version was previewed without error (needed to submit the version for review). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val previewed: Boolean,
)

/** The template block of a memo-type version: its template (if any) and the tags it may use. */
data class MemoTypeTemplateView(
    /** The version's id: the `owner` of a template upload. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val owner: UUID,
    val template: TemplateFileView?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val available: List<TemplateTagHelp>,
)

data class TemplatePreviewView(
    /** A file of purpose `memo.template.preview`; open its PDF rendition. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val fileId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val versionId: UUID,
)

/**
 * Templates of memo-type versions (ADR-0048, design E03 §3). Upload through `POST /files?purpose=memo.template&owner=
 * <versionId>`; here: the check, the preview and removal. The memo's own fill is under `/memos/{id}/document`.
 */
@RestController
@Tag(name = "memo")
class MemoTemplatesController(
    private val service: MemoTemplateService,
    private val lifecycle: MemoTypeLifecycle,
    private val memos: Memos,
    private val views: MemoViews,
    private val transactions: TenantTransactions,
) {
    @GetMapping("/api/v1/admin/memo-types/{typeId}/versions/{number}/template")
    @RequiresPermission(Permission.MEMO_TYPES_MANAGE, orAnyOf = [Permission.MEMO_TYPES_APPROVE])
    @Operation(operationId = "getMemoTypeTemplate", summary = "A version's template, checked, and the tags it may use")
    fun get(
        @PathVariable typeId: UUID,
        @PathVariable number: String,
    ): ResponseEntity<MemoTypeTemplateView> {
        val info = service.info(typeId, versionNumber(number))
        val row = transactions.readInTenant(TenantContext.current()) { lifecycle.get(typeId, versionNumber(number)) }
        val view =
            MemoTypeTemplateView(
                owner = row.id,
                template =
                    info?.let {
                        TemplateFileView(
                            it.fileId,
                            it.versionId,
                            it.name,
                            it.sizeBytes,
                            it.uploadedAt,
                            it.tags,
                            it.problems.map { p ->
                                TemplateProblemView(p.code, p.location.part, p.location.paragraph, p.excerpt, p.tag, p.suggestion)
                            },
                            it.previewed,
                        )
                    },
                available = help(row.settings),
            )
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view)
    }

    @PostMapping("/api/v1/admin/memo-types/{typeId}/versions/{number}/template/preview")
    @RequiresPermission(Permission.MEMO_TYPES_MANAGE)
    @Operation(operationId = "previewMemoTypeTemplate", summary = "Fill a draft's template with sample values, for its PDF preview")
    fun preview(
        @PathVariable typeId: UUID,
        @PathVariable number: String,
    ): TemplatePreviewView = service.preview(typeId, versionNumber(number)).let { (file, version) -> TemplatePreviewView(file, version) }

    @DeleteMapping("/api/v1/admin/memo-types/{typeId}/versions/{number}/template")
    @RequiresPermission(Permission.MEMO_TYPES_MANAGE)
    @Operation(operationId = "removeMemoTypeTemplate", summary = "Take the template off a draft")
    fun remove(
        @PathVariable typeId: UUID,
        @PathVariable number: String,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
    ): ResponseEntity<Unit> {
        service.remove(typeId, versionNumber(number), ifMatchVersion(ifMatch))
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/api/v1/memos/{id}/document/from-template")
    @SignedIn
    @Operation(operationId = "fillMemoDocumentFromTemplate", summary = "Fill a draft's document from its type's template")
    fun fill(
        @PathVariable id: UUID,
    ): ResponseEntity<MemoView> {
        val outcome = service.fillDocument(id)
        refuseIf(outcome == TemplateFill.NO_TEMPLATE, "NO_TEMPLATE") { "This memo's type has no template." }
        refuseIf(outcome == TemplateFill.NOT_NEEDED, "WRONG_STATE") { "Only a draft without a document can be filled from the template." }
        refuseIf(
            outcome == TemplateFill.FAILED,
            "TEMPLATE_FILL_FAILED",
        ) { "The document could not be filled from the template. Try again." }
        val view = transactions.inTenant(TenantContext.current()) { views.of(memos.get(id)) }
        return ResponseEntity.ok().eTag(etagOf(view.version)).body(view)
    }

    /** The tags of [settings], in the order of the admin's list. */
    private fun help(settings: MemoTypeSettings): List<TemplateTagHelp> =
        MemoTemplateTags.STANDARD.map { TemplateTagHelp("{{$it}}", STANDARD_LABELS.getValue(it), "VALUE") } +
            settings.fields.map { TemplateTagHelp("{{${MemoTemplateTags.field(it.key)}}}", it.label, "VALUE") } +
            settings.fields.map {
                TemplateTagHelp("{{?${MemoTemplateTags.field(it.key)}}}…{{/${MemoTemplateTags.field(it.key)}}}", it.label, "SECTION")
            } +
            TemplateTagHelp("{{${MemoTemplateTags.APPROVALS}}}", "Approval table (in its header row)", "VALUE") +
            MemoTemplateTags.APPROVAL_FIELDS.map { TemplateTagHelp("[$it]", APPROVAL_LABELS.getValue(it), "ROW_FIELD") }

    private companion object {
        val STANDARD_LABELS =
            mapOf(
                "memo.number" to "Memo number",
                "memo.date" to "Date",
                "memo.subject" to "Subject",
                "memo.classification" to "Classification",
                "memo.type" to "Memo type",
                "initiator.name" to "Initiator",
                "initiator.position" to "Initiator's position",
                "initiator.unit" to "Initiator's unit",
            )
        val APPROVAL_LABELS =
            mapOf("name" to "Approver", "position" to "Approver's position", "decision" to "Decision", "decidedAt" to "Date of decision")
    }
}

private fun versionNumber(path: String): Int = path.toIntOrNull() ?: throw MemoNotFoundException("memo type version")
