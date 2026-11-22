package com.memo.search.internal

import com.fasterxml.jackson.annotation.JsonProperty
import com.memo.common.authz.SignedIn
import com.memo.common.context.ActorContext
import com.memo.common.ratelimit.RateLimit
import com.memo.common.ratelimit.RateLimiter
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.FieldProblem
import com.memo.common.web.ProblemTypes
import com.memo.common.web.etagOf
import com.memo.common.web.ifMatchVersion
import com.memo.memo.MemoOrigin
import com.memo.search.internal.decision.TooManyRowsException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

/**
 * The body of `POST /search` (design E05 §3). A body, not a query string: search terms (names, account numbers) must not
 * reach access logs or browser history (AGENTS.md §3.4; threat model E05 I4).
 */
data class SearchParams(
    val q: String? = null,
    val type: List<UUID>? = null,
    val status: List<String>? = null,
    val unit: List<UUID>? = null,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val amountMin: BigDecimal? = null,
    val amountMax: BigDecimal? = null,
    /** Where to search: `metadata`, `notes`, `documents` (default all). */
    @param:JsonProperty("in")
    @get:JsonProperty("in")
    @field:Schema(name = "in")
    val sources: List<String>? = null,
    /** `relevance` (default) or `newest`. */
    val sort: String? = null,
    /** Memos raised here (`NATIVE`), imported from a legacy system (`IMPORTED`), or both (absent or empty). */
    val origin: List<MemoOrigin>? = null,
    val cursor: String? = null,
    val limit: Int? = null,
) {
    fun query(): SearchQuery {
        val sources = sources.orEmpty().map { name -> SearchSource.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
        val sort = sort?.let { name -> SearchSort.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
        val problems =
            listOfNotNull(
                FieldProblem("in", "UNKNOWN", "Search in metadata, notes or documents.").takeIf { null in sources },
                FieldProblem("sort", "UNKNOWN", "Sort by relevance or newest.").takeIf { this.sort != null && sort == null },
            )
        if (problems.isNotEmpty()) throw SearchRejectedException(problems)
        return SearchQuery(
            q = q.orEmpty(),
            types = type.orEmpty(),
            statuses = status.orEmpty(),
            units = unit.orEmpty(),
            from = from,
            to = to,
            amountMin = amountMin,
            amountMax = amountMax,
            sources = sources.filterNotNull().distinct().ifEmpty { if (this.sources == null) SearchSource.entries else emptyList() },
            sort = sort ?: SearchSort.RELEVANCE,
            origins = origin.orEmpty().distinct(),
        )
    }
}

/** The body of `POST /search/suggest`: what has been typed so far. */
data class SuggestRequest(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val q: String,
)

data class SavedSearchRequest(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val criteria: SearchQuery,
)

data class SavedSearchView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val criteria: SearchQuery,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val version: Long,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val createdAt: Instant,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val updatedAt: Instant,
)

/**
 * Search and saved searches (design E05 §3, §7; flows H1–H3). Signed-in users; results follow the reader's access rule.
 * Searches are rate-limited per person (30 a minute) before any transaction.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "search")
class SearchController(
    private val search: SearchService,
    private val saved: SavedSearches,
    private val transactions: TenantTransactions,
    private val limiter: RateLimiter,
) {
    @PostMapping("/search")
    @SignedIn
    @Operation(operationId = "search", summary = "Memos the reader may read that match, with counts, facets and snippets")
    fun search(
        @RequestBody params: SearchParams,
    ): ResponseEntity<SearchPageView> {
        limit()
        val query = params.query()
        val page =
            transactions.inTenant(TenantContext.current()) {
                search.search(query, params.cursor, params.limit ?: DEFAULT_PAGE)
            }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(page)
    }

    @PostMapping("/search/suggest")
    @SignedIn
    @Operation(operationId = "suggestSearch", summary = "Up to 8 memos while typing: the last word matches as a prefix")
    fun suggest(
        @RequestBody body: SuggestRequest,
    ): ResponseEntity<List<SuggestionView>> {
        limit()
        val found = transactions.inTenant(TenantContext.current()) { search.suggest(body.q) }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(found)
    }

    @GetMapping("/saved-searches")
    @SignedIn
    @Operation(operationId = "listSavedSearches", summary = "My saved searches, by name")
    fun list(): ResponseEntity<List<SavedSearchView>> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(transactions.readInTenant(TenantContext.current()) { saved.list().map(::view) })

    @PostMapping("/saved-searches")
    @SignedIn
    @Operation(operationId = "createSavedSearch", summary = "Save a search (at most 50 per person)")
    fun create(
        @RequestBody body: SavedSearchRequest,
    ): ResponseEntity<SavedSearchView> {
        val created = transactions.inTenant(TenantContext.current()) { view(saved.create(body.name, body.criteria)) }
        return ResponseEntity.status(HttpStatus.CREATED).eTag(etagOf(created.version)).body(created)
    }

    @PutMapping("/saved-searches/{id}")
    @SignedIn
    @Operation(operationId = "replaceSavedSearch", summary = "Rename a saved search or change its criteria")
    fun replace(
        @PathVariable id: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: SavedSearchRequest,
    ): ResponseEntity<SavedSearchView> {
        val version = ifMatchVersion(ifMatch)
        val updated = transactions.inTenant(TenantContext.current()) { view(saved.update(id, version, body.name, body.criteria)) }
        return ResponseEntity.ok().eTag(etagOf(updated.version)).body(updated)
    }

    @DeleteMapping("/saved-searches/{id}")
    @SignedIn
    @Operation(operationId = "deleteSavedSearch", summary = "Delete a saved search")
    fun delete(
        @PathVariable id: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
    ): ResponseEntity<Unit> {
        val version = ifMatchVersion(ifMatch)
        transactions.inTenant(TenantContext.current()) { saved.delete(id, version) }
        return ResponseEntity.noContent().build()
    }

    private fun limit() {
        limiter.consume(
            RateLimit.SEARCH_PER_USER,
            ActorContext
                .current()
                .actor.id
                .orEmpty(),
        )
    }

    private fun view(s: SavedSearch) = SavedSearchView(s.id, s.name, s.query, s.version, s.createdAt, s.updatedAt)

    private companion object {
        const val DEFAULT_PAGE = 20
    }
}

/** Problems of the search endpoints. */
@RestControllerAdvice(basePackageClasses = [SearchProblems::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class SearchProblems {
    @ExceptionHandler(SearchRejectedException::class)
    fun rejected(ex: SearchRejectedException): ResponseEntity<ProblemDetail> =
        ResponseEntity
            .status(HttpStatus.UNPROCESSABLE_CONTENT)
            .body(ProblemTypes.validation(ex.problems).apply { status = HttpStatus.UNPROCESSABLE_CONTENT.value() })

    /**
     * The register's export holds more rows than its format allows (design E08 §1.5, flow AT "too many rows"): 422 with the
     * count (`moreThan` when it is a lower bound) and the format's maximum, so the dialog can say "narrow the filters".
     */
    @ExceptionHandler(TooManyRowsException::class)
    fun tooMany(ex: TooManyRowsException): ResponseEntity<ProblemDetail> {
        val count = (if (ex.moreThan) "More than " else "") + "%,d".format(Locale.ENGLISH, ex.rows)
        val problem =
            FieldProblem(
                "format",
                "TOO_MANY_ROWS",
                "$count decisions match; this format holds up to ${"%,d".format(Locale.ENGLISH, ex.format.maxRows)}. Narrow the filters.",
            )
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(
            ProblemTypes.validation(listOf(problem)).apply {
                status = HttpStatus.UNPROCESSABLE_CONTENT.value()
                setProperty("rows", ex.rows)
                setProperty("moreThan", ex.moreThan)
                setProperty("maximum", ex.format.maxRows)
            },
        )
    }

    @ExceptionHandler(SavedSearchNotFoundException::class)
    fun notFound(ex: SavedSearchNotFoundException): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.message ?: "Not found"))

    /** The search took too long: the reader narrows it (flow H2 "That search took too long"). */
    @ExceptionHandler(SearchTimeoutException::class)
    fun timeout(): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
            ProblemTypes.of(HttpStatus.SERVICE_UNAVAILABLE, ProblemTypes.CONFLICT, "That search took too long.").apply {
                setProperty("reason", "SEARCH_TIMEOUT")
            },
        )

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(ex: IllegalArgumentException): ResponseEntity<ProblemDetail> =
        ResponseEntity.badRequest().body(ProblemTypes.of(HttpStatus.BAD_REQUEST, ProblemTypes.VALIDATION, ex.message ?: "Invalid request."))
}
