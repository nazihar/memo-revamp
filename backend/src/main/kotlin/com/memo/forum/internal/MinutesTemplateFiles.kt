package com.memo.forum.internal

import com.memo.audit.AuditLog
import com.memo.common.authz.Permission
import com.memo.common.authz.Policy
import com.memo.common.review.ReviewState
import com.memo.common.web.refuseIf
import com.memo.files.ContentRefusal
import com.memo.files.ContentRefused
import com.memo.files.FileInfo
import com.memo.files.FilePurpose
import com.memo.files.FileType
import com.memo.files.FileVersionCreated
import com.memo.files.RenditionKind
import com.memo.files.StorageClass
import com.memo.forum.MinutesTemplates
import com.memo.templating.DocxTemplates
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.nio.channels.SeekableByteChannel
import java.util.UUID

/**
 * Minutes templates of committee-type versions (design E06 §1.7, §6; ADR-0048): one .docx per draft version, uploaded by
 * holders of `forum.types.manage` with the version's id as owner, readable by them and by reviewers. Uploads carrying
 * macros, ActiveX, embedded objects, external content or field codes that pull content in are refused; the tags are
 * checked against the minutes vocabulary on the Minutes tab and when the version is submitted.
 */
@Component
class MinutesTemplatePurpose(
    private val versions: ForumTypeVersionStore,
    private val policy: Policy,
    private val templates: DocxTemplates,
) : FilePurpose {
    override val key = KEY
    override val allowedTypes = setOf(FileType.DOCX)
    override val maxBytes = MAX_BYTES
    override val storage = StorageClass.ORDINARY
    override val auditReads = true

    override fun mayUpload(ownerId: String?): Boolean = policy.allows(Permission.FORUM_TYPES_MANAGE) && draft(versions, ownerId)

    override fun mayRead(file: FileInfo): Boolean =
        policy.allows(Permission.FORUM_TYPES_MANAGE) || policy.allows(Permission.FORUM_TYPES_APPROVE)

    override fun inspectContent(
        type: FileType,
        open: () -> SeekableByteChannel,
    ) {
        val refusals = templates.scan(open).refusals
        if (refusals.isNotEmpty()) throw ContentRefused(refusals.map { ContentRefusal(it.rule.name, it.where) })
    }

    companion object {
        const val KEY = MinutesTemplates.PURPOSE
        const val MAX_BYTES = 10L shl 20
    }
}

/**
 * A minutes template filled with the sample meeting (flow AI's "Preview with a sample meeting"): .docx with a PDF
 * rendition for the preview; ordinary objects, not records. Uploaded by the server for type managers; read by them and
 * by reviewers.
 */
@Component
class MinutesTemplatePreviewPurpose(
    private val versions: ForumTypeVersionStore,
    private val policy: Policy,
) : FilePurpose {
    override val key = KEY
    override val allowedTypes = setOf(FileType.DOCX)
    override val maxBytes = MinutesTemplatePurpose.MAX_BYTES * 2
    override val storage = StorageClass.ORDINARY
    override val auditReads = false
    override val renditions = setOf(RenditionKind.PDF)

    override fun mayUpload(ownerId: String?): Boolean = policy.allows(Permission.FORUM_TYPES_MANAGE) && draft(versions, ownerId)

    override fun mayRead(file: FileInfo): Boolean =
        policy.allows(Permission.FORUM_TYPES_MANAGE) || policy.allows(Permission.FORUM_TYPES_APPROVE)

    companion object {
        const val KEY = "forum.minutes-template.preview"
    }
}

private fun draft(
    versions: ForumTypeVersionStore,
    ownerId: String?,
): Boolean {
    val id = ownerId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    return id != null && versions.byId(id)?.state == ReviewState.DRAFT
}

/**
 * Makes each template version uploaded for a draft the draft's minutes template, in the upload's transaction under the
 * version's row lock: a version that left draft since the upload was allowed vetoes it (409). The draft's revision moves
 * on, as for any change of the draft, so an editor holding the older one reloads before saving.
 */
@Component
class MinutesTemplateListener(
    private val versions: ForumTypeVersionStore,
    private val audit: AuditLog,
) {
    @EventListener
    fun onVersion(event: FileVersionCreated) {
        if (event.purposeKey != MinutesTemplatePurpose.KEY) return
        val versionId = event.ownerId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val row = versionId?.let(versions::lock) ?: throw ForumNotFoundException("forum type version")
        refuseIf(row.state != ReviewState.DRAFT, "WRONG_STATE") { "The minutes template can change only while the version is a draft." }
        val settings = row.settings.copy(minutes = row.settings.minutes.copy(templateFileId = event.fileId))
        check(versions.replace(row.id, row.revision, settings)) { "the locked draft changed" }
        audit.record(
            "forum.type.minutes_template_uploaded",
            ForumTypes.OBJECT_TYPE,
            row.typeId.toString(),
            after =
                mapOf(
                    "version" to row.number,
                    "templateFileId" to event.fileId.toString(),
                    "templateVersionId" to event.versionId.toString(),
                ),
        )
    }
}
