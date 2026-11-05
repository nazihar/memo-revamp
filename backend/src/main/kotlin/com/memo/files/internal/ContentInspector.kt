package com.memo.files.internal

import com.memo.files.FileType
import org.apache.tika.io.TikaInputStream
import org.apache.tika.metadata.Metadata
import org.apache.tika.mime.MimeTypes
import org.apache.tika.parser.ParseContext
import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Component
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/** What inspection found: the type from content and, for PDFs, the page count. */
internal data class Inspected(
    val type: FileType,
    val pdfPages: Int?,
)

/** What a purpose allows, for one inspection. */
internal data class InspectionRules(
    val allowed: Set<FileType>,
    val maxPdfPages: Int,
)

/**
 * Decides what an upload is from its content alone (ADR-0029): Tika's magic on the first bytes (no Tika parsers, and no
 * zip-container detector, which may spool the stream to a temporary file), our zip reader for OOXML and ODF, PDFBox
 * for PDFs. Then the type must be allowed, agree with the name's extension, and not hide a zip after it (polyglot).
 * Every step reads through `open`, a fresh decrypting channel over the ciphertext spool; the whole inspection runs on a
 * small bounded pool with a [timeout].
 */
@Component
internal class ContentInspector(
    private val timeout: Duration = DEFAULT_TIMEOUT,
) : DisposableBean {
    private val mimeTypes: MimeTypes = MimeTypes.getDefaultMimeTypes()
    private val pool =
        Executors.newFixedThreadPool(
            THREADS,
            object : ThreadFactory {
                private val count = AtomicInteger()

                override fun newThread(r: Runnable) = Thread(r, "files-inspect-${count.incrementAndGet()}").apply { isDaemon = true }
            },
        )

    fun inspect(
        open: () -> SeekableByteChannel,
        declaredName: String?,
        rules: InspectionRules,
    ): Inspected {
        val task = pool.submit<Inspected> { inspectNow(open, declaredName, rules) }
        return try {
            task.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            task.cancel(true)
            reject(RejectionReason.LIMIT_EXCEEDED, "limit" to "inspection-time", cause = e)
        } catch (e: ExecutionException) {
            val cause = e.cause
            if (cause is UploadRejected) throw UploadRejected(cause.reason, cause.detail, e)
            if (cause is OutOfMemoryError ||
                cause is StackOverflowError
            ) {
                reject(RejectionReason.LIMIT_EXCEEDED, "limit" to "inspection", cause = e)
            }
            reject(RejectionReason.MALFORMED, "problem" to (cause?.javaClass?.simpleName ?: "inspection"), cause = e)
        }
    }

    private fun inspectNow(
        open: () -> SeekableByteChannel,
        declaredName: String?,
        rules: InspectionRules,
    ): Inspected {
        val mediaType = open().use { detect(it) }
        val type =
            if (mediaType in ZIP_MEDIA_TYPES) {
                open().use { ZipInspector.classify(it, FileType.ofMediaType(mediaType)) }
            } else {
                FileType.ofMediaType(mediaType)
            }
        if (type == null || type in FileType.NEVER_ALLOWED || type !in rules.allowed) {
            reject(RejectionReason.TYPE_NOT_ALLOWED, "detectedType" to (type?.mediaType ?: mediaType))
        }
        val extension = declaredName?.let(FileNames::extension)
        if (extension != null && extension !in type.extensions) {
            reject(RejectionReason.TYPE_MISMATCH, "detectedType" to type.mediaType, "extension" to extension)
        }
        if (type !in ZIP_BASED && open().use(ZipInspector::hasZipTrailer)) {
            reject(RejectionReason.MALFORMED, "problem" to "polyglot", "detectedType" to type.mediaType)
        }
        val pages = if (type == FileType.PDF) open().use { PdfInspector.pages(it, rules.maxPdfPages) } else null
        return Inspected(type, pages)
    }

    /** Tika's magic detection on the first [HEAD] bytes: a media type, never taken from the name. */
    private fun detect(channel: SeekableByteChannel): String {
        val head = ByteBuffer.allocate(minOf(HEAD.toLong(), channel.size()).toInt())
        while (head.hasRemaining()) {
            if (channel.read(head) < 0) break
        }
        return TikaInputStream
            .get(head.array().copyOf(head.position()))
            .use { mimeTypes.detect(it, Metadata(), ParseContext()) }
            .baseType
            .toString()
    }

    override fun destroy() {
        pool.shutdownNow()
    }

    companion object {
        private val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(20)
        private const val THREADS = 4
        private const val HEAD = 64 * 1024

        /** Media types Tika gives zip containers; our zip reader decides which type they are. */
        private val ZIP_MEDIA_TYPES =
            setOf(
                "application/zip",
                "application/x-tika-ooxml",
                FileType.ODT.mediaType,
                FileType.ODS.mediaType,
                FileType.ODP.mediaType,
            )

        private val ZIP_BASED =
            setOf(
                FileType.ZIP,
                FileType.DOCX,
                FileType.XLSX,
                FileType.PPTX,
                FileType.ODT,
                FileType.ODS,
                FileType.ODP,
                FileType.DOCM,
                FileType.XLSM,
                FileType.PPTM,
            )
    }
}
