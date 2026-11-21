package com.memo.search

import com.memo.testsupport.TestDatabase
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * The search volume of E05 T1 (test plan "V1"): [memos] memos in one tenant with their search rows, two notes per memo and
 * a document chunk of 200 words for every fifth memo. Words follow a skewed vocabulary (a few words are in most memos,
 * most words are rare), and needle words sit in 50 subjects, 50 notes and 50 chunks. Every twentieth memo is ranked 2
 * and every seventh 1; documents are sometimes ranked above their memo. Origin units cycle over [UNITS] units.
 *
 * Rows are written as the schema owner (the search tables grant nothing to the application role), with the tenant set so
 * the forced policies of the memo tables accept them. Memo rows are drafts: search rows carry the status mix themselves.
 *
 * Each stage commits, and the tables it wrote are analysed before the next stage reads them or checks foreign keys against
 * them. In one transaction, an autovacuum ANALYZE during the load (a full suite triggers one) counted none of the
 * uncommitted rows: `memo` read as 0 rows over thousands of pages, and the foreign-key check of every later row
 * (`tenant_id = $1 AND id = $2`) chose a `tenant_id`-only index and walked the tenant's whole volume. That was the CI stall
 * (E06 log, 2026-11-22). Committed rows are counted by every later ANALYZE, so the plans can no longer be misled.
 */
