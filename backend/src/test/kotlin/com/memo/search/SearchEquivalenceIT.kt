package com.memo.search

import com.memo.common.tenant.TenantDsl
import com.memo.memo.MemoOrigin
import com.memo.search.internal.ReaderScope
import com.memo.search.internal.SearchCriteria
import com.memo.search.internal.SearchFinder
import com.memo.search.internal.SearchSnippet
import com.memo.testsupport.MemoIntegrationTest
import com.memo.testsupport.TestDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.util.UUID
import kotlin.random.Random

/**
 * The search function against the E03 read rule (ADR-0050, ADR-0054; test plan E05 T3 property, E06 T4): on random memos,
 * native or imported (ADR-0055: final, without workflow or initiator), classifications, origin units, participants
 * (active, revoked, lapsed), notes and chunks above their memo's level, `search_find` returns exactly the memos the
 * reader may read that match and pass the Origin filter, for random readers (clearance, unit scopes, tenant-wide), queries
 * and origin filters; and no snippet comes from a memo the reader may not read or a chunk above their clearance. Imported
 * memos follow the same rule as native ones.
 */
class SearchEquivalenceIT : MemoIntegrationTest() {
    @Autowired lateinit var finder: SearchFinder

    @Autowired lateinit var tenantDsl: TenantDsl

    @Autowired lateinit var clock: Clock

    private data class Chunk(
        val versionId: UUID,
        val rank: Int,
        val words: Set<String>,
    )

    private data class Memo(
        val id: UUID,
        val rank: Int,
        val unit: UUID?,
        val subject: Set<String>,
        val notes: List<Set<String>>,
        val chunks: List<Chunk>,
        /** Participant rows: user to active. */
        val participants: Map<UUID, Boolean>,
        val origin: MemoOrigin,
    )

    /** The memo type, its published version (an imported memo is pinned to one) and the lowest level. */
    private data class Kind(
        val type: UUID,
        val version: UUID,
        val level: UUID,
    )

    private data class Query(
        val text: String,
        val matches: (Set<String>) -> Boolean,
    )

    @Test
    fun `search returns exactly what the read rule allows and matches, over random cases`() {
        val (type, level) =
            asUser(admin) {
                val dsl = tenantDsl.current()
                (dsl.fetchValue("select id from memo_type where key = 'general'") as UUID) to
                    (dsl.fetchValue("select id from authz_classification_level where rank = 0") as UUID)
            }
        val version =
            asUser(admin) {
                tenantDsl.current().fetchValue(
                    "select id from memo_type_version where memo_type_id = ? and state = 'PUBLISHED'",
                    type,
                ) as UUID
            }
        repeat(SEEDS) { seed ->
            val random = Random(seed)
            // Each case has words of its own, so cases in one tenant do not see each other's memos.
            val words = BASE_WORDS.map { "$it${LETTERS[seed]}" }
            val units = List(UNITS) { UUID.randomUUID() }
            val readers = List(READERS) { UUID.randomUUID() }
            val memos = List(MEMOS) { memo(random, words, units, readers) }
            write(memos, Kind(type, version, level), seed)
            repeat(CHECKS) { check(random, words, units, readers, memos) }
        }
    }

    private fun memo(
        random: Random,
        words: List<String>,
        units: List<UUID>,
        readers: List<UUID>,
    ): Memo {
        val rank = random.nextInt(RANKS)

        fun pick() = List(1 + random.nextInt(WORDS_PER_TEXT)) { words.random(random) }.toSet()
        return Memo(
            id = UUID.randomUUID(),
            rank = rank,
            unit = units.random(random).takeIf { random.nextInt(UNIT_ODDS) > 0 },
            subject = pick(),
            notes = List(random.nextInt(MAX_ROWS)) { pick() },
            chunks = List(random.nextInt(MAX_ROWS)) { Chunk(UUID.randomUUID(), rank + random.nextInt(RANKS - rank), pick()) },
            participants = readers.filter { random.nextInt(PARTICIPANT_ODDS) == 0 }.associateWith { random.nextBoolean() },
            origin = if (random.nextInt(IMPORTED_ODDS) == 0) MemoOrigin.IMPORTED else MemoOrigin.NATIVE,
        )
    }

