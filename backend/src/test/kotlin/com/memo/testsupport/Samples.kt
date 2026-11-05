package com.memo.testsupport

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

/**
 * Sample files built at test time (TESTING_STRATEGY §3.6): nothing large or dangerous is committed, and every sample is
 * exactly what its name says. Used by the inspection, upload and hostile-file corpus tests.
 */
object Samples {
    fun pdf(
        pages: Int = 1,
        userPassword: String? = null,
    ): ByteArray =
        PDDocument().use { doc ->
            repeat(pages) { doc.addPage(PDPage()) }
            if (userPassword != null) {
                doc.protect(
                    StandardProtectionPolicy("owner-$userPassword", userPassword, AccessPermission()).apply {
                        encryptionKeyLength =
                            256
                    },
                )
            }
            ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }

    /** Starts like a PDF, then nothing a PDF parser can use. */
    val malformedPdf: ByteArray = "%PDF-1.7\n1 0 obj << /Type /Catalog /Pages 9 0 R >>\nthis is not a pdf\n%%EOF\n".toByteArray()

    fun png(): ByteArray = image("png")

    fun jpeg(): ByteArray = image("jpeg")

    /** A 1×1 lossless WebP. */
    val webp: ByteArray = Base64.getDecoder().decode("UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA==")

    val text: ByteArray = "Quarterly figures, draft 2\nNet income: 1,234\n".toByteArray()

    val html: ByteArray = "<!DOCTYPE html><html><body><script>alert(document.cookie)</script></body></html>".toByteArray()

    /** The OLE2 compound file signature followed by padding: what legacy .doc/.xls/.ppt look like to magic detection. */
    val ole2: ByteArray =
        byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()) +
            ByteArray(4096)

    fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** A minimal OOXML package whose main part has [mainContentType]; [vba] adds a VBA project part. */
    fun ooxml(
        mainPart: String,
        mainContentType: String,
        vba: Boolean = false,
    ): ByteArray {
        val vbaType = if (vba) """<Default Extension="bin" ContentType="application/vnd.ms-office.vbaProject"/>""" else ""
        val contentTypes =
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
              <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
              <Default Extension="xml" ContentType="application/xml"/>$vbaType
              <Override PartName="/$mainPart" ContentType="$mainContentType"/>
            </Types>"""
        val parts = mutableListOf("[Content_Types].xml" to contentTypes.toByteArray(), mainPart to "<doc/>".toByteArray())
        if (vba) parts += "word/vbaProject.bin" to ByteArray(512)
        return zip(*parts.toTypedArray())
    }

    fun docx() = ooxml("word/document.xml", "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml")

    fun docm() = ooxml("word/document.xml", "application/vnd.ms-word.document.macroEnabled.main+xml", vba = true)

    fun xlsx() = ooxml("xl/workbook.xml", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml")

    fun pptx() = ooxml("ppt/presentation.xml", "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml")

    /** A DOCX whose content types are fine but which still carries a VBA project (a DOCM in disguise). */
    fun docxWithVba() =
        ooxml("word/document.xml", "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml", vba = true)

    /** An ODF package: the `mimetype` entry first and stored, as the format requires. */
    fun odf(
        mimeType: String = "application/vnd.oasis.opendocument.text",
        macros: Boolean = false,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            val mime = mimeType.toByteArray()
            zip.putNextEntry(
                ZipEntry("mimetype").apply {
                    method = ZipEntry.STORED
                    size = mime.size.toLong()
                    crc = CRC32().apply { update(mime) }.value
                },
            )
            zip.write(mime)
            zip.closeEntry()
            listOf("content.xml" to "<office:document-content/>", "META-INF/manifest.xml" to "<manifest/>")
                .plus(if (macros) listOf("Basic/Standard/Module1.xml" to "<script/>") else emptyList())
                .forEach { (name, body) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(body.toByteArray())
                    zip.closeEntry()
                }
        }
        return out.toByteArray()
    }

    /** A valid PDF with a zip appended: opens as a PDF and as an archive. */
    fun pdfZipPolyglot(): ByteArray = pdf() + zip("payload.txt" to "hidden".toByteArray())

    /** A zip in a zip in a zip ... [depth] levels deep, the innermost holding a text file. */
    fun nestedZip(depth: Int): ByteArray = (1..depth).fold(text) { inner, level -> zip("level-$level.zip" to inner) }

    /** [megabytes] MiB of zeros deflated into a few hundred KiB: a decompression bomb. */
    fun zipBomb(megabytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.setLevel(9)
            zip.putNextEntry(ZipEntry("zeros.bin"))
            val chunk = ByteArray(1 shl 20)
            repeat(megabytes) { zip.write(chunk) }
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    /** A zip whose entry is flagged as encrypted (general purpose bit 0), as a password-protected archive is. */
    fun encryptedZip(): ByteArray {
        val bytes = zip("secret.txt" to text)
        flagEncrypted(bytes, byteArrayOf(0x50, 0x4B, 0x03, 0x04), LOCAL_FLAGS_OFFSET)
        flagEncrypted(bytes, byteArrayOf(0x50, 0x4B, 0x01, 0x02), CENTRAL_FLAGS_OFFSET)
        return bytes
    }

    private fun flagEncrypted(
        bytes: ByteArray,
        signature: ByteArray,
        flagsOffset: Int,
    ) {
        val at = (0..bytes.size - signature.size).first { i -> signature.indices.all { bytes[i + it] == signature[it] } }
        bytes[at + flagsOffset] = (bytes[at + flagsOffset].toInt() or 1).toByte()
    }

    private const val LOCAL_FLAGS_OFFSET = 6
    private const val CENTRAL_FLAGS_OFFSET = 8

    private fun image(format: String): ByteArray {
        val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
        return ByteArrayOutputStream().also { ImageIO.write(image, format, it) }.toByteArray()
    }
}
