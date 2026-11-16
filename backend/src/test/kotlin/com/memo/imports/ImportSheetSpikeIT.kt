package com.memo.imports

import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.apache.commons.csv.DuplicateHeaderMode
import org.apache.commons.io.input.BOMInputStream
import org.apache.poi.openxml4j.opc.OPCPackage
import org.apache.poi.openxml4j.opc.PackageAccess
import org.apache.poi.ss.usermodel.Comment
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.util.XMLHelper
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable
import org.apache.poi.xssf.eventusermodel.XSSFReader
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler
import org.apache.poi.xssf.streaming.SXSSFWorkbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.lang.management.ManagementFactory
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The E06 T1 spike, metadata half (ADR-0055, test plan T1): POI 6.0.0's streaming reader on a full-size workbook (time
 * and peak heap), the 1900 and 1904 date systems, cached formula values, DTD and external-entity refusal, and the Commons
 * CSV 1.14.1 strict profile on a hostile corpus. Not in CI (`MEMO_SPIKE_IMPORT`); the results are recorded in the E06 log.
 */
@EnabledIfEnvironmentVariable(named = "MEMO_SPIKE_IMPORT", matches = ".+")
class ImportSheetSpikeIT {
    @Test
    fun `a full-size workbook streams within the time limit, and its peak heap is recorded`() {
        val file = Files.createTempFile("import-spike", ".xlsx")
        try {
            val written = time { writeFullSize(file) }
            println("ImportSheetSpikeIT workbook: ${Files.size(file) / MIB} MiB, written in ${written.millis} ms")
            val peak = PeakHeap().apply { start() }
            val parsed = time { read(file) }
            val peakMib = peak.stop() / MIB
            println("ImportSheetSpikeIT parse: ${parsed.value} rows in ${parsed.millis} ms, peak heap above baseline $peakMib MiB")
            assertThat(parsed.value).isEqualTo((MEMOS + FILES + 2).toLong())
            assertThat(parsed.millis).isLessThan(PARSE_LIMIT_MS)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `dates in the 1900 and 1904 systems and cached formula values, as the streaming reader gives them`() {
        for (date1904 in listOf(false, true)) {
            val bytes = smallWorkbook(date1904)
            val plain = cells(bytes, DataFormatter())
            val fixed = cells(bytes, WindowingFormatter(date1904))
            println("ImportSheetSpikeIT date1904=$date1904: handler as is $plain; with the workbook's date system $fixed")
            assertThat(fixed["A1"]).isEqualTo("2019-03-14")
            assertThat(fixed["B1"]).describedAs("cached formula value").isEqualTo("42")
            println("ImportSheetSpikeIT date1904=$date1904: a formula without a cached value reads as '${fixed["C1"]}'")
        }
    }

    @Test
    fun `a DTD or an external entity in the shared strings or a sheet is refused`() {
        val secret = Files.createTempFile("xxe-target", ".txt").also { Files.writeString(it, "XXE-LEAKED") }
        val doctype = """<!DOCTYPE sst [<!ENTITY x SYSTEM "${secret.toUri()}">]>"""
        val hostile =
            rewrite(smallWorkbook(false), "xl/sharedStrings.xml") { xml ->
                xml.replaceFirst("<sst", "$doctype<sst").replaceFirst("<t>", "<t>&x;")
            }
        val outcome = runCatching { cells(hostile, DataFormatter()) }
        println(
            "ImportSheetSpikeIT DTD in sharedStrings.xml: ${outcome.exceptionOrNull()?.let {
                it.javaClass.simpleName + ": " + it.message
            }}",
        )
        assertThat(outcome.isFailure).isTrue()
        assertThat(outcome.getOrNull()?.values.orEmpty()).noneMatch { it.contains("XXE-LEAKED") }

        val sheetDoctype =
            rewrite(smallWorkbook(false), "xl/worksheets/sheet1.xml") { xml -> xml.replaceFirst("<worksheet", "$doctype<worksheet") }
        val sheetOutcome = runCatching { cells(sheetDoctype, DataFormatter()) }
        println("ImportSheetSpikeIT DTD in a sheet: ${sheetOutcome.exceptionOrNull()?.let { it.javaClass.simpleName + ": " + it.message }}")
        assertThat(sheetOutcome.isFailure).isTrue()
        Files.delete(secret)
    }

