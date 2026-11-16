package com.memo.demo

import org.apache.pdfbox.cos.COSArray
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.cos.COSString
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO

/**
 * Files in a legacy archive's formats for [SyntheticLegacy], written at run time and the same bytes every time: ZIP
 * entries carry a fixed date, PDFs a fixed file identifier, and the TIFF scan is drawn without fonts.
 */
internal object LegacyFiles {
    fun docx(
        title: String,
        paragraphs: List<String>,
    ): ByteArray = fixedTimes(DemoDocx.of(title, paragraphs))

    /** A one-page PDF with a fixed file identifier (PDFBox derives one from the time otherwise). */
    fun pdf(
        title: String,
        lines: List<String>,
    ): ByteArray =
        PDDocument().use { document ->
            val page = PDPage(PDRectangle.A4)
            document.addPage(page)
            PDPageContentStream(document, page).use { text ->
                text.beginText()
                text.newLineAtOffset(MARGIN, page.mediaBox.height - MARGIN)
                text.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), TITLE_SIZE)
                text.showText(title)
                text.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), BODY_SIZE)
                lines.forEach { line ->
                    text.newLineAtOffset(0f, -LEADING)
                    text.showText(line)
                }
                text.endText()
            }
            val id = COSString(sha256(title).copyOf(ID_BYTES))
            document.document.trailer.setItem(COSName.ID, COSArray(listOf(id, id)))
            ByteArrayOutputStream().also { document.save(it) }.toByteArray()
        }

    /** A Word 97 compound file holding a Word document stream: typed as Word by the Tika server (ADR-0055). */
    fun doc(title: String): ByteArray = compound("WordDocument" to "Legacy memo: $title".toByteArray())

    /** An Excel 97 workbook, as HSSF writes it: the rent schedule of the lease renewal. */
    fun xls(): ByteArray =
        HSSFWorkbook().use { book ->
            val sheet = book.createSheet("Rent schedule")
            listOf(listOf("Year", "Monthly rent (BDT)"), listOf("2019", "185000"), listOf("2020", "194250"), listOf("2021", "203960"))
                .forEachIndexed { r, values ->
                    val row = sheet.createRow(r)
                    values.forEachIndexed { c, value -> row.createCell(c).setCellValue(value) }
                }
            ByteArrayOutputStream().also(book::write).toByteArray()
        }

    /** A grey multi-page scan: ruled lines standing for text, no font (so the pixels never depend on the platform). */
    fun tiff(pages: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("tiff").next()
        ImageIO.createImageOutputStream(out).use { stream ->
            writer.output = stream
            writer.prepareWriteSequence(null)
            repeat(pages) { page -> writer.writeToSequence(IIOImage(scanPage(page), null, null), null) }
            writer.endWriteSequence()
        }
        writer.dispose()
        return out.toByteArray()
    }

    fun scanPage(page: Int): BufferedImage {
        val image = BufferedImage(SCAN_WIDTH, SCAN_HEIGHT, BufferedImage.TYPE_BYTE_GRAY)
        val g = image.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, SCAN_WIDTH, SCAN_HEIGHT)
        g.color = Color.DARK_GRAY
        for (line in 0 until SCAN_LINES - page * 2) {
            val y = SCAN_TOP + line * SCAN_LEADING
            g.fillRect(SCAN_MARGIN, y, SCAN_WIDTH - 2 * SCAN_MARGIN - (line % RAGGED) * SCAN_MARGIN, 2)
        }
        g.dispose()
        return image
    }

    fun compound(vararg entries: Pair<String, ByteArray>): ByteArray =
        POIFSFileSystem().use { fs ->
            entries.forEach { (name, bytes) -> fs.createDocument(bytes.inputStream(), name) }
            ByteArrayOutputStream().also(fs::writeFilesystem).toByteArray()
        }

    fun zip(entries: List<Pair<String, String>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name).apply { timeLocal = EXPORTED })
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** The same ZIP package with every entry dated [EXPORTED], so writing it again gives the same bytes. */
    fun fixedTimes(packaged: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        ZipInputStream(ByteArrayInputStream(packaged)).use { input ->
            ZipOutputStream(out).use { output ->
                generateSequence { input.nextEntry }.forEach { entry ->
                    output.putNextEntry(ZipEntry(entry.name).apply { timeLocal = EXPORTED })
                    output.write(input.readAllBytes())
                    output.closeEntry()
                }
            }
        }
        return out.toByteArray()
    }

    fun sha256(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())

    /** The export's date, as each ZIP entry's (local, so the bytes do not depend on the time zone). */
    private val EXPORTED: LocalDateTime = LocalDateTime.parse("2021-06-30T00:00")
    private const val SCAN_WIDTH = 620
    private const val SCAN_HEIGHT = 877
    private const val SCAN_MARGIN = 60
    private const val SCAN_TOP = 90
    private const val SCAN_LEADING = 22
    private const val SCAN_LINES = 30

    /** Every third line ends shorter, as paragraphs do. */
    private const val RAGGED = 3
    private const val ID_BYTES = 16
    private const val MARGIN = 72f
    private const val TITLE_SIZE = 16f
    private const val BODY_SIZE = 11f
    private const val LEADING = 16f
}
