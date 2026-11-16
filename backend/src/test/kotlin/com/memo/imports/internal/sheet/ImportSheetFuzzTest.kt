package com.memo.imports.internal.sheet

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import com.memo.imports.internal.PathRules

/**
 * Fuzz targets for the import's metadata readers (ADR-0055: POI parses an admin's workbook in the backend JVM; test plan
 * E06 T5 `ImportSheetFuzzTest`). In the PR stage they replay their stored inputs; the nightly stage fuzzes each for a few
 * minutes (`JAZZER_FUZZ=1`). A target fails when an exception leaves a reader (every failure must end as a batch
 * problem), or when a row breaks the caps.
 */
class ImportSheetFuzzTest {
    private val limits =
        SheetLimits(
            maxBytes = 1L shl 20,
            maxColumns = 20,
            maxCellChars = 1_000,
            maxRows = SheetTable.entries.associateWith { MAX_ROWS },
        )

    @FuzzTest(maxDuration = "5m")
    fun xlsx(data: FuzzedDataProvider) {
        val bytes = data.consumeRemainingAsBytes()
        checked(XlsxSheetReader(limits).read(bytes.inputStream(), ::row))
    }

    @FuzzTest(maxDuration = "5m")
    fun csv(data: FuzzedDataProvider) {
        val table = SheetTable.entries[data.consumeInt(0, SheetTable.entries.size - 1)]
        val bytes = data.consumeRemainingAsBytes()
        checked(CsvSheetReader(limits).read(table, bytes.inputStream(), ::row))
    }

    @FuzzTest(maxDuration = "5m")
    fun paths(data: FuzzedDataProvider) {
        val path = PathRules.normalise(data.consumeRemainingAsString()) ?: return
        check(PathRules.normalise(path) == path) { "a valid path normalises to itself" }
        check(
            path.split('/').none { it.isEmpty() || it == "." || it == ".." } && path.none { it.code == BACKSLASH } &&
                path.length <= PathRules.MAX_LENGTH,
        )
    }

    private fun row(row: SheetRow) {
        check(row.rowNumber >= 1)
        check(row.cells.size <= limits.maxColumns)
        check(row.cells.values.all { it.text.length <= limits.maxCellChars })
        check(row.cells.keys.all { row.table.knows(it) })
    }

    private fun checked(result: SheetResult) {
        check(result.rows.values.all { it in 0..MAX_ROWS })
        check(result.problems.none { it.code == SheetProblemCode.PARSE_TIMEOUT })
    }

    private companion object {
        const val MAX_ROWS = 50
        const val BACKSLASH = 92
    }
}
