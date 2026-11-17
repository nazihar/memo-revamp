package com.memo.memo.internal

import com.memo.audit.AuditLog
import com.memo.common.authz.ForbiddenException
import com.memo.common.authz.Permission
import com.memo.common.authz.Policy
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.review.ReviewRules
import com.memo.common.review.ReviewState
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.refuseIf
import com.memo.files.FileContents
import com.memo.files.FileUploads
import com.memo.files.FileVersionInfo
import com.memo.files.Files
import com.memo.org.OrgDirectory
import com.memo.org.OrgUnitDirectory
import com.memo.templating.DocxTemplates
import com.memo.templating.TemplateData
import com.memo.templating.TemplateFillException
import com.memo.templating.TemplateFiller
import com.memo.templating.TemplateProblem
import com.memo.templating.TemplateScan
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID

/** A type version's template as the admin screen shows it. */
data class TemplateInfo(
    val fileId: UUID,
    val versionId: UUID,
    val name: String,
    val sizeBytes: Long,
    val uploadedAt: Instant,
    val tags: List<String>,
    val problems: List<TemplateProblem>,
    val previewed: Boolean,
)

/** What filling a memo's document from its type's template came to. */
enum class TemplateFill { FILLED, NO_TEMPLATE, NOT_NEEDED, FAILED }

/**
 * Templates of memo types (ADR-0048, design E03 §3): the check of a version's template (scanned once per template
 * version, checked against the version's fields each time), the preview with sample values that maker-checker
 * requires, and the first fill of a memo's document. File reads, fills and uploads run outside transactions.
 */