    @Test
    fun `the strict CSV profile on a hostile corpus`() {
        fun parse(bytes: ByteArray): List<Map<String, String>> =
            CSVParser.parse(strictReader(ByteArrayInputStream(bytes)), format(delimiterOf(bytes))).use { parser ->
                parser.records.map { it.toMap() }
            }
        val good = "legacy_number,subject\r\nCR/2019/0001,\"Rent, floors 3 and 4\"\r\n".toByteArray()
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + good
        val semicolon = "legacy_number;subject\nCR/2019/0002;Rent\n".toByteArray()
        assertThat(parse(good).single()["subject"]).isEqualTo("Rent, floors 3 and 4")
        assertThat(parse(bom).single()).containsKey("legacy_number")
        assertThat(parse(semicolon).single()["legacy_number"]).isEqualTo("CR/2019/0002")

        val refused =
            mapOf(
                "duplicate header" to "a,a\n1,2\n".toByteArray(),
                "missing header name" to "a,\n1,2\n".toByteArray(),
                "invalid UTF-8" to "a,b\n1,".toByteArray() + byteArrayOf(0xC3.toByte(), 0x28) + "\n".toByteArray(),
                "unclosed quote at the end" to "a,b\n1,\"open\n".toByteArray(),
                "data after a closing quote" to "a,b\n1,\"x\"y\n".toByteArray(),
            )
        refused.forEach { (case, bytes) ->
            val failure = runCatching { parse(bytes) }.exceptionOrNull()
            println("ImportSheetSpikeIT CSV $case: ${failure?.let { it.javaClass.simpleName + ": " + it.message?.take(MESSAGE) }}")
            assertThat(failure).describedAs(case).isNotNull()
        }
        // Rows past the cap are not read at all.
        val many = ("a\n" + "x\n".repeat(MAX_ROWS.toInt() + 10)).toByteArray()
        assertThat(parse(many)).hasSize(MAX_ROWS.toInt())

        val giant = ("a,b\n1,\"" + "y".repeat(GIANT_FIELD) + "\"\n").toByteArray()
        val elapsed = time { parse(giant).single()["b"]!!.length }
        println("ImportSheetSpikeIT CSV giant field: ${elapsed.value} characters in ${elapsed.millis} ms")
    }

    // --- the readers under test ---

    private fun read(file: Path): Long {
        val rows = AtomicLong()
        OPCPackage.open(file.toFile(), PackageAccess.READ).use { pkg ->
            val reader = XSSFReader(pkg)
            val strings = ReadOnlySharedStringsTable(pkg, false)
            val sheets = reader.sheetsData as XSSFReader.SheetIterator
            while (sheets.hasNext()) {
                sheets.next().use { stream ->
                    parse(stream, XSSFSheetXMLHandler(reader.stylesTable, null, strings, RowCounter(rows), DataFormatter(), false))
                }
            }
        }
        return rows.get()
    }

    private fun cells(
        workbook: ByteArray,
        formatter: DataFormatter,
    ): Map<String, String> {
        val cells = linkedMapOf<String, String>()
        OPCPackage.open(ByteArrayInputStream(workbook)).use { pkg ->
            val reader = XSSFReader(pkg)
            val strings = ReadOnlySharedStringsTable(pkg, false)
            reader.sheetsData.next().use { stream ->
                parse(stream, XSSFSheetXMLHandler(reader.stylesTable, null, strings, CellCollector(cells), formatter, false))
            }
        }
        return cells
    }

    private fun parse(
        stream: InputStream,
        handler: XSSFSheetXMLHandler,
    ) {
        val xml = XMLHelper.newXMLReader()
        xml.contentHandler = handler
        xml.parse(InputSource(stream))
    }

    /** The streaming handler formats dates in the 1900 system only; this applies the workbook's own system. */
    private class WindowingFormatter(
        private val date1904: Boolean,
    ) : DataFormatter() {
        override fun formatRawCellContents(
            value: Double,
            formatIndex: Int,
            formatString: String?,
        ): String = formatRawCellContents(value, formatIndex, formatString, date1904)
    }

