package com.memo.testsupport

import com.memo.common.authz.Permission
import com.memo.common.authz.Policy
import com.memo.files.FileInfo
import com.memo.files.FilePurpose
import com.memo.files.FileType
import com.memo.files.FileVersionCreated
import com.memo.files.StorageClass
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Test-only file purposes (design E05 §3), so the files service can be exercised before its first real owner (E07) and
 * so no test permission enters the catalogue. Picked up by component scanning in every test context.
 */
@Component
class TestDocumentPurpose(
    private val policy: Policy,
) : FilePurpose {
    override val key = KEY
    override val allowedTypes =
        setOf(
            FileType.PDF,
            FileType.DOCX,
            FileType.XLSX,
            FileType.PPTX,
            FileType.ODT,
            FileType.ODS,
            FileType.ODP,
            FileType.PNG,
            FileType.JPEG,
            FileType.TEXT,
            FileType.ZIP,
        )
    override val maxBytes = 50L shl 20
    override val storage = StorageClass.RECORD
    override val auditReads = true
    override val inlineTypes = setOf(FileType.PDF, FileType.PNG, FileType.JPEG)

    /** Uploads need `org.manage` somewhere (org admins, tenant admins); reads are open to signed-in users. */
    override fun mayUpload(ownerId: String?) = policy.holdsAnywhere(Permission.ORG_MANAGE)

    override fun mayRead(file: FileInfo) = true

    companion object {
        const val KEY = "test.document"
    }
}

@Component
class TestImagePurpose : FilePurpose {
    override val key = KEY
    override val allowedTypes = setOf(FileType.PNG, FileType.JPEG)
    override val maxBytes = 2L shl 20
    override val storage = StorageClass.ORDINARY
    override val auditReads = false
    override val inlineTypes = setOf(FileType.PNG, FileType.JPEG)

    override fun mayUpload(ownerId: String?) = true

    override fun mayRead(file: FileInfo) = true

    companion object {
        const val KEY = "test.image"
    }
}

/** An owner that vetoes uploads for the owner id [VETO], to prove the business transaction rolls back as a whole. */
@Component
class VetoingFileOwner {
    @EventListener
    fun on(event: FileVersionCreated) {
        check(event.ownerId != VETO) { "owner refused the file" }
    }

    companion object {
        const val VETO = "veto"
    }
}
