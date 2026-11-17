package com.memo.testsupport

import com.memo.common.authz.Permission
import com.memo.common.authz.Policy
import com.memo.common.ratelimit.RateLimit
import com.memo.files.FileInfo
import com.memo.files.FilePurpose
import com.memo.files.FileType
import com.memo.files.FileUploadRejected
import com.memo.files.FileVersionCreated
import com.memo.files.RenditionKind
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

/** Like [TestDocumentPurpose], with both renditions (ADR-0045). */
@Component
class TestRenderedPurpose(
    private val policy: Policy,
) : FilePurpose {
    override val key = KEY
    override val allowedTypes = setOf(FileType.PDF, FileType.DOCX, FileType.XLSX, FileType.PNG, FileType.TEXT, FileType.ZIP)
    override val maxBytes = 50L shl 20
    override val storage = StorageClass.RECORD
    override val auditReads = true
    override val renditions = setOf(RenditionKind.PDF, RenditionKind.TEXT)

    override fun mayUpload(ownerId: String?) = policy.holdsAnywhere(Permission.ORG_MANAGE)

    override fun mayRead(file: FileInfo) = true

    companion object {
        const val KEY = "test.rendered"
    }
}

/**
 * A stand-in for the legacy import's staging purpose (ADR-0055, design E06 §10) until the imports module brings the real
 * one (E06 T5, which removes this): legacy formats, purgeable, ordinary, with the intake's own upload rate. The key is
 * the real one because only `imports.intake` and `memo.legacy` may accept legacy formats.
 */
@Component
class TestIntakePurpose : FilePurpose {
    override val key = KEY
    override val allowedTypes =
        setOf(
            FileType.PDF,
            FileType.DOCX,
            FileType.XLSX,
            FileType.PNG,
            FileType.TEXT,
            FileType.DOC,
            FileType.XLS,
            FileType.PPT,
            FileType.RTF,
            FileType.TIFF,
        )
    override val maxBytes = 100L shl 20
    override val storage = StorageClass.ORDINARY
    override val auditReads = true
    override val acceptsLegacyFormats = true
    override val purgeable = true
    override val uploadRateLimit = RateLimit.IMPORT_INTAKE_PER_USER

    override fun mayUpload(ownerId: String?) = true

    override fun mayRead(file: FileInfo) = true

    companion object {
        const val KEY = "imports.intake"
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

/** Every [FileUploadRejected] published in this JVM's contexts, for tests that check what an owner learns (ADR-0055). */
@Component
class UploadRejections {
    @EventListener
    fun on(event: FileUploadRejected) {
        seen += event
    }

    companion object {
        val seen: MutableList<FileUploadRejected> = java.util.concurrent.CopyOnWriteArrayList()
    }
}