    private class RowCounter(
        private val rows: AtomicLong,
    ) : XSSFSheetXMLHandler.SheetContentsHandler {
        override fun startRow(rowNum: Int) = Unit

        override fun endRow(rowNum: Int) {
            rows.incrementAndGet()
        }

        override fun cell(
            cellReference: String?,
            formattedValue: String?,
            comment: Comment?,
        ) = Unit
    }

    private class CellCollector(
        private val cells: MutableMap<String, String>,
    ) : XSSFSheetXMLHandler.SheetContentsHandler {
        override fun startRow(rowNum: Int) = Unit

        override fun endRow(rowNum: Int) = Unit

        override fun cell(
            cellReference: String?,
            formattedValue: String?,
            comment: Comment?,
        ) {
            if (cellReference != null) cells[cellReference] = formattedValue.orEmpty()
        }
    }

    private fun strictReader(input: InputStream): InputStreamReader {
        val decoder =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        return InputStreamReader(BOMInputStream.builder().setInputStream(input).get(), decoder)
    }

    private fun format(delimiter: Char): CSVFormat =
        CSVFormat.RFC4180
            .builder()
            .setDelimiter(delimiter)
            .setHeader()
            .setSkipHeaderRecord(true)
            .setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW)
            .setAllowMissingColumnNames(false)
            .setTrailingData(false)
            .setLenientEof(false)
            .setMaxRows(MAX_ROWS)
            .get()

    /** Comma or semicolon, whichever the header line holds more of. */
    private fun delimiterOf(bytes: ByteArray): Char {
        val header = String(bytes, StandardCharsets.UTF_8).substringBefore('\n')
        return if (header.count { it == ';' } > header.count { it == ',' }) ';' else ','
    }

    // --- fixtures ---

    private fun writeFullSize(file: Path) {
        SXSSFWorkbook(XSSFWorkbook(), WINDOW, true, true).use { workbook ->
            val dateStyle = workbook.createCellStyle().apply { dataFormat = workbook.createDataFormat().getFormat("yyyy-mm-dd") }
            val memos = workbook.createSheet("Memos")
            memos.createRow(0).apply {
                MEMO_COLUMNS.forEachIndexed { i, name -> createCell(i).setCellValue(name) }
            }
            val start = LocalDate.of(2010, 1, 1)
            val random = java.util.Random(SEED)
            for (n in 1..MEMOS) {
                val row = memos.createRow(n)
                row.createCell(0).setCellValue("CR/%d/%05d".format(2010 + n % 15, n))
                row.createCell(1).setCellValue(if (n % 3 == 0) "Credit proposal" else "General memo")
                row.createCell(2).setCellValue("Legacy memo $n about the branch at plot ${n * 7} and its lease")
                row.createCell(3).apply {
                    setCellValue(Date.from(start.plusDays(n % 5000L).atStartOfDay().toInstant(ZoneOffset.UTC)))
                    cellStyle = dateStyle
                }
                row.createCell(4).setCellValue("Officer ${n % 900}")
                row.createCell(5).setCellValue("officer${n % 900}@legacy.example")
                row.createCell(6).setCellValue("UNIT-${n % 40}")
                row.createCell(7).setCellValue(if (n % 10 == 0) "Rejected" else "Approved")
                row.createCell(8).setCellValue(if (n % 7 == 0) "Confidential" else "Internal")
                row.createCell(9).setCellValue((n * 1000.5) % 9_000_000)
                // Free text that compresses as little as real remarks do, to bring the workbook to its 20 MiB cap.
                row.createCell(10).setCellValue(remarks(random))
            }
            val files = workbook.createSheet("Files")
            files.createRow(0).apply { FILE_COLUMNS.forEachIndexed { i, name -> createCell(i).setCellValue(name) } }
            for (n in 1..FILES) {
                val memo = (n - 1) / (FILES / MEMOS) + 1
                val row = files.createRow(n)
                row.createCell(0).setCellValue("CR/%d/%05d".format(2010 + memo % 15, memo))
                row.createCell(1).setCellValue("archive/${2010 + memo % 15}/unit-${memo % 40}/memo-$memo/file-$n.pdf")
                row.createCell(2).setCellValue(if (n % (FILES / MEMOS) == 1) "main" else "attachment")
                row.createCell(3).setCellValue("Scan $n")
                row.createCell(4).setCellValue("%064x".format(n.toLong() * LARGE_PRIME))
            }
            Files.newOutputStream(file).use(workbook::write)
        }
    }

    private fun remarks(random: java.util.Random): String =
        (1..REMARK_WORDS).joinToString(" ") { WORDS[random.nextInt(WORDS.size)] + random.nextInt(REMARK_NUMBER) }

    /** A1 a date cell (2019-03-14), B1 a formula with its cached value 42, C1 a formula with no cached value. */
    private fun smallWorkbook(date1904: Boolean): ByteArray =
        XSSFWorkbook().use { workbook ->
            workbook.ctWorkbook.addNewWorkbookPr().date1904 = date1904
            val sheet = workbook.createSheet("Memos")
            val row = sheet.createRow(0)
            row.createCell(0).apply {
                // The serial in the workbook's own date system, as Excel stores it.
                setCellValue(DateUtil.getExcelDate(LocalDate.of(2019, 3, 14).atStartOfDay(), date1904))
                cellStyle = workbook.createCellStyle().apply { dataFormat = workbook.createDataFormat().getFormat("yyyy-mm-dd") }
            }
            row.createCell(4).setCellValue(41.0)
            row.createCell(1).cellFormula = "E1+1"
            workbook.creationHelper.createFormulaEvaluator().evaluateFormulaCell(row.getCell(1))
            row.createCell(2).cellFormula = "E1*2"
            ByteArrayOutputStream().also(workbook::write).toByteArray()
        }

    private fun rewrite(
        zip: ByteArray,
        entry: String,
        change: (String) -> String,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipInputStream(ByteArrayInputStream(zip)).use { input ->
            ZipOutputStream(out).use { output ->
                generateSequence { input.nextEntry }.forEach { e ->
                    val bytes = input.readAllBytes()
                    output.putNextEntry(ZipEntry(e.name))
                    output.write(if (e.name == entry) change(String(bytes, StandardCharsets.UTF_8)).toByteArray() else bytes)
                    output.closeEntry()
                }
            }
        }
        return out.toByteArray()
    }

    private class Timed<T>(
        val value: T,
        val millis: Long,
    )

    private fun <T> time(block: () -> T): Timed<T> {
        val start = System.nanoTime()
        val value = block()
        return Timed(value, (System.nanoTime() - start) / NANOS_PER_MILLI)
    }

    /** Samples the used heap every few milliseconds; the peak is measured above the level after a GC at start. */
    private class PeakHeap {
        private val running = AtomicBoolean(true)
        private val peak = AtomicLong()
        private var baseline = 0L
        private lateinit var sampler: Thread

        fun start() {
            System.gc()
            baseline = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used
            sampler =
                Thread {
                    while (running.get()) {
                        peak.accumulateAndGet(ManagementFactory.getMemoryMXBean().heapMemoryUsage.used, ::maxOf)
                        Thread.sleep(SAMPLE_MS)
                    }
                }.apply {
                    isDaemon = true
                    start()
                }
        }

        fun stop(): Long {
            running.set(false)
            sampler.join()
            return (peak.get() - baseline).coerceAtLeast(0)
        }
    }

    private companion object {
        const val MEMOS = 50_000
        const val FILES = 200_000
        const val WINDOW = 200
        const val MIB = 1024 * 1024
        const val NANOS_PER_MILLI = 1_000_000L
        const val PARSE_LIMIT_MS = 120_000L
        const val SAMPLE_MS = 5L
        const val MAX_ROWS = 1000L
        const val GIANT_FIELD = 10_000_000
        const val MESSAGE = 160
        const val LARGE_PRIME = 2_305_843_009_213_693_951L
        const val SEED = 42L
        const val REMARK_WORDS = 40
        const val REMARK_NUMBER = 1000
        val WORDS = listOf("lease", "branch", "rent", "loan", "approval", "floor", "budget", "vendor", "audit", "staff", "plot", "credit")
        val MEMO_COLUMNS =
            listOf(
                "legacy_number",
                "type",
                "subject",
                "raised_on",
                "initiator",
                "initiator_id",
                "unit",
                "status",
                "classification",
                "amount",
                "remarks",
            )
        val FILE_COLUMNS = listOf("legacy_number", "path", "role", "title", "sha256")
    }
}
