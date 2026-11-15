package com.memo.memo.internal.rendition

import com.memo.memo.internal.rendition.PdfBoxPageRenderer.Companion.EM
import com.memo.memo.internal.rendition.PdfBoxPageRenderer.Companion.MARGIN
import org.apache.pdfbox.pdmodel.font.PDType0Font

/** A piece of a line in one font. */
internal data class Piece(
    val text: String,
    val font: PDType0Font,
)

/**
 * Greedy word wrapping on measured widths (ADR-0052): spaces between words, forced breaks at newlines, and a word wider
 * than the line broken at any character (hashes, long URLs). No hyphenation.
 */
internal class LineBreaker(
    private val fonts: PageFonts,
) {
    fun lines(
        runs: List<Run>,
        size: Float,
        width: Float,
    ): List<List<Piece>> {
        val builder = LineBuilder(size, width)
        runs.forEach { run ->
            val font = fonts.of(run)
            TOKENS.findAll(fonts.clean(run.text)).forEach { builder.add(it.value, font) }
        }
        return builder.finish()
    }

    fun measure(
        piece: Piece,
        size: Float,
    ): Float = piece.font.getStringWidth(piece.text) / EM * size

    /** [word] cut into pieces that each fit [width]. */
    private fun chunks(
        word: String,
        font: PDType0Font,
        size: Float,
        width: Float,
    ): List<String> {
        val chunks = mutableListOf<String>()
        val chunk = StringBuilder()
        word.codePoints().forEach { cp ->
            val next = chunk.toString() + String(Character.toChars(cp))
            if (chunk.isNotEmpty() && font.getStringWidth(next) / EM * size > width) {
                chunks += chunk.toString()
                chunk.setLength(0)
            }
            chunk.appendCodePoint(cp)
        }
        chunks += chunk.toString()
        return chunks
    }

    /** Neighbouring pieces in the same font as one, so a line is a few text operations. */
    private fun merged(pieces: List<Piece>): List<Piece> =
        pieces.fold(mutableListOf()) { acc, piece ->
            val last = acc.lastOrNull()
            if (last != null && last.font == piece.font) acc[acc.lastIndex] = Piece(last.text + piece.text, piece.font) else acc += piece
            acc
        }

    /** One paragraph's lines as they fill up: the current line, its width, and a space waiting for the next word. */
    private inner class LineBuilder(
        private val size: Float,
        private val width: Float,
    ) {
        private val lines = mutableListOf<List<Piece>>()
        private val current = mutableListOf<Piece>()
        private var used = 0f
        private var space: Piece? = null

        fun add(
            token: String,
            font: PDType0Font,
        ) {
            when {
                token == "\n" -> push()
                token.isBlank() -> if (current.isNotEmpty()) space = Piece(" ", font)
                else -> word(token, font)
            }
        }

        fun finish(): List<List<Piece>> {
            if (current.isNotEmpty() || lines.isEmpty()) push()
            return lines
        }

        private fun word(
            token: String,
            font: PDType0Font,
        ) {
            val gap = space?.let { measure(it, size) } ?: 0f
            val word = measure(Piece(token, font), size)
            if (current.isNotEmpty() && used + gap + word > width) push()
            if (current.isEmpty() && word > width) {
                val chunks = chunks(token, font, size, width)
                chunks.dropLast(1).forEach { chunk ->
                    current += Piece(chunk, font)
                    push()
                }
                current += Piece(chunks.last(), font)
                used = measure(current.last(), size)
            } else {
                val pending = space
                if (current.isNotEmpty() && pending != null) {
                    current += pending
                    used += gap
                }
                current += Piece(token, font)
                used += word
            }
            space = null
        }

        private fun push() {
            lines += merged(current)
            current.clear()
            used = 0f
            space = null
        }
    }

    private companion object {
        val TOKENS = Regex("\n| +|[^ \n]+")
    }
}

/** Something of a known height that draws itself with its top at the given y; spacers draw nothing. */
internal class Row(
    val height: Float,
    val spacer: Boolean = false,
    val draw: (Canvas, Float) -> Unit = { _, _ -> },
)

/**
 * Places blocks on pages: text flows line by line; a box is kept on one page when it fits on one, and flows unframed when
 * it is taller than a page; spacers vanish at the top of a page.
 */
