package com.memo.memo.internal.rendition

import com.memo.memo.internal.rendition.PdfBoxPageRenderer.Companion.MARGIN

/**
 * A [Block.Table] as rows of a known height (E08 T3): each cell's text wrapped in its column at the small size, the header
 * bold. [PageLayout] decides where the pages break; this draws a band of lines of a row (all of it, or the part that fits).
 */
internal class TableRows(
    private val breaker: LineBreaker,
    table: Block.Table,
    width: Float,
) {
    private val widths: List<Float> =
        table.columns
            .sumOf { it.weight.toDouble() }
            .toFloat()
            .let { total -> table.columns.map { width * it.weight / total } }
    private val full = width

    /** The header's cells. */
    val header: Cells = cells(table.columns.map { it.name }, bold = true)

    /** Every row's cells, a short row padded with empty ones. */
    val rows: List<Cells> = table.rows.map { cells(it, bold = false) }

    /** The height of [lines] lines of a row, with its padding. */
    fun height(lines: Int): Float = lines * LINE + 2 * PAD

    /** Lines [from] to [from] + [count] of every cell of [cells], as one row with a rule under it. */
    fun band(
        cells: Cells,
        from: Int,
        count: Int,
    ): Row =
        Row(height(count)) { canvas, at ->
            var left = MARGIN
            cells.lines.forEachIndexed { column, cell ->
                cell.drop(from).take(count).forEachIndexed { i, line ->
                    var x = left + PAD
                    line.forEach { piece ->
                        canvas.text(x, at - PAD - i * LINE - SIZE, piece.font, SIZE, piece.text)
                        x += breaker.measure(piece, SIZE)
                    }
                }
                left += widths[column]
            }
            canvas.line(MARGIN, at - height(count), MARGIN + full, at - height(count), grey = !cells.bold)
        }

    private fun cells(
        values: List<String>,
        bold: Boolean,
    ) = Cells(widths.mapIndexed { i, w -> breaker.lines(listOf(Run(values.getOrElse(i) { "" }, bold = bold)), SIZE, w - 2 * PAD) }, bold)

    /** A row's cells as wrapped lines; [count] is the tallest cell's. */
    class Cells(
        val lines: List<List<List<Piece>>>,
        val bold: Boolean,
    ) {
        val count: Int = lines.maxOf { it.size }
    }

    companion object {
        const val SIZE = 8.5f
        const val PAD = 3f
        const val LINE = SIZE * 1.38f
    }
}
