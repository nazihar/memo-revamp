package com.memo.files.internal

import org.apache.pdfbox.Loader
import org.apache.pdfbox.io.MemoryUsageSetting
import org.apache.pdfbox.io.RandomAccessRead
import org.apache.pdfbox.io.RandomAccessReadView
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

/**
 * Opens a PDF with hard limits (ADR-0029): read through the decrypting channel (no plaintext copy), memory-only stream
 * cache capped at [MAX_MEMORY] bytes (no scratch files), refused when encrypted, malformed or longer than the page limit.
 */
internal object PdfInspector {
    const val MAX_MEMORY = 64L shl 20

    /** The page count; throws [UploadRejected] for anything that cannot be accepted. */
    fun pages(
        channel: SeekableByteChannel,
        maxPages: Int,
    ): Int {
        val document =
            try {
                Loader.loadPDF(
                    ChannelRandomAccessRead(channel),
                    "",
                    null,
                    null,
                    MemoryUsageSetting.setupMainMemoryOnly(MAX_MEMORY).streamCache,
                )
            } catch (e: InvalidPasswordException) {
                reject(RejectionReason.ENCRYPTED, "detectedType" to "application/pdf", cause = e)
            } catch (e: IOException) {
                reject(RejectionReason.MALFORMED, "problem" to "pdf", cause = e)
            }
        document.use {
            if (it.isEncrypted) reject(RejectionReason.ENCRYPTED, "detectedType" to "application/pdf")
            val pages = it.numberOfPages
            if (pages < 1) reject(RejectionReason.MALFORMED, "problem" to "pdf-no-pages")
            if (pages > maxPages) reject(RejectionReason.LIMIT_EXCEEDED, "limit" to "pdf-pages", "pages" to pages)
            return pages
        }
    }
}

/** PDFBox's random access over a [SeekableByteChannel], with a small read buffer. */
internal class ChannelRandomAccessRead(
    private val channel: SeekableByteChannel,
) : RandomAccessRead {
    private val buffer = ByteBuffer.allocate(BUFFER)
    private var bufferStart = 0L
    private var position = 0L
    private var closed = false

    init {
        buffer.limit(0)
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and BYTE
    }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        val inFile = position < length()
        if (len > 0 && inFile && outsideBuffer()) fill()
        val count = if (inFile) minOf(len.toLong(), bufferStart + buffer.limit() - position).toInt() else 0
        if (count > 0) {
            buffer.get((position - bufferStart).toInt(), b, off, count)
            position += count
        }
        return when {
            len == 0 -> 0
            count <= 0 -> -1
            else -> count
        }
    }

    private fun outsideBuffer() = position < bufferStart || position >= bufferStart + buffer.limit()

    private fun fill() {
        bufferStart = position
        buffer.clear()
        channel.position(position)
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) break
        }
        buffer.flip()
    }

    override fun getPosition(): Long = position

    override fun seek(position: Long) {
        if (position < 0) throw IOException("negative position")
        this.position = minOf(position, length())
    }

    override fun length(): Long = channel.size()

    override fun isClosed(): Boolean = closed

    override fun isEOF(): Boolean = position >= length()

    override fun createView(
        startPosition: Long,
        streamLength: Long,
    ): RandomAccessReadView = RandomAccessReadView(this, startPosition, streamLength)

    override fun close() {
        closed = true
        channel.close()
    }

    private companion object {
        const val BUFFER = 64 * 1024
        const val BYTE = 0xFF
    }
}
