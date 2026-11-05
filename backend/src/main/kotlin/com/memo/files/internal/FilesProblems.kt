package com.memo.files.internal

import com.memo.common.storage.StorageUnavailableException
import com.memo.common.web.ProblemTypes
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import software.amazon.awssdk.core.exception.SdkException

/**
 * Problems of the file endpoints (ADR-0029). A refused upload is 422 `file-rejected` with a stable `reason` that the web
 * client translates; a scanner, storage or capacity problem is 503 with `Retry-After`, never a silent acceptance.
 */
@RestControllerAdvice(basePackageClasses = [FilesProblems::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
internal class FilesProblems {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(UploadRejected::class)
    fun rejected(ex: UploadRejected): ResponseEntity<ProblemDetail> {
        val unavailable = ex.reason == RejectionReason.SCAN_UNAVAILABLE
        val status = if (unavailable) HttpStatus.SERVICE_UNAVAILABLE else HttpStatus.UNPROCESSABLE_CONTENT
        val problem =
            ProblemTypes.of(status, ProblemTypes.FILE_REJECTED, MESSAGES.getValue(ex.reason)).apply {
                setProperty("reason", ex.reason.name)
                ex.detail["maxBytes"]?.let { setProperty("maxBytes", it) }
                ex.detail["detectedType"]?.let { setProperty("detectedType", it) }
            }
        return ResponseEntity
            .status(status)
            .apply { if (unavailable) header(HttpHeaders.RETRY_AFTER, RETRY_SECONDS) }
            .body(problem)
    }

    @ExceptionHandler(UploadsBusyException::class)
    fun busy(): ResponseEntity<ProblemDetail> = unavailable("Too many uploads are in progress. Try again shortly.")

    @ExceptionHandler(StorageUnavailableException::class, SdkException::class)
    fun storage(ex: RuntimeException): ResponseEntity<ProblemDetail> {
        log.warn("Object storage unavailable: {}", ex.javaClass.simpleName)
        return unavailable("File storage is not available. Try again shortly.")
    }

    @ExceptionHandler(UnknownPurposeException::class)
    fun unknownPurpose(): ResponseEntity<ProblemDetail> =
        ResponseEntity.badRequest().body(
            ProblemTypes.of(HttpStatus.BAD_REQUEST, ProblemTypes.VALIDATION, "Unknown file purpose.").apply {
                setProperty("reason", "UNKNOWN_PURPOSE")
            },
        )

    @ExceptionHandler(UploadIncompleteException::class)
    fun incomplete(): ResponseEntity<ProblemDetail> =
        ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The upload did not complete."))

    @ExceptionHandler(FileNotFoundException::class)
    fun notFound(): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "File not found"))

    private fun unavailable(message: String): ResponseEntity<ProblemDetail> =
        ResponseEntity
            .status(HttpStatus.SERVICE_UNAVAILABLE)
            .header(HttpHeaders.RETRY_AFTER, RETRY_SECONDS)
            .body(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, message))

    private companion object {
        const val RETRY_SECONDS = "5"

        /** English fallbacks; clients show their own text per `reason`. */
        val MESSAGES =
            mapOf(
                RejectionReason.TOO_LARGE to "The file is too large.",
                RejectionReason.MALWARE to "The file was blocked by the malware scanner.",
                RejectionReason.TYPE_NOT_ALLOWED to "This type of file is not allowed here.",
                RejectionReason.TYPE_MISMATCH to "The file's name does not match its content.",
                RejectionReason.MALFORMED to "The file is damaged or not what it claims to be.",
                RejectionReason.ENCRYPTED to "Password-protected files cannot be checked and are not accepted.",
                RejectionReason.LIMIT_EXCEEDED to "The file is too complex to be checked safely.",
                RejectionReason.SCAN_UNAVAILABLE to "Files cannot be checked right now. Try again shortly.",
            )
    }
}
