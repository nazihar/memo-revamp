package com.memo.search.internal

import com.memo.common.context.ActorNames
import com.memo.files.Files
import com.memo.memo.MemoSearchSource
import com.memo.org.OrgUnitDirectory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * What a page of results shows (flow H2): each memo's number, subject, type, status and unit, a snippet with its matches,
 * and where it matched (details, its number, a note and its author, the document, an attachment by name); and the facets
 * of the whole permitted candidate set. Inside a tenant transaction, after the read rule was applied.
 */
@Component
class SearchResults(
    private val finder: SearchFinder,
    private val source: MemoSearchSource,
    private val files: Files,
    private val units: OrgUnitDirectory,
    private val names: ObjectProvider<ActorNames>,
) {
    fun views(
        page: List<SearchCandidate>,
        q: String,
        scope: ReaderScope,
    ): List<SearchResultView> {
        if (page.isEmpty()) return emptyList()
        val ids = page.map { it.memoId }
        val summaries = source.summaries(ids)
        val snippets = finder.snippets(ids, q, scope)
        val unitNames = units.units(page.mapNotNull { it.originUnitId })
        val authors = noteAuthors(snippets.values)
        return page.mapNotNull { c ->
            val summary = summaries[c.memoId] ?: return@mapNotNull null
            val snippet = snippets[c.memoId]
            SearchResultView(
                memoId = c.memoId,
                number = summary.reference,
                subject = summary.subject,
                typeId = c.typeId,
                typeName = summary.typeName,
                status = c.status,
                unitId = c.originUnitId,
                unitName = c.originUnitId?.let { unitNames[it]?.name },
                updatedAt = c.updatedAt,
                snippet = snippet?.text?.let(SnippetParts::of) ?: listOf(SnippetPart(summary.subject, false)),
                foundIn = foundIn(c, snippet, authors),
            )
        }
    }

    fun facets(candidates: List<SearchCandidate>): SearchFacetsView {
        val types = source.typeNames(candidates.map { it.typeId }.toSet())
        val unitNames = units.units(candidates.mapNotNull { it.originUnitId }.toSet())
        return SearchFacetsView(
            status = facet(candidates.groupingBy { it.status }.eachCount()) { it },
            type = facet(candidates.groupingBy { it.typeId }.eachCount()) { types[it].orEmpty() },
            unit = facet(candidates.mapNotNull { it.originUnitId }.groupingBy { it }.eachCount()) { unitNames[it]?.name.orEmpty() },
        )
    }

    fun suggestions(found: List<SearchCandidate>): List<SuggestionView> {
        val summaries = source.summaries(found.map { it.memoId })
        return found.mapNotNull { c -> summaries[c.memoId]?.let { SuggestionView(c.memoId, it.reference, it.subject) } }
    }

    private fun <K : Any> facet(
        counts: Map<K, Int>,
        label: (K) -> String,
    ): List<FacetView> =
        counts.entries
            .sortedWith(compareByDescending<Map.Entry<K, Int>> { it.value }.thenBy { it.key.toString() })
            .map { FacetView(it.key.toString(), label(it.key), it.value) }

    private fun noteAuthors(snippets: Collection<SearchSnippet>): Map<UUID, String> {
        val notes = snippets.filter { it.source == SearchSnippet.NOTE }.mapNotNull { it.sourceId?.let(source::note) }
        val people = names.ifAvailable?.names(notes.map { it.authorId }.toSet()).orEmpty()
        return notes.associate { it.noteId to people[it.authorId].orEmpty() }
    }

    private fun foundIn(
        candidate: SearchCandidate,
        snippet: SearchSnippet?,
        authors: Map<UUID, String>,
    ): FoundInView =
        when {
            candidate.byNumber -> FoundInView(FoundInKind.NUMBER)
            snippet?.source == SearchSnippet.NOTE -> FoundInView(FoundInKind.NOTE, snippet.sourceId?.let { authors[it] })
            snippet?.source == SearchSnippet.DOCUMENT -> document(snippet.sourceId)
            else -> FoundInView(FoundInKind.DETAILS)
        }

    /** The memo's document, or an attachment named by its file. */
    private fun document(versionId: UUID?): FoundInView {
        val version = versionId?.let(files::version)
        val file = version?.let { source.file(it.fileId) }
        return if (file == null || file.document) FoundInView(FoundInKind.DOCUMENT) else FoundInView(FoundInKind.ATTACHMENT, version.name)
    }
}
