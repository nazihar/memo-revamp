package com.memo.imports.internal.dryrun

import com.memo.common.tenant.TenantDsl
import com.memo.imports.internal.PathRules
import com.memo.jooq.tables.references.IMPORTS_FILE
import com.memo.jooq.tables.references.IMPORTS_ROW_FILE
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import java.util.UUID

/** How a Files row's path found its original (design E06 §5.2 step 2). */
enum class PathMatch { EXACT, CASE, NONE, AMBIGUOUS }

/** A Files row's path, by the row's id. */
data class RowPath(
    val id: UUID,
    val path: String?,
)

/** A Files row matched (or not) to a registered original. */
data class RowMatch(
    val id: UUID,
    val intakeId: UUID?,
    val match: PathMatch,
)

/** The staging reads and writes of path matching; inside a tenant transaction. */
@Repository
class PathMatchStore(
    private val tenantDsl: TenantDsl,
) {
    /** Files rows of the batch after [after] (by id), at most [limit]. */
    fun page(
        batchId: UUID,
        after: UUID?,
        limit: Int,
    ): List<RowPath> =
        tenantDsl
            .current()
            .select(IMPORTS_ROW_FILE.ID, IMPORTS_ROW_FILE.PATH)
            .from(IMPORTS_ROW_FILE)
            .where(IMPORTS_ROW_FILE.BATCH_ID.eq(batchId))
            .and(
                after?.let { IMPORTS_ROW_FILE.ID.gt(it) } ?: org.jooq.impl.DSL
                    .noCondition(),
            ).orderBy(IMPORTS_ROW_FILE.ID)
            .limit(limit)
            .fetch { RowPath(it.value1()!!, it.value2()) }

    /** The batch's registered originals at [paths], by path. */
    fun byPath(
        batchId: UUID,
        paths: Collection<String>,
    ): Map<String, UUID> =
        if (paths.isEmpty()) {
            emptyMap()
        } else {
            tenantDsl
                .current()
                .select(IMPORTS_FILE.PATH, IMPORTS_FILE.ID)
                .from(IMPORTS_FILE)
                .where(IMPORTS_FILE.BATCH_ID.eq(batchId), IMPORTS_FILE.PATH.`in`(paths))
                .fetch { it.value1()!! to it.value2()!! }
                .toMap()
        }

    /** The batch's registered originals whose case-folded path is one of [folds], by fold. */
    fun byFold(
        batchId: UUID,
        folds: Collection<String>,
    ): Map<String, List<UUID>> =
        if (folds.isEmpty()) {
            emptyMap()
        } else {
            tenantDsl
                .current()
                .select(IMPORTS_FILE.PATH_FOLD, IMPORTS_FILE.ID)
                .from(IMPORTS_FILE)
                .where(IMPORTS_FILE.BATCH_ID.eq(batchId), IMPORTS_FILE.PATH_FOLD.`in`(folds))
                .fetch { it.value1()!! to it.value2()!! }
                .groupBy({ it.first }, { it.second })
        }

    fun save(matches: List<RowMatch>) {
        if (matches.isEmpty()) return
        val dsl = tenantDsl.current()
        dsl
            .batch(
                matches.map {
                    dsl
                        .update(IMPORTS_ROW_FILE)
                        .set(IMPORTS_ROW_FILE.FILE_ID, it.intakeId)
                        .set(IMPORTS_ROW_FILE.MATCH, it.match.name)
                        .where(IMPORTS_ROW_FILE.ID.eq(it.id))
                },
            ).execute()
    }
}

/**
 * Matches each Files row's path to a registered original (design E06 §5.2 step 2): the same NFC path, else the one
 * original whose path differs only in case (`PATH_CASE_MATCH`), else AMBIGUOUS (several do) or NONE. Whether the matched
 * original was received, refused or never sent is the validation's business. Inside a tenant transaction.
 */
@Component
class PathMatching(
    private val store: PathMatchStore,
) {
    /** Matches one page of Files rows after [after]; returns the last row's id, or null when there were none. */
    fun page(
        batchId: UUID,
        after: UUID?,
        limit: Int = PAGE,
    ): UUID? {
        val rows = store.page(batchId, after, limit)
        if (rows.isEmpty()) return null
        val paths = rows.mapNotNull { it.path }
        val exact = store.byPath(batchId, paths)
        val folds = store.byFold(batchId, paths.filterNot { it in exact }.map(PathRules::fold))
        store.save(rows.map { match(it, exact, folds) })
        return rows.last().id
    }

    private fun match(
        row: RowPath,
        exact: Map<String, UUID>,
        folds: Map<String, List<UUID>>,
    ): RowMatch {
        val path = row.path
        val same = path?.let(exact::get)
        val candidates = path?.let { folds[PathRules.fold(it)] }.orEmpty()
        return when {
            same != null -> RowMatch(row.id, same, PathMatch.EXACT)
            candidates.size == 1 -> RowMatch(row.id, candidates.single(), PathMatch.CASE)
            candidates.size > 1 -> RowMatch(row.id, null, PathMatch.AMBIGUOUS)
            else -> RowMatch(row.id, null, PathMatch.NONE)
        }
    }

    companion object {
        const val PAGE = 1_000
    }
}
