package com.memo.imports.internal.sheet

import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.apache.commons.csv.CSVRecord
import org.apache.commons.csv.DuplicateHeaderMode
import org.apache.commons.io.ByteOrderMark
import org.apache.commons.io.input.BOMInputStream
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Reads one table from a CSV file (ADR-0055, design E06 §7) with Commons CSV's strict profile: RFC 4180 quoting, a
 * required header naming our columns, no duplicate or blank names, no lenient end of file or trailing data, and at most
 * the table's rows. UTF-8 only, with or without its BOM, decoded strictly: a UTF-16 or UTF-32 BOM, or any invalid byte,
 * is `NOT_UTF8`. The delimiter is a comma or a semicolon, whichever splits the header into our column names. No exception
 * leaves [read].
 */
class CsvSheetReader(
    private val limits: SheetLimits = SheetLimits(),
    private val deadline: ReadDeadline = ReadDeadline.NONE,
) {
    fun read(
        table: SheetTable,
        input: InputStream,
        sink: RowSink,
    ): SheetResult =
        SheetReads
            .guarded {
                val bytes = SheetReads.bounded(input, limits.maxBytes)
                val bom =
                    BOMInputStream
                        .builder()
                        .setInputStream(ByteArrayInputStream(bytes))
                        .setByteOrderMarks(*BOMS)
                        .setInclude(false)
                        .get()
                if (bom.hasBOM() && !bom.hasBOM(ByteOrderMark.UTF_8)) throw SheetStop(SheetProblem(SheetProblemCode.NOT_UTF8, table))
                val decoder =
                    StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                BufferedReader(InputStreamReader(bom, decoder), BUFFER).use { reader -> table(table, reader, sink) }
            }.let { result -> result.copy(problems = result.problems.map { if (it.table == null) it.copy(table = table) else it }) }

    private fun table(
        table: SheetTable,
        reader: BufferedReader,
        sink: RowSink,
    ): SheetResult {
        val line = firstLine(reader)
        val delimiter = line?.let { delimiterOf(table, it) }
        val collector = TableCollector(table, limits, sink, deadline)
        val problems = if (line != null && delimiter != null) collector.header(split(line, delimiter).orEmpty()) else emptyList()
        return when {
            line == null -> SheetResult.failed(SheetProblem(SheetProblemCode.HEADER_MISSING, table, 1))
            delimiter == null -> SheetResult.failed(SheetProblem(SheetProblemCode.DELIMITER_UNKNOWN, table, 1))
            problems.isNotEmpty() -> SheetResult(problems, emptyMap())
            else -> rows(table, reader, delimiter, collector)
        }
    }

    private fun rows(
        table: SheetTable,
        reader: BufferedReader,
        delimiter: Char,
        collector: TableCollector,
    ): SheetResult {
        val maxRows = limits.rows(table).toLong()
        CSVParser.builder().setReader(reader).setFormat(strict(delimiter, maxRows + 1)).get().use { parser ->
            var read = 0L
            for (record in parser) {
                read++
                val number = rowNumber(record)
                if (read > maxRows) {
                    throw SheetStop(SheetProblem(SheetProblemCode.TOO_MANY_ROWS, table, number, params = mapOf("limit" to maxRows)))
                }
                collector.row(number, record.values().withIndex().associate { (i, value) -> i to SheetCell(value.orEmpty()) })
            }
        }
        return SheetResult(emptyList(), mapOf(table to collector.rows))
    }

    /** The header line, read ahead and given back, or null when the file is empty. */
    private fun firstLine(reader: BufferedReader): String? {
        reader.mark(MAX_HEADER_CHARS + 1)
        val buffer = CharArray(MAX_HEADER_CHARS)
        var length = 0
        var read = 0
        while (length < buffer.size && read >= 0 && '\n' !in buffer.sliceArray(length - read until length)) {
            read = reader.read(buffer, length, buffer.size - length)
            if (read > 0) length += read
        }
        reader.reset()
        val text = String(buffer, 0, length)
        val end = text.indexOf('\n')
        if (end < 0 && length >= MAX_HEADER_CHARS) throw SheetStop(SheetProblem(SheetProblemCode.MALFORMED, rowNumber = 1))
        return (if (end < 0) text else text.substring(0, end)).removeSuffix("\r").takeIf { it.isNotBlank() }
    }

    /** `,` or `;`: whichever splits [line] into more of the table's column names; null when neither gives one. */
    private fun delimiterOf(
        table: SheetTable,
        line: String,
    ): Char? =
        DELIMITERS
            .associateWith { d -> split(line, d).orEmpty().count { table.knows(SheetColumns.normalise(it)) } }
            .filterValues { it > 0 }
            .maxByOrNull { it.value }
            ?.key

    /** The header's names split by [delimiter] (quotes honoured), or null when the line is not valid CSV. */
    private fun split(
        line: String,
        delimiter: Char,
    ): List<String>? =
        runCatching {
            CSVParser.builder().setReader(line.reader()).setFormat(plain(delimiter)).get().use { parser ->
                parser.firstOrNull()?.values()?.map { it.orEmpty() }
            }
        }.getOrNull()

    /** The record's number with the header as 1 (Commons CSV counts data records from 1). */
    private fun rowNumber(record: CSVRecord): Int = Math.toIntExact(minOf(record.recordNumber + 1, Int.MAX_VALUE.toLong()))

    private companion object {
        val BOMS =
            arrayOf(ByteOrderMark.UTF_8, ByteOrderMark.UTF_16BE, ByteOrderMark.UTF_16LE, ByteOrderMark.UTF_32BE, ByteOrderMark.UTF_32LE)
        val DELIMITERS = listOf(',', ';')
        const val BUFFER = 64 * 1024
        const val MAX_HEADER_CHARS = 64 * 1024

        fun plain(delimiter: Char): CSVFormat =
            CSVFormat.RFC4180
                .builder()
                .setDelimiter(delimiter)
                .get()

        /** T1's strict profile; the header line is checked first by our own rules, so its errors name the column. */
        fun strict(
            delimiter: Char,
            maxRows: Long,
        ): CSVFormat =
            CSVFormat.RFC4180
                .builder()
                .setDelimiter(delimiter)
                .setHeader()
                .setSkipHeaderRecord(true)
                .setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW)
                .setAllowMissingColumnNames(false)
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .setTrailingData(false)
                .setLenientEof(false)
                .setMaxRows(maxRows)
                .get()
    }
}
