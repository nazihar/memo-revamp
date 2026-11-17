package com.memo.files.internal

import com.memo.files.ContentRefusal
import com.memo.files.ContentRefused
import com.memo.files.FileType
import com.memo.testsupport.LegacySamples
import com.memo.testsupport.Samples
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import org.apache.tika.io.TikaInputStream
import org.apache.tika.metadata.Metadata
import org.apache.tika.mime.MimeTypes
import org.apache.tika.parser.ParseContext
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
    fun `a purpose's own content check refuses with its rules and where they are, after the type is known`() {
        val seen = mutableListOf<FileType>()
        val rules =
            everything.copy(content = { type, _ ->
                seen += type
                if (type == FileType.DOCX) throw ContentRefused(listOf(ContentRefusal("MACROS", "word/vbaProject.bin")))
            })
        val rejected = rejection(Samples.docx(), "letterhead.docx", rules)
        assertThat(rejected.reason).isEqualTo(RejectionReason.CONTENT_REFUSED)
        assertThat(rejected.detail["refusals"]).isEqualTo(listOf(mapOf("rule" to "MACROS", "where" to "word/vbaProject.bin")))
        assertThat(inspect(Samples.pdf(), "a.pdf", rules).type).isEqualTo(FileType.PDF)
        assertThat(seen).containsExactly(FileType.DOCX, FileType.PDF)
        // A type the purpose does not allow is refused before its content check runs.
        assertThat(rejection(Samples.docm(), "a.docx", rules).reason).isEqualTo(RejectionReason.TYPE_NOT_ALLOWED)
        assertThat(seen).hasSize(2)
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
    fun `a compression bomb is refused from its central directory, which clamd would only trim`() {
        assertThat(rejection(Samples.zipBomb(200), "a.zip").detail).containsEntry("limit", "zip-ratio")
    }

    @Test
    fun `a zip with too many entries is refused from its trailer, before the directory is parsed`() {
        val many = Samples.zip(*Array(ZipInspector.MAX_ENTRIES + 1) { "f$it.txt" to ByteArray(0) })

        assertThat(rejection(many, "a.zip").detail).containsEntry("limit", "zip-entries")
    }

    @Test
    fun `an OLE2 document is typed by the server only for purposes that accept legacy formats`() {
        // ADR-0055: the magic sees only a container; the Tika server names what is inside, here a stand-in.
        val xls = LegacySamples.xls()
        val asked = mutableListOf<ByteArray>()
        val legacy =
            everything.copy(ole2 = { open ->
                asked += open().use { it.readAllBytes() }
                FileType.XLS
            })

        assertThat(inspect(xls, "ledger.xls", legacy).type).isEqualTo(FileType.XLS)
        assertThat(asked.single()).isEqualTo(xls)
        assertThat(rejection(xls, "ledger.doc", legacy).reason).isEqualTo(RejectionReason.TYPE_MISMATCH)
        // Without the server's answer every OLE2 file stays a container, which no purpose allows.
        assertThat(rejection(xls, "ledger.xls").detail["detectedType"]).isEqualTo(FileType.OLE2.mediaType)
        // The server's refusal (an Outlook message) and its absence (fail closed) reach the uploader unchanged.
        val outlook = "application/vnd.ms-outlook"
        val refusing = everything.copy(ole2 = { _ -> reject(RejectionReason.TYPE_NOT_ALLOWED, "detectedType" to outlook) })
        assertThat(rejection(LegacySamples.msg(), "minutes.msg", refusing).detail["detectedType"]).isEqualTo(outlook)
        val down = everything.copy(ole2 = { _ -> reject(RejectionReason.SCAN_UNAVAILABLE, "scanner" to "unreachable") })
        assertThat(rejection(LegacySamples.doc(), "minutes.doc", down).reason).isEqualTo(RejectionReason.SCAN_UNAVAILABLE)
    }

    @Test
    fun `RTF and TIFF are recognised from content, and only a purpose that allows them takes them`() {
        assertThat(inspect(LegacySamples.rtf, "letter.rtf").type).isEqualTo(FileType.RTF)
        assertThat(inspect(LegacySamples.tiff(), "scan.tif").type).isEqualTo(FileType.TIFF)
        assertThat(inspect(LegacySamples.tiff(), "scan.TIFF").type).isEqualTo(FileType.TIFF)
        val modern = InspectionRules(FileType.entries.toSet() - FileType.NEVER_ALLOWED - FileType.LEGACY, maxPdfPages = 10)
        assertThat(rejection(LegacySamples.rtf, "letter.rtf", modern).reason).isEqualTo(RejectionReason.TYPE_NOT_ALLOWED)
        assertThat(rejection(LegacySamples.tiff(), "scan.tif", modern).detail["detectedType"]).isEqualTo("image/tiff")
    }

    @Test
    fun `an old Office type named by its magic number alone is not taken`() {
        // Word for DOS-era magic: Tika names it msword without any container; only the server may name a .doc.
        val wordTwo = byteArrayOf(0xDB.toByte(), 0xA5.toByte(), 0x2D, 0x00) + ByteArray(1024)
        assertThat(rejection(wordTwo, "old.doc").reason).isEqualTo(RejectionReason.TYPE_NOT_ALLOWED)
    }

    @Test
    fun `an OLE2 file whose directory comes first is still a container, typed by the server`() {
        // The directory in sector 1, its first entry the Word stream: Tika's magic names msword from that name alone.
        val doc = ByteArray(4096)
        intArrayOf(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1).forEachIndexed { i, b -> doc[i] = b.toByte() }
        "WordDocument".toByteArray(Charsets.UTF_16LE).copyInto(doc, destinationOffset = 1152)
        val magic = TikaInputStream.get(doc).use { MimeTypes.getDefaultMimeTypes().detect(it, Metadata(), ParseContext()) }
        assertThat(magic.baseType.toString()).isEqualTo(FileType.DOC.mediaType)

        var asked = 0
        val legacy = everything.copy(ole2 = { _ -> FileType.DOC.also { asked++ } })
        assertThat(inspect(doc, "memo.doc", legacy).type).isEqualTo(FileType.DOC)
        assertThat(asked).isEqualTo(1)
        // A purpose that does not accept legacy formats refuses it as the container it is.
        assertThat(rejection(doc, "memo.doc").detail["detectedType"]).isEqualTo(FileType.OLE2.mediaType)
    }
}