class SearchVolume(
    private val tenant: UUID,
    private val memos: Int,
    private val type: UUID,
    private val level: UUID,
    // Seeds of the generated ids: memo ids are a global key, so each test class uses its own.
    private val seed: String = "s",
) {
    /** The units, by index (origin unit of memo i is unit i % [UNITS]). */
    fun unit(index: Int): UUID = generated("${seed}u$index")

    /** The memo of index i (1-based). */
    fun memo(index: Int): UUID = generated("${seed}m$index")

    /** The id the generator wrote for [key]: PostgreSQL's `md5(seed)::uuid`, computed by PostgreSQL. */
    private fun generated(key: String): UUID =
        DriverManager.getConnection(TestDatabase.jdbcUrl, "memo_migrator", TestDatabase.migratorPassword).use { c ->
            c.prepareStatement("select md5(?)::uuid").use { st ->
                st.setString(1, key)
                st.executeQuery().use { rs ->
                    rs.next()
                    rs.getObject(1, UUID::class.java)
                }
            }
        }

    fun generate(readerRows: Map<UUID, Int>) {
        owner { c ->
            c.exec("select setseed(0.42)")
            c.exec(
                """
                create temp table words as
                select array[${VOCABULARY.joinToString(",") { "'$it'" }}] as w, ${VOCABULARY.size} as n
                """,
            )
            memos(c)
            notes(c)
            chunks(c)
            participants(c, readerRows)
        }
        // Searches are timed on vacuumed tables: GIN pending lists merged, visibility maps set, and no autovacuum of the
        // fresh volume running beside the measurement.
        DriverManager.getConnection(TestDatabase.jdbcUrl, "memo_migrator", TestDatabase.migratorPassword).use { c ->
            c.createStatement().use { it.execute("set statement_timeout = '${timeoutMinutes}min'") }
            c.createStatement().use { it.execute("vacuum (analyze) memo, memo_participant, search_memo, search_note, search_chunk") }
        }
    }

    private fun memos(c: Connection) {
        val subject =
            "(select string_agg(w[1 + floor(power(random(), 3) * n)::int], ' ') from words, generate_series(1, 5 + 0 * i))"
        c.exec(
            """
            create temp table gen as
            select i, md5('${seed}m' || i)::uuid as id,
                   $subject || case when i % ${memos / NEEDLES} = 0 then ' $NEEDLE_SUBJECT' else '' end as subject,
                   (select string_agg(w[1 + floor(power(random(), 2) * n)::int], ' ') from words, generate_series(1, 8 + 0 * i)) as fields,
                   md5('${seed}u' || (i % $UNITS))::uuid as unit,
                   case when i % 20 = 0 then 2 when i % 7 = 0 then 1 else 0 end as rank,
                   (array['IN_REVIEW', 'APPROVED', 'RETURNED', 'DRAFT', 'REJECTED'])[1 + i % 5] as status,
                   (i % 1000) * 1000.00 as amount,
                   now() - (i || ' minutes')::interval as at
            from generate_series(1, $memos) i
            """,
        )
        c.exec(
            """
            insert into memo (id, tenant_id, reference, memo_type_id, subject, classification_id, classification_rank,
                              initiator_id, origin_unit_id, status, current_round, created_at, updated_at)
            select id, '$tenant', 'M-2026-' || lpad(i::text, 7, '0'), '$type', left(subject, 300), '$level', 0,
                   md5('${seed}i' || (i % 500))::uuid, unit, 'DRAFT', 0, at, at
            from gen
            """,
        )
        c.commitAndAnalyse("memo")
        c.exec(
            """
            insert into search_memo (tenant_id, memo_id, classification_rank, origin_unit_id, status, type_id, initiator_id,
                                     amount, created_at, updated_at, ref_norm, ref_tail, subject, fields_text)
            select '$tenant', id, rank, unit, status, '$type', md5('${seed}i' || (i % 500))::uuid, amount, at, at,
                   'M-2026-' || lpad(i::text, 7, '0'), i::text, left(subject, 300), fields
            from gen
            """,
        )
        c.exec(
            """
            insert into memo_participant (tenant_id, memo_id, user_id, kind, created_at)
            select '$tenant', id, md5('${seed}i' || (i % 500))::uuid, 'INITIATOR', at from gen
            """,
        )
        c.commitAndAnalyse("search_memo", "memo_participant")
    }

    private fun notes(c: Connection) {
        val notes = memos * 2
        // The text is made once into a temp table and analysed before the join: joined directly, a plan built on stale
        // statistics re-ran the per-row text for every memo (the CI stall, E06 log "CI stall").
        c.exec(
            """
            create temp table note_gen as
            select md5('${seed}n' || j)::uuid as note_id, md5('${seed}m' || (1 + j % $memos))::uuid as memo_id,
                   (select string_agg(w[1 + floor(power(random(), 3) * n)::int], ' ') from words, generate_series(1, 25 + 0 * j))
                       || case when j % ${notes / NEEDLES} = 0 then ' $NEEDLE_NOTE' else '' end as body
            from generate_series(1, $notes) j
            """,
        )
        c.exec("analyze note_gen")
        c.exec(
            """
            insert into search_note (tenant_id, note_id, memo_id, classification_rank, origin_unit_id, status, type_id,
                                     memo_created_at, amount, body)
            select '$tenant', g.note_id, sm.memo_id, sm.classification_rank, sm.origin_unit_id, sm.status, sm.type_id,
                   sm.created_at, sm.amount, g.body
            from note_gen g
            join search_memo sm on sm.tenant_id = '$tenant' and sm.memo_id = g.memo_id
            """,
        )
        c.exec("drop table note_gen")
        c.commitAndAnalyse("search_note")
    }

    private fun chunks(c: Connection) {
        val chunks = memos / 5
        c.exec(
            """
            create temp table chunk_gen as
            select md5('${seed}v' || j)::uuid as v, md5('${seed}m' || (j * 5))::uuid as m,
                   case when j % 10 = 0 then 2 else 0 end as r,
                   (select string_agg(w[1 + floor(power(random(), 2) * n)::int], ' ') from words, generate_series(1, 200 + 0 * j))
                       || case when j % ${chunks / NEEDLES} = 0 then ' $NEEDLE_DOCUMENT' else '' end as b
            from generate_series(1, $chunks) j
            """,
        )
        c.exec("analyze chunk_gen")
        c.exec(
            """
            insert into search_chunk (tenant_id, file_id, file_version_id, chunk, memo_id, file_rank, classification_rank, origin_unit_id,
                                      status, type_id, memo_created_at, amount, body, tsv)
            select '$tenant', md5('${seed}f' || v)::uuid, v, 0, m, r, greatest(r, sm.classification_rank), sm.origin_unit_id,
                   sm.status, sm.type_id, sm.created_at, sm.amount, b, to_tsvector('english', b)
            from chunk_gen s
            join search_memo sm on sm.tenant_id = '$tenant' and sm.memo_id = s.m
            """,
        )
        c.exec("drop table chunk_gen")
        c.commitAndAnalyse("search_chunk")
    }

    /** Copies for each reader: [readerRows] rows spread evenly over the volume. */
    private fun participants(
        c: Connection,
        readerRows: Map<UUID, Int>,
    ) {
        readerRows.forEach { (reader, rows) ->
            c.exec(
                """
                insert into memo_participant (tenant_id, memo_id, user_id, kind, granted_by, created_at)
                select '$tenant', md5('${seed}m' || (1 + (k * ${memos / rows.coerceAtLeast(1)}) % $memos))::uuid, '$reader', 'COPY',
                       md5('${seed}i1')::uuid, now()
                from generate_series(1, $rows) k
                on conflict do nothing
                """,
            )
        }
        c.commitAndAnalyse("memo_participant")
    }

    /**
     * Transactions as the schema owner, bound to the tenant (transaction-local, as the application binds it): one per
     * stage, ended by [commitAndAnalyse].
     */
    private fun owner(block: (Connection) -> Unit) =
        DriverManager.getConnection(TestDatabase.jdbcUrl, "memo_migrator", TestDatabase.migratorPassword).use { c ->
            c.autoCommit = false
            c.bind()
            block(c)
            c.commit()
        }

    private val timeoutMinutes get() = if (memos > SUITE_SCALE) VOLUME_TIMEOUT_MINUTES else SUITE_TIMEOUT_MINUTES

    /** The settings of each transaction: they are local, so every stage sets them again. */
    private fun Connection.bind() {
        // A lock wait or a runaway plan fails the test (with its statement) instead of stalling the whole suite.
        createStatement().use { it.execute("set local lock_timeout = '30s'") }
        createStatement().use { it.execute("set local statement_timeout = '${timeoutMinutes}min'") }
        prepareStatement("select set_config('app.tenant_id', ?, true)").use {
            it.setString(1, tenant.toString())
            it.execute()
        }
    }

    /**
     * Commits a stage and analyses the [tables] it wrote, in the next stage's transaction: from here on every ANALYZE, ours
     * or autovacuum's, counts these rows, so the next stage's joins and foreign-key checks are planned on their real size.
     */
    private fun Connection.commitAndAnalyse(vararg tables: String) {
        commit()
        bind()
        exec("analyze ${tables.joinToString()}")
    }

    /**
     * Runs [sql]; an insert's plan is first written to `build/test-stalls/` (uploaded when CI fails), because a stall
     * here ran on CPU for many minutes in a full suite and never alone, and a temp table's plan cannot be read from
     * another session.
     */
    private fun Connection.exec(sql: String) {
        if (sql.trimStart().startsWith("insert", ignoreCase = true)) {
            val plan =
                createStatement().use { st ->
                    st.executeQuery("explain $sql").use { rows -> buildString { while (rows.next()) appendLine(rows.getString(1)) } }
                }
            runCatching {
                val dir = Path.of("build", "test-stalls").also(Files::createDirectories)
                val text = "${sql.trim().lineSequence().first()} …\n$plan\n"
                Files.writeString(dir.resolve("search-volume-plans.txt"), text, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            }
        }
        createStatement().use { it.execute(sql) }
    }

    companion object {
        /** The suite's volume; above it (the weekly V1 run) statements may take longer. */
        private const val SUITE_SCALE = 100_000
        private const val SUITE_TIMEOUT_MINUTES = 8
        private const val VOLUME_TIMEOUT_MINUTES = 60
        const val UNITS = 20
        const val NEEDLES = 50
        const val NEEDLE_SUBJECT = "zephyrine"
        const val NEEDLE_NOTE = "quillwort"
        const val NEEDLE_DOCUMENT = "marigoldia"

        fun uuid(seed: String): UUID = UUID.nameUUIDFromBytes(seed.toByteArray())

        /** Most frequent first: word k is picked with probability falling steeply in k. */
        val VOCABULARY =
            listOf(
                "loan",
                "branch",
                "credit",
                "approval",
                "customer",
                "account",
                "facility",
                "renewal",
                "limit",
                "review",
                "office",
                "lease",
                "budget",
                "proposal",
                "payment",
                "vendor",
                "contract",
                "policy",
                "risk",
                "audit",
                "schedule",
                "rent",
                "generator",
                "replacement",
                "procurement",
                "salary",
                "transfer",
                "staff",
                "training",
                "security",
                "deposit",
                "interest",
                "rate",
                "collateral",
                "guarantee",
                "mortgage",
                "valuation",
                "insurance",
                "compliance",
                "report",
                "quarter",
                "annual",
                "dividend",
                "capital",
                "reserve",
                "liquidity",
                "treasury",
                "exchange",
                "remittance",
                "export",
                "import",
                "letter",
                "shipment",
                "invoice",
                "tender",
                "quotation",
                "furniture",
                "vehicle",
                "fuel",
                "maintenance",
                "repair",
                "building",
                "floor",
                "electricity",
                "water",
                "internet",
                "software",
                "license",
                "server",
                "network",
                "printer",
                "stationery",
                "courier",
                "travel",
                "allowance",
                "overtime",
                "bonus",
                "promotion",
                "recruitment",
                "retirement",
                "pension",
                "medical",
                "agriculture",
                "fisheries",
                "textile",
                "garment",
                "pharmaceutical",
                "cement",
                "steel",
                "rice",
                "jute",
                "tea",
                "poultry",
                "dairy",
                "tannery",
                "shipping",
                "aviation",
                "telecom",
                "hospital",
                "school",
                "university",
                "charity",
                "donation",
                "sponsorship",
                "advertisement",
                "campaign",
                "marketing",
                "survey",
                "complaint",
                "dispute",
                "litigation",
                "settlement",
                "waiver",
                "write-off",
                "provision",
                "restructuring",
                "rescheduling",
                "moratorium",
                "subsidy",
                "refinance",
                "syndication",
                "consortium",
                "debenture",
                "bond",
                "sukuk",
            )
    }
}