    private fun check(
        random: Random,
        words: List<String>,
        units: List<UUID>,
        readers: List<UUID>,
        memos: List<Memo>,
    ) {
        val reader = readers.random(random)
        val scope =
            ReaderScope(
                reader = reader,
                clearanceRank = random.nextInt(RANKS),
                allUnits = random.nextInt(ALL_UNITS_ODDS) == 0,
                units = units.filter { random.nextBoolean() }.toSet(),
            )
        val a = words.random(random)
        val b = words.random(random)
        val query =
            when (random.nextInt(QUERY_KINDS)) {
                0 -> Query(a) { a in it }
                1 -> Query("$a $b") { a in it && b in it }
                else -> Query("$a or $b") { a in it || b in it }
            }
        val origins = ORIGIN_FILTERS[random.nextInt(ORIGIN_FILTERS.size)]
        val readable = memos.filter { readable(it, scope) }
        val expected =
            readable
                .filter { origins == null || it.origin in origins }
                .filter { m ->
                    query.matches(m.subject) || m.notes.any(query.matches) ||
                        m.chunks.any { it.rank <= scope.clearanceRank && query.matches(it.words) }
                }.map { it.id }
                .toSet()
        val found = asUser(admin) { finder.find(SearchCriteria(text = query.text, origins = origins), scope, CAP) }
        assertThat(found.map { it.memoId }.toSet()).describedAs("${query.text} in $origins as $scope").isEqualTo(expected)
        assertThat(found).allMatch { c -> memos.first { it.id == c.memoId }.origin == c.origin }

        val page = memos.shuffled(random).take(PAGE).map { it.id }
        val snippets = asUser(admin) { finder.snippets(page, query.text, scope) }
        assertThat(snippets.keys).isSubsetOf(readable.map { it.id })
        val visibleChunks = memos.flatMap { m -> m.chunks.filter { it.rank <= scope.clearanceRank }.map { it.versionId } }.toSet()
        assertThat(snippets.values.filter { it.source == SearchSnippet.DOCUMENT }.map { it.sourceId }).allMatch { it in visibleChunks }
    }

    /** The E03 rule (ADR-0050): clearance, then an active participant row or a unit scope. */
    private fun readable(
        memo: Memo,
        scope: ReaderScope,
    ): Boolean =
        memo.rank <= scope.clearanceRank &&
            (scope.allUnits || memo.unit in scope.units || memo.participants[scope.reader] == true)

    /** The memos and their search rows, as the schema owner (as the indexer would leave them). */
    private fun write(
        memos: List<Memo>,
        kind: Kind,
        seed: Int,
    ) {
        val now = clock.instant()
        owner { c ->
            memos.forEachIndexed { i, m ->
                val imported = m.origin == MemoOrigin.IMPORTED
                // An imported memo is final, pinned to a type version, outside workflow, with no initiator linked.
                val status = if (imported) "APPROVED" else "DRAFT"
                val initiator = UUID.randomUUID().takeUnless { imported }
                c.update(
                    """insert into memo (id, tenant_id, reference, memo_type_id, memo_type_version_id, subject, classification_id,
                       classification_rank, initiator_id, origin_unit_id, status, current_round, origin, created_at, updated_at)
                       values (?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, 0, ?, now(), now())""",
                    m.id,
                    tenant.id.value,
                    "EQ$seed-$i",
                    kind.type,
                    kind.version.takeIf { imported },
                    m.subject.joinToString(" "),
                    kind.level,
                    initiator,
                    m.unit,
                    status,
                    m.origin.name,
                )
                c.update(
                    """insert into search_memo (tenant_id, memo_id, classification_rank, origin_unit_id, status, type_id, initiator_id,
                       created_at, updated_at, subject, origin) values (?, ?, ?, ?, ?, ?, ?, now(), now(), ?, ?)""",
                    tenant.id.value,
                    m.id,
                    m.rank,
                    m.unit,
                    status,
                    kind.type,
                    initiator,
                    m.subject.joinToString(" "),
                    m.origin.name,
                )
                m.participants.forEach { (user, active) -> participant(c, m, user, active, now) }
                texts(c, m, kind.type)
            }
        }
    }

