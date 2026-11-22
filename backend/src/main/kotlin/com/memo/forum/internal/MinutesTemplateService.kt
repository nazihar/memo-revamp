package com.memo.forum.internal

import com.memo.common.authz.ForbiddenException
import com.memo.common.authz.Permission
import com.memo.common.authz.Policy
import com.memo.common.review.ReviewRules
import com.memo.common.review.ReviewState
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import com.memo.files.FileContents
import com.memo.files.FileUploads
import com.memo.files.FileVersionInfo
import com.memo.files.Files
import com.memo.forum.MinutesTemplateTags
import com.memo.forum.MinutesTemplates
import com.memo.org.OrgDirectory
import com.memo.templating.DocxTemplates
import com.memo.templating.TemplateFiller
import com.memo.templating.TemplateProblem
import com.memo.templating.TemplateRefusal
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.util.UUID

/** A version's minutes template as the Minutes tab shows it: the uploaded file, or none for the built-in one, and its check. */
data class MinutesTemplateInfo(
    /** The version's id: the `owner` of a template upload. */
    val owner: UUID,
    /** The uploaded template's current version; null: the built-in template. */
    val file: FileVersionInfo?,
    /** The tag names it uses, in document order, once each. */
    val tags: List<String>,
    val refusals: List<TemplateRefusal>,
    val problems: List<TemplateProblem>,
    /** Deprecated tags it uses (E06 T7): warnings, the template stays usable. */
    val warnings: List<TemplateProblem> = emptyList(),
)

/**
 * Minutes templates of committee-type versions (design E06 §1.7, flow AI): the check of a version's template against the
 * minutes vocabulary (the built-in one's included), the preview filled with a sample meeting, and the check at submit.
 * File reads, fills and uploads run outside transactions, as the files module asks.
 */
@Service
class MinutesTemplateService(
    private val transactions: TenantTransactions,
    private val versions: ForumTypeVersionStore,
    private val files: Files,
    private val contents: FileContents,
    private val uploads: FileUploads,
    private val templates: DocxTemplates,
    private val builtIn: MinutesTemplates,
    private val filler: TemplateFiller,
    private val policy: Policy,
    private val directory: OrgDirectory,
) {
    /** The template of version [number] of [typeId], checked. For type managers and reviewers. */
    fun info(
        typeId: UUID,
        number: Int,
    ): MinutesTemplateInfo {
        val (row, file) =
            inTenant {
                requireReader()
                val row = version(typeId, number)
                row to current(row)
            }
        val bytes = file?.let(::read) ?: builtIn.builtIn()
        val scan = templates.scan { SeekableInMemoryByteChannel(bytes) }
        val problems = templates.check(scan, MinutesTemplateTags.vocabulary)
        val warnings = templates.warnings(scan, MinutesTemplateTags.vocabulary)
        return MinutesTemplateInfo(row.id, file, scan.tags.map { it.name }.distinct(), scan.refusals, problems, warnings)
    }

    /**
     * Fills the draft's template (or the built-in one) with the sample meeting and stores the result as a preview file,
     * whose PDF the admin opens. Refused while the template has problems.
     */
    fun preview(
        typeId: UUID,
        number: Int,
    ): Pair<UUID, UUID> {
        val (row, file) =
            inTenant {
                policy.require(Permission.FORUM_TYPES_MANAGE)
                val row = version(typeId, number)
                ReviewRules.requireState(row.state, ReviewState.DRAFT, "previewed")
                row to current(row)
            }
        val bytes = file?.let(::read) ?: builtIn.builtIn()
        val scan = templates.scan { SeekableInMemoryByteChannel(bytes) }
        val usable = scan.refusals.isEmpty() && templates.check(scan, MinutesTemplateTags.vocabulary).isEmpty()
        refuseIf(!usable, "TEMPLATE_PROBLEMS") { "Fix the minutes template's problems before previewing it." }
        val filled = filler.fill(bytes.inputStream(), MinutesTemplateSample.data(inTenant { directory.today() }))
        val previewId =
            uploads.upload(
                MinutesTemplatePreviewPurpose.KEY,
                row.id.toString(),
                "minutes-preview.docx",
                filled,
                "minutes-preview:${UUID.randomUUID()}",
            )
        return inTenant { checkNotNull(files.version(previewId)).fileId } to previewId
    }

    /**
     * Before a draft at [revision] is submitted (design E06 §1.8): an uploaded template must have no problems, else 422
     * `TEMPLATE_PROBLEMS` at `minutes/templateFileId`. A draft no longer at [revision] is left to the submit's own check.
     */
    fun requireUsable(
        typeId: UUID,
        number: Int,
        revision: Long,
    ) {
        val file =
            inTenant {
                val row = version(typeId, number)
                if (row.state == ReviewState.DRAFT && row.revision == revision) current(row) else null
            } ?: return
        val bytes = read(file)
        val scan = templates.scan { SeekableInMemoryByteChannel(bytes) }
        val problems = scan.refusals.isNotEmpty() || templates.check(scan, MinutesTemplateTags.vocabulary).isNotEmpty()
        requireNoProblems(
            listOfNotNull(
                FieldProblem("minutes/templateFileId", "TEMPLATE_PROBLEMS", "The minutes template has problems: fix them first.")
                    .takeIf { problems },
            ),
        )
    }

    /** The current version of the version's uploaded template, or null for the built-in one. */
    private fun current(row: ForumTypeVersionRow): FileVersionInfo? =
        row.settings.minutes.templateFileId
            ?.let { files.currentVersions(listOf(it))[it] }

    private fun read(file: FileVersionInfo): ByteArray = ByteArrayOutputStream().also { contents.write(file, it) }.toByteArray()

    private fun version(
        typeId: UUID,
        number: Int,
    ): ForumTypeVersionRow = versions.version(typeId, number) ?: throw ForumNotFoundException("forum type version")

    private fun requireReader() {
        if (!policy.allows(Permission.FORUM_TYPES_MANAGE) && !policy.allows(Permission.FORUM_TYPES_APPROVE)) {
            throw ForbiddenException(Permission.FORUM_TYPES_MANAGE)
        }
    }

    private fun <T> inTenant(block: () -> T): T = transactions.inTenant(TenantContext.current(), block)
}