internal class PageLayout(
    private val fonts: PageFonts,
    private val width: Float,
    private val top: Float,
    private val bottom: Float,
) {
    private val pages = mutableListOf<MutableList<Draw>>(mutableListOf())
    private val breaker = LineBreaker(fonts)
    private var y = top

    // Nothing placed on the current page yet (spacers and page breaks do nothing then).
    private var fresh = true

    fun pages(): List<List<Draw>> = pages

    fun place(block: Block) {
        when (block) {
            is Block.PageBreak -> if (!fresh) newPage()
            is Block.Box -> box(block)
            else -> rows(block, MARGIN, width).forEach(::row)
        }
    }

    private fun box(block: Block.Box) {
        val inner = block.blocks.flatMap { rows(it, MARGIN + PAD, width - 2 * PAD) }.dropLastWhile { it.spacer }
        val height = inner.sumOf { it.height.toDouble() }.toFloat() + 2 * PAD
        if (height > top - bottom) {
            inner.forEach(::row)
            row(Row(SPACE, spacer = true))
            return
        }
        if (y - height < bottom) newPage()
        val boxTop = y
        pages.last() += { canvas -> canvas.frame(MARGIN, boxTop - height, width, height) }
        var rowTop = boxTop - PAD
        inner.forEach { r ->
            val at = rowTop
            pages.last() += { canvas -> r.draw(canvas, at) }
            rowTop -= r.height
        }
        y -= height
        fresh = false
        row(Row(SPACE, spacer = true))
    }

    private fun row(r: Row) {
        if (r.spacer && fresh) return
        if (y - r.height < bottom) {
            newPage()
            if (r.spacer) return
        }
        val at = y
        pages.last() += { canvas -> r.draw(canvas, at) }
        y -= r.height
        if (!r.spacer) fresh = false
    }

    private fun newPage() {
        pages += mutableListOf<Draw>()
        y = top
        fresh = true
    }

    private fun rows(
        block: Block,
        x: Float,
        width: Float,
    ): List<Row> =
        when (block) {
            is Block.Title -> {
                text(listOf(Run(block.text, bold = true)), TITLE, x, width) + Row(SPACE * 2, spacer = true)
            }

            is Block.Heading -> {
                listOf(Row(SPACE * 2, spacer = true)) + text(listOf(Run(block.text, bold = true)), HEADING, x, width) +
                    Row(SPACE, spacer = true)
            }

            is Block.Paragraph -> {
                paragraph(block, x, width)
            }

            is Block.KeyValues -> {
                block.rows.flatMap { (key, value) -> keyValue(key, value, x, width) } + Row(SPACE, spacer = true)
            }

            is Block.Rule -> {
                listOf(Row(SPACE * 2) { canvas, at -> canvas.line(x, at - SPACE, x + width, at - SPACE) })
            }

            is Block.Box, is Block.PageBreak -> {
                emptyList()
            }
        }

    private fun paragraph(
        block: Block.Paragraph,
        x: Float,
        width: Float,
    ): List<Row> {
        val size = if (block.small) SMALL else BODY
        val indent = block.indent * INDENT
        return text(block.runs, size, x + indent, width - indent, grey = block.struck || block.small, struck = block.struck) +
            Row(SPACE, spacer = true)
    }

    private fun keyValue(
        key: String,
        value: String,
        x: Float,
        width: Float,
    ): List<Row> {
        val keyWidth = width * KEY_SHARE
        val keys = breaker.lines(listOf(Run(key)), SMALL, keyWidth - PAD)
        val values = breaker.lines(listOf(Run(value)), BODY, width - keyWidth)
        val height = maxOf(keys.size * SMALL, values.size * BODY) * LEADING
        return listOf(
            Row(height) { canvas, at ->
                keys.forEachIndexed { i, line -> draw(canvas, line, x, at - i * SMALL * LEADING, SMALL, grey = true) }
                values.forEachIndexed { i, line -> draw(canvas, line, x + keyWidth, at - i * BODY * LEADING, BODY) }
            },
        )
    }

    private fun text(
        runs: List<Run>,
        size: Float,
        x: Float,
        width: Float,
        grey: Boolean = false,
        struck: Boolean = false,
    ): List<Row> =
        breaker.lines(runs, size, width).map { line ->
            Row(size * LEADING) { canvas, at ->
                val end = draw(canvas, line, x, at, size, grey)
                if (struck) canvas.line(x, at - size * STRIKE, end, at - size * STRIKE, grey = true)
            }
        }

    /** Draws one line with its top at [at]; returns where it ends. */
    private fun draw(
        canvas: Canvas,
        line: List<Piece>,
        x: Float,
        at: Float,
        size: Float,
        grey: Boolean = false,
    ): Float {
        var left = x
        line.forEach { piece ->
            canvas.text(left, at - size, piece.font, size, piece.text, grey)
            left += breaker.measure(piece, size)
        }
        return left
    }

    private companion object {
        const val TITLE = 17f
        const val HEADING = 12.5f
        const val BODY = 10.5f
        const val SMALL = 8.5f
        const val LEADING = 1.38f
        const val SPACE = 4f
        const val PAD = 8f
        const val INDENT = 14f
        const val KEY_SHARE = 0.3f
        const val STRIKE = 0.68f
    }
}
