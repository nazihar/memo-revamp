package com.memo.memo.internal.rendition

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.verapdf.gf.foundry.VeraGreenfieldFoundryProvider
import org.verapdf.pdfa.Foundries
import org.verapdf.pdfa.flavours.PDFAFlavour
import org.verapdf.pdfa.results.TestAssertion
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * What a validator said about a file (ADR-0053): the profile it was checked against, whether it complies, the failed
 * rules (clause and test number, with how often each failed), and the validator's name and version. [error] is set when
 * no verdict could be reached (time limit, unreadable file); such a file counts as not compliant.
 */
data class PdfaValidation(
    val profile: String,
    val compliant: Boolean,
    val failedRules: Map<String, Int>,
    val validator: String,
    val error: String? = null,
)

/** Checks a file against a PDF/A profile; never throws for a bad file. */
interface PdfaValidator {
    fun validate(
        file: Path,
        profile: String = PDFA_2U,
    ): PdfaValidation

    companion object {
        /** PDF/A-2, level U (ADR-0053). */
        const val PDFA_2U = "2u"
    }
}

/**
 * veraPDF's Greenfield validator (1.30.x, used under its MPL-2.0 option), on a small pool of its own with a time limit,
 * so a large or crafted file cannot hold the job (threat model D1, D3). The flavour is always given, never detected, so
 * a file that claims another level fails.
 */
@Component
class VeraPdfValidator(
    @param:Value("\${memo.rendition.validation-timeout:60s}") private val timeout: Duration,
) : PdfaValidator,
    DisposableBean {
    private val log = LoggerFactory.getLogger(javaClass)
    private val pool =
        ThreadPoolExecutor(THREADS, THREADS, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(QUEUE)) { task ->
            Thread(task, "verapdf").apply { isDaemon = true }
        }
    private val name = "veraPDF " + (VeraGreenfieldFoundryProvider::class.java.`package`?.implementationVersion ?: "1.30")

    init {
        VeraGreenfieldFoundryProvider.initialise()
    }

    override fun validate(
        file: Path,
        profile: String,
    ): PdfaValidation {
        val task =
            try {
                pool.submit<PdfaValidation> { run(file, profile) }
            } catch (_: RejectedExecutionException) {
                return failed(profile, "BUSY")
            }
        return try {
            task.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            task.cancel(true)
            failed(profile, "TIMEOUT")
        } catch (e: ExecutionException) {
            log.warn("PDF/A validation failed: {}", e.cause?.javaClass?.simpleName)
            failed(profile, "UNREADABLE")
        }
    }

    private fun run(
        file: Path,
        profile: String,
    ): PdfaValidation {
        val flavour = PDFAFlavour.fromString(profile)
        Foundries.defaultInstance().use { foundry ->
            Files.newInputStream(file).use { input ->
                foundry.createParser(input, flavour).use { parser ->
                    val result = foundry.createValidator(flavour, false).validate(parser)
                    val failed =
                        result.testAssertions
                            .filter { it.status == TestAssertion.Status.FAILED }
                            .groupingBy { "${it.ruleId.clause}-${it.ruleId.testNumber}" }
                            .eachCount()
                    return PdfaValidation(profile, result.isCompliant, failed.toSortedMap(), name)
                }
            }
        }
    }

    private fun failed(
        profile: String,
        error: String,
    ) = PdfaValidation(profile, compliant = false, failedRules = emptyMap(), validator = name, error = error)

    override fun destroy() {
        pool.shutdownNow()
    }

    private companion object {
        const val THREADS = 2
        const val QUEUE = 4
    }
}
