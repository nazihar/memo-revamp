package com.memo.memo.internal

import com.memo.common.authz.Permission
import com.memo.common.authz.Policy
import com.memo.common.review.ReviewState
import com.memo.files.ContentRefusal
import com.memo.files.ContentRefused
import com.memo.files.FileInfo
import com.memo.files.FilePurpose
import com.memo.files.FileType
import com.memo.files.StorageClass
import com.memo.templating.DocxTemplates
import com.memo.templating.TagVocabulary
import org.springframework.stereotype.Component
import java.nio.channels.SeekableByteChannel
import java.util.UUID

/**
 * The tags a memo template may use (ADR-0048), published to admins on the memo-type page: the memo's own values, the
 * initiator's, one `meta.<key>` per field of the type version (each may also open a `{{?meta.<key>}}` block), and the
 * approval table.
 */
object MemoTemplateTags {
    const val APPROVALS = "approvals"

    val STANDARD: List<String> =
        listOf(
            "memo.number",
            "memo.date",
            "memo.subject",
            "memo.classification",
            "memo.type",
            "initiator.name",
            "initiator.position",
            "initiator.unit",
        )

    val APPROVAL_FIELDS: List<String> = listOf("name", "position", "decision", "decidedAt")

    fun field(key: String) = "meta.$key"

    fun vocabulary(settings: MemoTypeSettings): TagVocabulary {
        val fields = settings.fields.map { field(it.key) }
        return TagVocabulary(
            values = (STANDARD + fields).toSet(),
            sections = fields.toSet(),
            loops = mapOf(APPROVALS to APPROVAL_FIELDS.toSet()),
        )
    }
}

/**
 * Templates of memo types (ADR-0048): one .docx per type version, uploaded while the version is a draft by holders of
 * `memo.types.manage`, readable by them and by reviewers. Uploads are refused when the package carries macros, ActiveX,
 * embedded objects, external content or field codes that pull content in; tags are checked later, against the version.
 */
@Component
class MemoTemplatePurpose(
    private val versions: MemoTypeVersionStore,
    private val policy: Policy,
    private val templates: DocxTemplates,
) : FilePurpose {
    override val key = KEY
    override val allowedTypes = setOf(FileType.DOCX)
    override val maxBytes = MAX_BYTES
    override val storage = StorageClass.RECORD
    override val auditReads = true

    override fun mayUpload(ownerId: String?): Boolean {
        if (!policy.allows(Permission.MEMO_TYPES_MANAGE)) return false
        val id = ownerId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        return id != null && versions.byId(id)?.state == ReviewState.DRAFT
    }

    override fun mayRead(file: FileInfo): Boolean =
        policy.allows(Permission.MEMO_TYPES_MANAGE) || policy.allows(Permission.MEMO_TYPES_APPROVE)

    override fun inspectContent(
        type: FileType,
        open: () -> SeekableByteChannel,
    ) {
        val refusals = templates.scan(open).refusals
        if (refusals.isNotEmpty()) throw ContentRefused(refusals.map { ContentRefusal(it.rule.name, it.where) })
    }

    companion object {
        const val KEY = "memo.template"
        const val MAX_BYTES = 10L shl 20
    }
}
