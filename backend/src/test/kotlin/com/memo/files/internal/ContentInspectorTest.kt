package com.memo.files.internal

import com.memo.files.FileType
import com.memo.testsupport.Samples
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration

/** ADR-0029: the type comes from content alone; names, macros, polyglots and limits are checked. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContentInspectorTest {
    private val inspector = ContentInspector(Duration.ofSeconds(20))
    private val everything = InspectionRules(FileType.entries.toSet() - FileType.NEVER_ALLOWED, maxPdfPages = 10)

    @AfterAll
    fun stop() = inspector.destroy()

    private fun inspect(
        bytes: ByteArray,
        name: String? = null,
        rules: InspectionRules = everything,
    ) = inspector.inspect({ SeekableInMemoryByteChannel(bytes) }, name, rules)

    private fun rejection(
        bytes: ByteArray,
        name: String? = null,
        rules: InspectionRules = everything,
    ): UploadRejected = catchThrowableOfType(UploadRejected::class.java) { inspect(bytes, name, rules) }

    @Test
    fun `each type is recognised from its content, whatever it is called`() {
        mapOf(
            Samples.pdf(3) to FileType.PDF,
            Samples.png() to FileType.PNG,
            Samples.jpeg() to FileType.JPEG,
            Samples.webp to FileType.WEBP,
            Samples.text to FileType.TEXT,
            Samples.zip("a.txt" to "a".toByteArray()) to FileType.ZIP,
            Samples.docx() to FileType.DOCX,
            Samples.xlsx() to FileType.XLSX,
            Samples.pptx() to FileType.PPTX,
            Samples.odf() to FileType.ODT,
            Samples.odf("application/vnd.oasis.opendocument.spreadsheet") to FileType.ODS,
        ).forEach { (bytes, type) ->
            assertThat(inspect(bytes).type).describedAs(type.name).isEqualTo(type)
        }
        assertThat(inspect(Samples.pdf(3)).pdfPages).isEqualTo(3)
    }

    @Test
    fun `macro documents are refused, also when renamed or disguised`() {
        assertThat(rejection(Samples.docm(), "budget.docm").reason).isEqualTo(RejectionReason.TYPE_NOT_ALLOWED)
        assertThat(rejection(Samples.docm(), "budget.docx").detail["detectedType"]).isEqualTo(FileType.DOCM.mediaType)
        assertThat(rejection(Samples.docxWithVba(), "budget.docx").detail["detectedType"]).isEqualTo(FileType.DOCM.mediaType)
        assertThat(rejection(Samples.odf(macros = true), "notes.odt").detail).containsEntry("macros", true)
        assertThat(rejection(Samples.ole2, "legacy.doc").detail["detectedType"]).isEqualTo(FileType.OLE2.mediaType)
    }

    @Test
    fun `a type the purpose does not allow, or unknown content, is refused with the detected type`() {
        val imagesOnly = InspectionRules(setOf(FileType.PNG, FileType.JPEG), maxPdfPages = 10)

        assertThat(rejection(Samples.pdf(), "a.pdf", imagesOnly).detail["detectedType"]).isEqualTo("application/pdf")
        assertThat(rejection(Samples.html, "logo.png", imagesOnly).detail["detectedType"]).isEqualTo("text/html")
        assertThat(
            rejection(byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 2, 1, 1) + ByteArray(64)).reason,
        ).isEqualTo(RejectionReason.TYPE_NOT_ALLOWED)
    }

    @Test
    fun `the name's extension must agree with the content`() {
        val mismatch = rejection(Samples.pdf(), "report.docx")

        assertThat(mismatch.reason).isEqualTo(RejectionReason.TYPE_MISMATCH)
        assertThat(mismatch.detail).containsEntry("extension", "docx")
        assertThat(inspect(Samples.jpeg(), "photo.JPG").type).isEqualTo(FileType.JPEG)
        assertThat(inspect(Samples.pdf(), "no-extension").type).isEqualTo(FileType.PDF)
    }

    @Test
    fun `a polyglot, a malformed or an encrypted PDF, and too many pages are refused`() {
        assertThat(rejection(Samples.pdfZipPolyglot(), "a.pdf").detail).containsEntry("problem", "polyglot")
        assertThat(rejection(Samples.malformedPdf, "a.pdf").reason).isEqualTo(RejectionReason.MALFORMED)
        assertThat(rejection(Samples.pdf(userPassword = "secret"), "a.pdf").reason).isEqualTo(RejectionReason.ENCRYPTED)
        assertThat(rejection(Samples.pdf(pages = 11), "a.pdf").detail).containsEntry("limit", "pdf-pages")
    }

    @Test
    fun `OOXML content types with a DTD are refused before any entity is expanded`() {
        val withDtd =
            """<?xml version="1.0"?><!DOCTYPE Types [<!ENTITY x "x">]><Types><Default ContentType="&x;"/></Types>"""
        val rejected = catchThrowableOfType(UploadRejected::class.java) { ZipInspector.contentTypesOf(withDtd.byteInputStream()) }

        assertThat(rejected.reason).isEqualTo(RejectionReason.MALFORMED)
    }

    @Test
    fun `a zip with too many entries is refused from its trailer, before the directory is parsed`() {
        val many = Samples.zip(*Array(ZipInspector.MAX_ENTRIES + 1) { "f$it.txt" to ByteArray(0) })

        assertThat(rejection(many, "a.zip").detail).containsEntry("limit", "zip-entries")
    }
}
