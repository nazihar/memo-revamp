package com.memo.testsupport

import org.apache.poi.hssf.record.crypto.Biff8EncryptionKey
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO

/**
 * Legacy-format samples built at test time (ADR-0055, E06 T2): nothing third-party is committed. Excel files are written
 * by POI's HSSF; the other OLE2 documents are compound files with the entries the Tika server types them by (a Word
 * document stream, an Outlook property stream), which is all the stand-in detector and clamd look at.
 */
object LegacySamples {
    /** A macro-free Excel 97 workbook; with [password], encrypted as Excel does (clamd: `Heuristics.Encrypted.OLE2`). */
    fun xls(password: String? = null): ByteArray =
        HSSFWorkbook().use { book ->
            book
                .createSheet("Memos")
                .createRow(0)
                .createCell(0)
                .setCellValue("LEG/2019/0001")
            Biff8EncryptionKey.setCurrentUserPassword(password)
            try {
                ByteArrayOutputStream().also(book::write).toByteArray()
            } finally {
                Biff8EncryptionKey.setCurrentUserPassword(null)
            }
        }

    /** An OLE2 compound file with a Word document stream: a `.doc` to the Tika server. */
    fun doc(): ByteArray = compound("WordDocument" to "Legacy memo: rent schedule".toByteArray())

    /** An OLE2 compound file with a VBA project beside the Word document stream: a macro `.doc` to clamd. */
    fun docWithMacros(): ByteArray =
        POIFSFileSystem().use { fs ->
            fs.createDocument("Legacy memo".toByteArray().inputStream(), "WordDocument")
            val vba = fs.root.createDirectory("Macros").createDirectory("VBA")
            vba.createDocument("_VBA_PROJECT", byteArrayOf(0xCC.toByte(), 0x61, 0xFF.toByte(), 0xFF.toByte(), 0, 0, 0).inputStream())
            vba.createDocument("dir", ByteArray(DIR_BYTES).inputStream())
            vba.createDocument("Module1", "Attribute VB_Name = \"Module1\"\r\nSub AutoOpen()\r\nEnd Sub\r\n".toByteArray().inputStream())
            ByteArrayOutputStream().also(fs::writeFilesystem).toByteArray()
        }

    /** An OLE2 compound file with an Outlook property stream: an Outlook `.msg` to the Tika server. */
    fun msg(): ByteArray = compound("__substg1.0_0037001F" to "Board minutes".toByteArray(Charsets.UTF_16LE))

    val rtf: ByteArray = "{\\rtf1\\ansi\\deff0 {\\fonttbl {\\f0 Times;}} Legacy memo: rent schedule.\\par }".toByteArray()

    /** A two-page TIFF scan, as ImageIO's TIFF plug-in writes it. */
    fun tiff(): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("tiff").next()
        ImageIO.createImageOutputStream(out).use { stream ->
            writer.output = stream
            writer.prepareWriteSequence(null)
            repeat(2) { writer.writeToSequence(IIOImage(BufferedImage(SIDE, SIDE, BufferedImage.TYPE_BYTE_GRAY), null, null), null) }
            writer.endWriteSequence()
        }
        writer.dispose()
        return out.toByteArray()
    }

    private fun compound(vararg entries: Pair<String, ByteArray>): ByteArray =
        POIFSFileSystem().use { fs ->
            entries.forEach { (name, bytes) -> fs.createDocument(bytes.inputStream(), name) }
            ByteArrayOutputStream().also(fs::writeFilesystem).toByteArray()
        }

    private const val SIDE = 16
    private const val DIR_BYTES = 64
}
