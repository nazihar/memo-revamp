package com.memo.files.internal

import net.jqwik.api.ForAll
import net.jqwik.api.Property
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.text.Normalizer

class FileNamesProperties {
    @Suppress("ktlint:standard:function-naming")
    @Property(tries = 500)
    fun `a sanitised name has no path, no controls, at most 255 characters, and is never empty`(
        @ForAll declared: String,
    ) {
        val name = FileNames.sanitise(declared, "pdf")

        assertThat(name).isNotBlank().doesNotContain("/", "\\")
        assertThat(name.length).isLessThanOrEqualTo(FileNames.MAX_LENGTH)
        assertThat(name.none { Character.getType(it) == Character.CONTROL.toInt() || Character.getType(it) == Character.FORMAT.toInt() })
            .isTrue()
        assertThat(Normalizer.isNormalized(name, Normalizer.Form.NFC)).isTrue()
    }

    @Test
    fun `paths, controls and bidi overrides are removed, and long names keep their extension`() {
        assertThat(FileNames.sanitise("../../etc/passwd", "txt")).isEqualTo("passwd")
        assertThat(FileNames.sanitise("C:\\Users\\x\\Q3 report.pdf", "pdf")).isEqualTo("Q3 report.pdf")
        assertThat(FileNames.sanitise("invoice\u202Efdp.exe", "pdf")).isEqualTo("invoicefdp.exe")
        assertThat(FileNames.sanitise("  ", "png")).isEqualTo("file.png")
        assertThat(FileNames.sanitise("a".repeat(400) + ".docx", "docx")).hasSize(255).endsWith(".docx")
        assertThat(FileNames.extension("Archive.TAR.GZ")).isEqualTo("gz")
        assertThat(FileNames.extension("README")).isNull()
    }
}
