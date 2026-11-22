package com.memo.memo.internal.rendition

import com.memo.memo.ReportDocument
import com.memo.memo.ReportPages
import com.memo.memo.ReportRendered
import org.springframework.stereotype.Component
import java.io.OutputStream

/** [ReportPages] as generated pages (ADR-0052): a title, then per section a heading, its paragraphs, its lines and its table. */
@Component
class ReportPagesRenderer(
    private val renderer: PageRenderer,
) : ReportPages {
    override fun render(
        document: ReportDocument,
        out: OutputStream,
    ): ReportRendered {
        val result = renderer.render(pagesOf(document), out)
        return ReportRendered(result.pages, result.missingGlyphs)
    }

    companion object {
        /** [document] in the page model: a title, then per section a heading, its paragraphs, its lines and its table. */
        fun pagesOf(document: ReportDocument): PageDocument {
            val blocks = mutableListOf<Block>(Block.Title(document.title))
            document.sections.forEach { section ->
                blocks += Block.Heading(section.heading)
                section.paragraphs.forEach { blocks += Block.Paragraph(it) }
                if (section.lines.isNotEmpty()) blocks += Block.KeyValues(section.lines)
                section.table?.let { table ->
                    blocks += Block.Table(table.columns.map { Block.Column(it.name, it.weight) }, table.rows)
                }
            }
            return PageDocument(document.header, document.footer, blocks, document.landscape, document.pagesInWords)
        }
    }
}
