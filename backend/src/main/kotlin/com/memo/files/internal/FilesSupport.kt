package com.memo.files.internal

import com.memo.files.FilePurpose
import com.memo.files.FileType
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.Semaphore

/** Installation settings of the files service (design §10). */
@ConfigurationProperties("memo.files")
data class FilesProperties(
    /** Platform ceiling for any upload; clamd's `StreamMaxLength` is set above it (deploy/clamav/clamd.env). */
    val maxUploadBytes: Long = DEFAULT_MAX_UPLOAD_BYTES,
    /** Ciphertext spools of uploads in progress; empty: `<java.io.tmpdir>/memo-spool`. */
    val spoolDir: String = "",
    /** Uploads processed at once on this node; more get 503 with `Retry-After`. */
    val concurrentUploads: Int = DEFAULT_CONCURRENT_UPLOADS,
) {
    companion object {
        const val DEFAULT_MAX_UPLOAD_BYTES = 100L shl 20
        const val DEFAULT_CONCURRENT_UPLOADS = 8
    }
}

/** The registered purposes by key; a duplicate key or an invalid declaration stops the application. */
@Component
internal class Purposes(
    provider: ObjectProvider<FilePurpose>,
) {
    private val purposes = provider.orderedStream().toList()
    private val byKey: Map<String, FilePurpose> =
        purposes.associateBy { it.key }.also { map ->
            check(
                map.size == purposes.size,
            ) { "duplicate file purpose keys: ${purposes.groupBy { it.key }.filterValues { it.size > 1 }.keys}" }
            map.values.forEach { purpose ->
                check(KEY.matches(purpose.key)) { "invalid file purpose key '${purpose.key}'" }
                check(purpose.allowedTypes.isNotEmpty() && purpose.allowedTypes.none { it in FileType.NEVER_ALLOWED }) {
                    "file purpose '${purpose.key}' allows no type, or a type that is never allowed"
                }
                check(purpose.maxBytes > 0) { "file purpose '${purpose.key}' needs a positive maxBytes" }
            }
        }

    operator fun get(key: String): FilePurpose? = byKey[key]

    private companion object {
        val KEY = Regex("^[a-z][a-z0-9.-]{0,99}$")
    }
}

/**
 * The private directory of upload spools (ADR-0029). Spools hold ciphertext only; still, the directory is owner-only,
 * files are removed when an upload ends, and whatever a crash left behind is removed at start.
 */
@Component
internal class Spool(
    properties: FilesProperties,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)
    private val posix = "posix" in FileSystems.getDefault().supportedFileAttributeViews()

    val directory: Path =
        (if (properties.spoolDir.isBlank()) Path.of(System.getProperty("java.io.tmpdir"), "memo-spool") else Path.of(properties.spoolDir))
            .toAbsolutePath()

    /** A new, empty spool file readable only by this process's user. */
    fun create(): Path {
        ensureDirectory()
        return if (posix) {
            Files.createTempFile(
                directory,
                "upload-",
                ".spool",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
            )
        } else {
            Files.createTempFile(directory, "upload-", ".spool")
        }
    }

    fun delete(file: Path) {
        runCatching { Files.deleteIfExists(file) }.onFailure { log.warn("Could not delete upload spool {}", file.fileName, it) }
    }

    /** Removes leftovers of uploads interrupted by a crash or a kill. */
    override fun run(args: ApplicationArguments) {
        if (!Files.isDirectory(directory)) return
        Files.list(directory).use { files -> files.filter { it.fileName.toString().endsWith(".spool") }.forEach(::delete) }
    }

    private fun ensureDirectory() {
        if (Files.isDirectory(directory)) return
        if (posix) {
            Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        } else {
            Files.createDirectories(directory)
        }
    }
}

/** Uploads in progress on this node; beyond the limit an upload is refused at once rather than queued. */
@Component
internal class UploadSlots(
    properties: FilesProperties,
) {
    private val slots = Semaphore(properties.concurrentUploads)

    fun <T> withSlot(block: () -> T): T {
        if (!slots.tryAcquire()) throw UploadsBusyException()
        try {
            return block()
        } finally {
            slots.release()
        }
    }
}

/** Every upload slot on this node is taken: 503 with `Retry-After`. */
internal class UploadsBusyException : RuntimeException("upload slots exhausted")

/** No purpose with this key is registered: 400. */
internal class UnknownPurposeException : RuntimeException("unknown file purpose")