    /** The memo's note and chunk rows. */
    private fun texts(
        c: Connection,
        m: Memo,
        type: UUID,
    ) {
        m.notes.forEach { words ->
            c.update(
                """insert into search_note (tenant_id, note_id, memo_id, classification_rank, origin_unit_id, status, type_id,
                           memo_created_at, body, origin) values (?, ?, ?, ?, ?, 'DRAFT', ?, now(), ?, ?)""",
                tenant.id.value,
                UUID.randomUUID(),
                m.id,
                m.rank,
                m.unit,
                type,
                words.joinToString(" "),
                m.origin.name,
            )
        }
        m.chunks.forEach { chunk ->
            c.update(
                """insert into search_chunk (tenant_id, file_id, file_version_id, chunk, memo_id, file_rank, classification_rank,
                           origin_unit_id, status, type_id, memo_created_at, body, tsv, origin)
                           values (?, ?, ?, 0, ?, ?, ?, ?, 'DRAFT', ?, now(), ?, to_tsvector('english', ?), ?)""",
                tenant.id.value,
                UUID.randomUUID(),
                chunk.versionId,
                m.id,
                chunk.rank,
                chunk.rank,
                m.unit,
                type,
                chunk.words.joinToString(" "),
                chunk.words.joinToString(" "),
                m.origin.name,
            )
        }
    }

    /** An active row, or one that is not: revoked, or a share that lapsed. */
    private fun participant(
        c: Connection,
        memo: Memo,
        user: UUID,
        active: Boolean,
        now: java.time.Instant,
    ) {
        val lapsed = !active && memo.id.leastSignificantBits % 2 == 0L
        c.update(
            """insert into memo_participant (tenant_id, memo_id, user_id, kind, granted_by, created_at, expires_at, revoked_at)
               values (?, ?, ?, ?, ?, now(), ?, ?)""",
            tenant.id.value,
            memo.id,
            user,
            if (active || lapsed) "SHARED" else "COPY",
            UUID.randomUUID(),
            Timestamp.from(if (lapsed) now.minus(Duration.ofDays(1)) else now.plus(Duration.ofDays(1))).takeIf { active || lapsed },
            Timestamp.from(now.minus(Duration.ofHours(1))).takeIf { !active && !lapsed },
        )
    }

    private fun Connection.update(
        sql: String,
        vararg values: Any?,
    ) {
        prepareStatement(sql).use { st ->
            values.forEachIndexed { i, v -> st.setObject(i + 1, v) }
            st.executeUpdate()
        }
    }

    private fun owner(block: (Connection) -> Unit) =
        DriverManager.getConnection(TestDatabase.jdbcUrl, "memo_migrator", TestDatabase.migratorPassword).use { c ->
            c.autoCommit = false
            c.prepareStatement("select set_config('app.tenant_id', ?, true)").use {
                it.setString(1, tenant.id.value.toString())
                it.execute()
            }
            block(c)
            c.commit()
        }

    private companion object {
        const val SEEDS = 4
        const val CHECKS = 40
        const val MEMOS = 120
        const val UNITS = 5
        const val READERS = 6
        const val RANKS = 3
        const val MAX_ROWS = 3
        const val WORDS_PER_TEXT = 3
        const val UNIT_ODDS = 6
        const val PARTICIPANT_ODDS = 4
        const val ALL_UNITS_ODDS = 8
        const val IMPORTED_ODDS = 3
        const val QUERY_KINDS = 3
        const val CAP = 10_000
        const val PAGE = 60
        val LETTERS = listOf("q", "x", "z", "v")

        /** No filter, each origin alone, and both (the API's `origin` list). */
        val ORIGIN_FILTERS: List<Set<MemoOrigin>?> =
            listOf(null, setOf(MemoOrigin.NATIVE), setOf(MemoOrigin.IMPORTED), setOf(MemoOrigin.NATIVE, MemoOrigin.IMPORTED))
        val BASE_WORDS = listOf("albatross", "bramble", "cobalt", "dulcimer", "ember", "fjord", "gannet", "heron")
    }
}
