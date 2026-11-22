package com.memo.actions.internal

import com.memo.files.FileInfo
import com.memo.files.FilePurpose
import com.memo.files.FileType
import com.memo.files.StorageClass
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Evidence on an action (ADR-0067, design E07 §1.6, §1.14, §6): files the owner, a contributor or a secretary writing
 * for the owner attach to an update (and, from T5, to marking it done). They go through the whole upload pipeline (ClamAV,
 * then the type by content, at most 25 MiB each, ADR-0029), stored per tenant encrypted as ordinary objects owned by the
 * action, and are read only by those who see the action ([ActionAccess]): the generic files endpoints serve them to
 * nobody else. Every open is audited.
 */
@Component
class ActionEvidencePurpose(
    private val store: ActionStore,
    private val viewers: ActionViewers,
    private val access: ActionAccess,
    private val scenes: ActionScenes,
    private val lines: ActionLines,
) : FilePurpose {
    override val key = KEY
    override val allowedTypes =
        setOf(
            FileType.PDF,
            FileType.PNG,
            FileType.JPEG,
            FileType.WEBP,
            FileType.TEXT,
            FileType.DOCX,
            FileType.XLSX,
            FileType.PPTX,
            FileType.ODT,
            FileType.ODS,
            FileType.ODP,
        )
    override val maxBytes = MAX_BYTES
    override val storage = StorageClass.ORDINARY
    override val auditReads = true
    override val inlineTypes = setOf(FileType.PDF, FileType.PNG, FileType.JPEG, FileType.WEBP)

    /**
     * For an open action the actor sees and may write on: its owner, a contributor not recused from the item, or a
     * secretary of the committee not recused.
     */
    override fun mayUpload(ownerId: String?): Boolean {
        val row = actionOf(ownerId)?.takeIf { it.status in ActionChecks.OPEN }
        val viewer = row?.let { viewer() }
        return row != null && viewer != null && access.sees(viewer, row.id, row.forumId) && writes(viewer, row)
    }

    /** Whoever sees the action (the one rule). */
    override fun mayRead(file: FileInfo): Boolean {
        val row = actionOf(file.ownerId)
        val viewer = row?.let { viewer() }
        return row != null && viewer != null && access.sees(viewer, row.id, row.forumId)
    }

    /** The owner, or a contributor or secretary not recused from the item. */
    private fun writes(
        viewer: ActionViewer,
        row: ActionRow,
    ): Boolean {
        val owner = viewer.userId in scenes.owners(row) || row.owner?.positionId?.let { it in viewer.positions } == true
        val recused = row.itemId != null && access.recused(viewer, row.id)
        val contributor = viewer.userId in lines.contributorsOf(row.id)
        return owner || (!recused && (contributor || viewer.secretary(row.forumId)))
    }

    private fun viewer(): ActionViewer? = runCatching { viewers.actor() }.getOrNull()

    private fun actionOf(ownerId: String?): ActionRow? = ownerId?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let(store::find)

    companion object {
        const val KEY = "action.evidence"
        const val MAX_FILES = 10
        const val MAX_BYTES = 25L * 1024 * 1024
    }
}
