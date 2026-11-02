package com.memo.files.internal

import com.memo.files.FileType
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SeekableByteChannel
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamReader

/**
 * Reads zip containers far enough to tell what they are (ADR-0029): DOCX from DOCM (Tika keeps that detector in its
 * Apache POI module), and ODF with or without Basic macros. Reads the central directory, never streamed local headers,
 * and checks the entry count from the end-of-central-directory record before parsing anything.
 */
internal object ZipInspector {
    const val MAX_ENTRIES = 2_000
    const val MAX_CONTENT_TYPES_BYTES = 1L shl 20
    const val MAX_RATIO = 100

    private const val EOCD_SIGNATURE = 0x06054b50
    private const val EOCD_MIN = 22
    private const val EOCD_MAX = EOCD_MIN + 0xFFFF
    private const val EOCD_ENTRIES_OFFSET = 10
    private const val ZIP64_MARKER = 0xFFFF
    private const val CONTENT_TYPES = "[Content_Types].xml"

    /** Whether the last bytes of [channel] hold a zip end-of-central-directory record (also used to find polyglots). */
    fun hasZipTrailer(channel: SeekableByteChannel): Boolean = endOfCentralDirectory(channel) != null

    /**
     * The type of the zip in [channel]: an OOXML or ODF type, or [FileType.ZIP]. [tikaType] is Tika's magic-based
     * guess, which already names ODF types from their `mimetype` entry.
     */
    fun classify(
        channel: SeekableByteChannel,
        tikaType: FileType?,
    ): FileType {
        val eocd = endOfCentralDirectory(channel) ?: reject(RejectionReason.MALFORMED, "problem" to "zip-trailer")
        val entries = eocd.getShort(EOCD_ENTRIES_OFFSET).toInt() and ZIP64_MARKER
        if (entries == ZIP64_MARKER || entries > MAX_ENTRIES) {
            reject(RejectionReason.LIMIT_EXCEEDED, "limit" to "zip-entries")
        }
        channel.position(0)
        val zip =
            try {
                ZipFile
                    .builder()
                    .setSeekableByteChannel(channel)
                    .setIgnoreLocalFileHeader(true)
                    .get()
            } catch (e: IOException) {
                reject(RejectionReason.MALFORMED, "problem" to "zip", cause = e)
            }
        val names =
            zip.entries
                .asSequence()
                .map { it.name }
                .toList()
        if (names.size > MAX_ENTRIES) reject(RejectionReason.LIMIT_EXCEEDED, "limit" to "zip-entries")
        val contentTypes = zip.getEntry(CONTENT_TYPES)
        return when {
            contentTypes != null -> {
                if (contentTypes.size > MAX_CONTENT_TYPES_BYTES ||
                    (contentTypes.compressedSize > 0 && contentTypes.size / contentTypes.compressedSize > MAX_RATIO)
                ) {
                    reject(RejectionReason.LIMIT_EXCEEDED, "limit" to "content-types")
                }
                ooxml(contentTypesOf(zip.getInputStream(contentTypes).bounded(MAX_CONTENT_TYPES_BYTES)))
            }

            tikaType in ODF -> {
                // ODF with Basic macros or scripts is refused like macro-enabled OOXML.
                if (names.any { it.startsWith("Basic/") || it.startsWith("Scripts/") }) {
                    reject(RejectionReason.TYPE_NOT_ALLOWED, "detectedType" to tikaType!!.mediaType, "macros" to true)
                }
                tikaType!!
            }

            else -> {
                FileType.ZIP
            }
        }
    }

    /** The OOXML type from the content types of a package; macro-enabled when any part carries macros. */
    fun ooxml(contentTypes: List<String>): FileType {
        val lower = contentTypes.map { it.lowercase() }
        val macros = lower.any { "macroenabled" in it || "vbaproject" in it }
        return when {
            lower.any { "wordprocessingml.document.main+xml" in it || "ms-word.document.macroenabled.main+xml" in it } -> {
                if (macros) FileType.DOCM else FileType.DOCX
            }

            lower.any { "spreadsheetml.sheet.main+xml" in it || "ms-excel.sheet.macroenabled.main+xml" in it } -> {
                if (macros) FileType.XLSM else FileType.XLSX
            }

            lower.any { "presentationml.presentation.main+xml" in it || "ms-powerpoint.presentation.macroenabled.main+xml" in it } -> {
                if (macros) FileType.PPTM else FileType.PPTX
            }

            // A package of another kind (templates, add-ins, unknown): not a type any purpose allows.
            else -> {
                reject(RejectionReason.TYPE_NOT_ALLOWED, "detectedType" to "application/x-tika-ooxml")
            }
        }
    }

    /** `ContentType` attributes of `Default` and `Override` elements; DTDs and external entities are refused. */
    fun contentTypesOf(input: InputStream): List<String> {
        val factory =
            XMLInputFactory.newFactory().apply {
                setProperty(XMLInputFactory.SUPPORT_DTD, false)
                setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
                setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false)
            }
        return try {
            input.use { contentTypes(factory.createXMLStreamReader(it)) }
        } catch (e: XMLStreamException) {
            reject(RejectionReason.MALFORMED, "problem" to "content-types", cause = e)
        } catch (e: IOException) {
            reject(RejectionReason.LIMIT_EXCEEDED, "limit" to "content-types", cause = e)
        }
    }

    private fun contentTypes(reader: XMLStreamReader): List<String> {
        val result = mutableListOf<String>()
        while (reader.hasNext()) {
            val event = reader.next()
            if (event == XMLStreamConstants.DTD) reject(RejectionReason.MALFORMED, "problem" to "content-types-dtd")
            if (event == XMLStreamConstants.START_ELEMENT && (reader.localName == "Default" || reader.localName == "Override")) {
                reader.getAttributeValue(null, "ContentType")?.let(result::add)
            }
        }
        return result
    }

    private fun endOfCentralDirectory(channel: SeekableByteChannel): ByteBuffer? {
        val size = channel.size()
        if (size < EOCD_MIN) return null
        val tailLength = minOf(size, EOCD_MAX.toLong()).toInt()
        val tail = ByteBuffer.allocate(tailLength).order(ByteOrder.LITTLE_ENDIAN)
        channel.position(size - tailLength)
        while (tail.hasRemaining() && channel.read(tail) >= 0) {
            // keep reading until the tail is full
        }
        val at = (tailLength - EOCD_MIN downTo 0).firstOrNull { tail.getInt(it) == EOCD_SIGNATURE }
        return at?.let { tail.slice(it, tailLength - it).order(ByteOrder.LITTLE_ENDIAN) }
    }

    private val ODF = setOf(FileType.ODT, FileType.ODS, FileType.ODP)

    /** Fails once more than [max] bytes come out: declared entry sizes can lie. */
    private fun InputStream.bounded(max: Long): InputStream {
        val source = this
        return object : InputStream() {
            private var count = 0L

            override fun read(): Int = source.read().also { if (it >= 0) check(1) }

            override fun read(
                b: ByteArray,
                off: Int,
                len: Int,
            ): Int = source.read(b, off, len).also { if (it > 0) check(it.toLong()) }

            private fun check(n: Long) {
                count += n
                if (count > max) throw IOException("more than $max bytes")
            }

            override fun close() = source.close()
        }
    }
}
