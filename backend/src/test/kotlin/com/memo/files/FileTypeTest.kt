package com.memo.files

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The legacy types of ADR-0055 (test plan E06 T2): their media types and names, and what stays never allowed. */
class FileTypeTest {
    @Test
    fun `the legacy types carry their media types and extensions`() {
        assertThat(FileType.ofMediaType("application/msword")).isEqualTo(FileType.DOC)
        assertThat(FileType.ofMediaType("application/vnd.ms-excel")).isEqualTo(FileType.XLS)
        assertThat(FileType.ofMediaType("application/vnd.ms-powerpoint")).isEqualTo(FileType.PPT)
        assertThat(FileType.ofMediaType("application/rtf")).isEqualTo(FileType.RTF)
        assertThat(FileType.ofMediaType("image/tiff")).isEqualTo(FileType.TIFF)
        assertThat(FileType.TIFF.extensions).containsExactlyInAnyOrder("tif", "tiff")
        assertThat(FileType.LEGACY.map { it.extensions.first() }).containsExactlyInAnyOrder("doc", "xls", "ppt", "rtf", "tif")
        assertThat(FileType.ofMediaType("application/vnd.ms-outlook")).isNull()
    }

    @Test
    fun `what is never allowed is unchanged, and no legacy type is safe inline`() {
        assertThat(FileType.NEVER_ALLOWED).containsExactlyInAnyOrder(FileType.DOCM, FileType.XLSM, FileType.PPTM, FileType.OLE2)
        assertThat(FileType.LEGACY).doesNotContainAnyElementsOf(FileType.NEVER_ALLOWED)
        assertThat(FileType.INLINE_SAFE).doesNotContainAnyElementsOf(FileType.LEGACY)
        assertThat(FileType.OLE2_DOCUMENTS).containsExactlyInAnyOrder(FileType.DOC, FileType.XLS, FileType.PPT)
    }
}
