package com.memo.search

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.springframework.test.web.servlet.assertj.MvcTestResult
import java.io.ByteArrayInputStream

/**
 * The text of the decision register's exported files (E08 T3), for the leak suites' canary checks: every cell of every
 * sheet of the spreadsheet, and the PDF's text in drawing order (each cell's lines together, so a wrapped value reads as
 * it was written), with runs of white space as one space.
 */
object RegisterFiles {
    /** The formats of `POST /decision-register/export`. */
    val formats = listOf("XLSX", "PDF")

    fun text(result: MvcTestResult): String {
        val bytes = result.response.contentAsByteArray
        val text =
            if (result.response.contentType == "application/pdf") {
                Loader.loadPDF(bytes).use { PDFTextStripper().getText(it) }
            } else {
                XSSFWorkbook(ByteArrayInputStream(bytes)).use { book ->
                    (0 until book.numberOfSheets).joinToString("\n") { i ->
                        book.getSheetAt(i).joinToString("\n") { row -> row.joinToString(" | ") { it.stringCellValue } }
                    }
                }
            }
        return text.replace(Regex("\\s+"), " ")
    }
}
