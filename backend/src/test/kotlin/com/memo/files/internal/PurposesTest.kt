package com.memo.files.internal

import com.memo.common.ratelimit.RateLimit
import com.memo.files.FileInfo
import com.memo.files.FilePurpose
import com.memo.files.FileType
import com.memo.files.RenditionKind
import com.memo.files.StorageClass
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * The start-up check of purpose declarations (ADR-0029, ADR-0055; test plan E06 T2 "architecture"): only the import's
 * purposes accept legacy formats, a purpose that allows one must say so, only ordinary purposes without renditions are
 * purgeable, and a purpose names an upload limit.
 */
class PurposesTest {
    private class Declared(
        override val key: String,
        override val allowedTypes: Set<FileType> = setOf(FileType.PDF),
        override val storage: StorageClass = StorageClass.ORDINARY,
        override val acceptsLegacyFormats: Boolean = false,
        override val purgeable: Boolean = false,
        override val renditions: Set<RenditionKind> = emptySet(),
        override val uploadRateLimit: RateLimit = RateLimit.UPLOAD_PER_USER,
    ) : FilePurpose {
        override val maxBytes = 1L shl 20
        override val auditReads = true

        override fun mayUpload(ownerId: String?) = true

        override fun mayRead(file: FileInfo) = true
    }

    private fun refused(purpose: FilePurpose) =
        assertThatThrownBy { Purposes.verify(purpose) }.isInstanceOf(IllegalStateException::class.java)

    @Test
    fun `only the import's staging and imported originals accept legacy formats`() {
        FilePurpose.LEGACY_FORMAT_PURPOSES.forEach { key ->
            assertThatCode { Purposes.verify(Declared(key, FileType.LEGACY + FileType.PDF, acceptsLegacyFormats = true)) }
                .doesNotThrowAnyException()
        }
        refused(Declared("memo.attachment", setOf(FileType.PDF, FileType.DOC), acceptsLegacyFormats = true))
            .hasMessageContaining("may not accept legacy formats")
    }

    @Test
    fun `a purpose that allows a legacy type without accepting legacy formats stops the start`() {
        FileType.LEGACY.forEach { type ->
            refused(Declared("memo.attachment", setOf(FileType.PDF, type))).hasMessageContaining("allows a legacy format")
        }
        refused(Declared("imports.intake", setOf(FileType.OLE2), acceptsLegacyFormats = true)).hasMessageContaining("never allowed")
    }

    @Test
    fun `records and purposes with renditions are never purgeable`() {
        assertThatCode { Purposes.verify(Declared("imports.source", purgeable = true)) }.doesNotThrowAnyException()
        refused(Declared("imports.record", storage = StorageClass.RECORD, purgeable = true)).hasMessageContaining("purgeable")
        refused(Declared("imports.source", purgeable = true, renditions = setOf(RenditionKind.TEXT))).hasMessageContaining("purgeable")
    }

    @Test
    fun `a purpose's upload limit is one of the upload limits`() {
        assertThatCode { Purposes.verify(Declared("imports.intake", uploadRateLimit = RateLimit.IMPORT_INTAKE_PER_USER)) }
            .doesNotThrowAnyException()
        refused(Declared("tenant.logo", uploadRateLimit = RateLimit.LOGIN_PER_IP)).hasMessageContaining("limit")
    }
}
