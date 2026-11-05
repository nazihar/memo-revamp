package com.memo.files.internal

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import com.memo.files.FileType
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import java.time.Duration

/**
 * Fuzz targets on the upload path (TESTING_STRATEGY §3.6, ADR-0029). In the PR stage each runs its stored inputs as a
 * regression test; the nightly stage fuzzes each for a few minutes (`JAZZER_FUZZ=1`). A target fails on any exception
 * other than a deliberate refusal, and on any broken invariant.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UploadPathFuzzTest {
    private val inspector = ContentInspector(Duration.ofSeconds(5))
    private val everything = InspectionRules(FileType.entries.toSet() - FileType.NEVER_ALLOWED, maxPdfPages = 50)

    @AfterAll
    fun stop() = inspector.destroy()

    @FuzzTest(maxDuration = "5m")
    fun clamdReply(data: FuzzedDataProvider) {
        val reply = data.consumeRemainingAsString()
        when (val verdict = ClamdReply.parse(reply)) {
            is ScanVerdict.Clean -> check(reply == "stream: OK")
            is ScanVerdict.Infected -> check(reply == "stream: ${verdict.signature} FOUND" && verdict.signature.none { it.isWhitespace() })
            is ScanVerdict.Failed -> check(verdict.reason.all { it.code in PRINTABLE })
        }
    }

    @FuzzTest(maxDuration = "5m")
    fun byteRanges(data: FuzzedDataProvider) {
        val size = data.consumeLong(0, 1L shl 40)
        val parsed = ByteRanges.parse(data.consumeRemainingAsString(), size)
        if (parsed is RangeRequest.Partial) {
            check(parsed.range.first in 0..<size && parsed.range.last in parsed.range.first..<size)
        }
    }

    @FuzzTest(maxDuration = "5m")
    fun contentTypes(data: FuzzedDataProvider) {
        refusalsOnly { ZipInspector.contentTypesOf(data.consumeRemainingAsBytes().inputStream()) }
    }

    @FuzzTest(maxDuration = "5m")
    fun inspection(data: FuzzedDataProvider) {
        val name = data.consumeString(MAX_NAME)
        val bytes = data.consumeRemainingAsBytes()
        refusalsOnly {
            val inspected = inspector.inspect({ SeekableInMemoryByteChannel(bytes) }, name, everything)
            check(inspected.type !in FileType.NEVER_ALLOWED)
        }
    }

    @FuzzTest(maxDuration = "5m")
    fun fileNames(data: FuzzedDataProvider) {
        val name = FileNames.sanitise(data.consumeRemainingAsString(), "bin")
        check(name.isNotBlank() && name.length <= FileNames.MAX_LENGTH && '/' !in name && '\\' !in name)
    }

    /** Runs [block]; a refusal of the upload is an expected outcome, anything else is a finding. */
    private fun refusalsOnly(block: () -> Unit) {
        try {
            block()
        } catch (_: UploadRejected) {
            // Refusing hostile input is the point.
        }
    }

    private companion object {
        val PRINTABLE = 0x20..0x7E
        const val MAX_NAME = 300
    }
}