@Service
class MemoTemplateService(
    private val transactions: TenantTransactions,
    private val versions: MemoTypeVersionStore,
    private val store: MemoTemplateStore,
    private val files: Files,
    private val contents: FileContents,
    private val uploads: FileUploads,
    private val templates: DocxTemplates,
    private val filler: TemplateFiller,
    private val policy: Policy,
    private val audit: AuditLog,
    private val memos: Memos,
    private val types: MemoTypes,
    private val views: MemoViews,
    private val directory: OrgDirectory,
    private val units: OrgUnitDirectory,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** The template of version [number] of [typeId], checked; null without one. For type managers and reviewers. */
    fun info(
        typeId: UUID,
        number: Int,
    ): TemplateInfo? {
        val (row, state, file) =
            inTenant {
                requireReader()
                val row = version(typeId, number)
                val state = checkNotNull(store.of(row.id))
                Triple(row, state, state.fileVersionId?.let(files::version))
            }
        if (file == null) return null
        val scan = state.currentScan ?: scan(row.id, file)
        val problems = templates.check(scan, MemoTemplateTags.vocabulary(row.settings))
        val tags = scan.tags.map { it.name }.distinct()
        return TemplateInfo(file.fileId, file.id, file.name, file.sizeBytes, file.uploadedAt, tags, problems, state.previewed)
    }

    /**
     * Fills the draft's template with sample values (field labels in brackets, today's date, a sample approver) and
     * stores the result as a preview file, whose PDF the admin opens; records the template version as previewed.
     */
    fun preview(
        typeId: UUID,
        number: Int,
    ): Pair<UUID, UUID> {
        val (row, state, file) =
            inTenant {
                policy.require(Permission.MEMO_TYPES_MANAGE)
                val row = version(typeId, number)
                ReviewRules.requireState(row.state, ReviewState.DRAFT, "previewed")
                val state = checkNotNull(store.of(row.id))
                refuseIf(!state.present, "NO_TEMPLATE") { "This version has no template." }
                Triple(row, state, checkNotNull(files.version(state.fileVersionId!!)))
            }
        val scan = state.currentScan ?: scan(row.id, file)
        val problems = templates.check(scan, MemoTemplateTags.vocabulary(row.settings))
        refuseIf(problems.isNotEmpty(), "TEMPLATE_PROBLEMS") { "Fix the template's problems before previewing it." }
        val filled = filler.fill(read(file).inputStream(), MemoTemplateValues.sample(row.settings, inTenant { directory.today() }))
        val previewId =
            uploads.upload(
                MemoTemplatePreviewPurpose.KEY,
                row.id.toString(),
                "preview.docx",
                filled,
                "template-preview:${UUID.randomUUID()}",
            )
        inTenant {
            if (store.markPreviewed(row.id, file.id)) {
                audit.record(
                    "memo.type.template_previewed",
                    MemoTypes.OBJECT_TYPE,
                    typeId.toString(),
                    after = mapOf("version" to number, "templateVersionId" to file.id.toString()),
                )
            }
        }
        return inTenant { checkNotNull(files.version(previewId)).fileId } to previewId
    }

    /** Takes the template off a draft; [revision] is the client's `If-Match` on the version. */
    fun remove(
        typeId: UUID,
        number: Int,
        revision: Long,
    ) = inTenant {
        policy.require(Permission.MEMO_TYPES_MANAGE)
        val row = version(typeId, number)
        ReviewRules.requireState(row.state, ReviewState.DRAFT, "changed")
        val state = checkNotNull(store.lock(row.id))
        if (state.revision != revision) throw ConcurrentModificationException("memo type version ${row.id}", revision)
        if (state.present) {
            store.set(row.id, null, null)
            audit.record("memo.type.template_removed", MemoTypes.OBJECT_TYPE, typeId.toString(), after = mapOf("version" to number))
        }
    }

    /**
     * Fills [memoId]'s document from its type's template, as the current user (who must be allowed to upload the
     * memo's document), when the memo is editable and has no document yet. Failures are reported, never thrown, so the
     * memo stays usable; the initiator can try again.
     */
    fun fillDocument(memoId: UUID): TemplateFill {
        val job = inTenant { fillJob(memoId) }
        val template = job?.template
        return when {
            job == null -> TemplateFill.NOT_NEEDED
            template == null -> TemplateFill.NO_TEMPLATE
            else -> fillInto(memoId, template, job)
        }
    }

    private fun fillInto(
        memoId: UUID,
        template: FileVersionInfo,
        job: FillJob,
    ): TemplateFill =
        try {
            val bytes = filler.fill(read(template).inputStream(), job.data)
            uploads.upload(MemoDocumentPurpose.KEY, memoId.toString(), job.name, bytes, "template-fill:$memoId:${template.id}")
            inTenant {
                store.recordFill(memoId, template.id)
                audit.record(
                    "memo.document.filled",
                    Memos.OBJECT_TYPE,
                    memoId.toString(),
                    after = mapOf("templateVersionId" to template.id.toString()),
                )
            }
            TemplateFill.FILLED
        } catch (e: TemplateFillException) {
            log.warn("Memo {} was not filled from its template: {}", memoId, e.failure)
            TemplateFill.FAILED
        }

    private class FillJob(
        val template: FileVersionInfo?,
        val data: TemplateData,
        val name: String,
    )

    private fun fillJob(memoId: UUID): FillJob? {
        val memo = memos.get(memoId)
        val version = types.versionFor(memo)?.takeIf { memo.status.editable && memo.documentFileId == null } ?: return null
        val template = store.of(version.id)?.fileVersionId?.let(files::version)
        val view = views.of(memo)
        val position = directory.positionsOf(listOf(memo.initiatorId))[memo.initiatorId]
        val unit = position?.unitId?.let { units.units(listOf(it))[it]?.name }.orEmpty()
        val data = MemoTemplateValues.of(view, version.settings.fields, position?.title.orEmpty(), unit, directory.today())
        return FillJob(template, data, "${memo.subject.take(MAX_NAME)}.docx")
    }

    private fun scan(
        versionId: UUID,
        file: FileVersionInfo,
    ): TemplateScan {
        val bytes = read(file)
        val scan = templates.scan { SeekableInMemoryByteChannel(bytes) }
        inTenant { store.storeScan(versionId, file.id, scan) }
        return scan
    }

    private fun read(file: FileVersionInfo): ByteArray = ByteArrayOutputStream().also { contents.write(file, it) }.toByteArray()

    private fun version(
        typeId: UUID,
        number: Int,
    ): MemoTypeVersionRow = versions.version(typeId, number) ?: throw MemoNotFoundException("memo type version")

    private fun requireReader() {
        if (!policy.allows(Permission.MEMO_TYPES_MANAGE) && !policy.allows(Permission.MEMO_TYPES_APPROVE)) {
            throw ForbiddenException(Permission.MEMO_TYPES_MANAGE)
        }
    }

    private fun <T> inTenant(block: () -> T): T = transactions.inTenant(TenantContext.current(), block)

    private companion object {
        const val MAX_NAME = 120
    }
}
