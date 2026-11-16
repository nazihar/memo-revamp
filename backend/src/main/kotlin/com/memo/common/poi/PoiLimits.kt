package com.memo.common.poi

import org.apache.poi.openxml4j.util.ZipInputStreamZipEntrySource
import org.apache.poi.openxml4j.util.ZipSecureFile

/**
 * Apache POI's JVM-wide limits (ADR-0048, ADR-0055), set once by every module that opens OOXML packages (templates and
 * the import's workbooks): entries at most 64 MiB inflated and compressed at most 100 to 1 (the ratio the upload pipeline
 * enforces), so a package that passed upload but is a bomb inside still fails. A package opened from a stream keeps parts
 * above 1 MiB in temporary files encrypted with a key held only in memory, so a large workbook neither fills the heap nor
 * leaves plaintext on disk. The settings are made once, when this object is first used.
 */
object PoiLimits {
    const val MAX_ENTRY_BYTES = 64L shl 20
    const val MIN_INFLATE_RATIO = 0.01
    const val TEMP_FILE_THRESHOLD = 1 shl 20

    init {
        ZipSecureFile.setMaxEntrySize(MAX_ENTRY_BYTES)
        ZipSecureFile.setMinInflateRatio(MIN_INFLATE_RATIO)
        ZipInputStreamZipEntrySource.setThresholdBytesForTempFiles(TEMP_FILE_THRESHOLD)
        ZipInputStreamZipEntrySource.setEncryptTempFiles(true)
    }

    /** Makes sure the limits are in force (the first call sets them). */
    fun apply() = Unit
}
