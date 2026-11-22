package com.memo.search

import com.memo.testsupport.TestDatabase
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * Register rows written straight into `search_decision` as its owner, for the plan gate and the cross-tenant checks (the
 * rows' sources are not needed there): per committee, 12 meetings a year from 2017, 15 decided items each, two thirds of
 * them numbered resolutions, every 20th item restricted to one person, every 25th with a hidden title, every 30th in a
 * members-only section, every 40th confidential (rank 2), and [needles] rows carrying the rare word `zirconium`.
 * Committed per committee, then analysed (the test-infra lesson of #225).
 */
object DecisionVolume {
    const val ITEMS_PER_MEETING = 15
    const val NEEDLE = "zirconium"

    /** One committee's [meetings] meetings for [tenant]; returns its item ids in insertion order (for recusals). */
    fun committee(
        tenant: UUID,
        forum: UUID,
        forumType: UUID,
        code: String,
        meetings: Int,
        restrictedTo: UUID,
        needles: Int = 0,
    ): List<UUID> =
        owner(tenant) { c ->
            c
                .prepareStatement(
                    """
                    insert into search_decision (
                        tenant_id, decision_id, forum_id, forum_type_id, meeting_id, meeting_label, meeting_date, item_id,
                        item_parent_id, item_number, classification_rank, item_restricted_to, item_title_hidden,
                        item_members_only, title, outcome_kind, outcome_label, is_resolution, resolution_id,
                        resolution_number, resolution_state, number_period, number_sequence, ref_norm, ref_tail, state,
                        decided_at, entry_seq, decision_text, conditions, has_dissent, vote_mode, ratification_required,
                        memo_number, memo_ref_norm, prior_deferrals, body, updated_at)
                    select ?::uuid, gen_random_uuid(), ?::uuid, ?::uuid, m.id, ? || ' ' || (m.n % 12 + 1) || '/' || m.y, m.d,
                           gen_random_uuid(), null, (i.n + 1)::text, CASE WHEN g % 40 = 0 THEN 2 ELSE 0 END,
                           CASE WHEN g % 20 = 0 THEN array[?::uuid] END, g % 25 = 0, g % 30 = 0,
                           CASE WHEN g < ? THEN 'Zirconium supply contract ' ELSE (array['Credit proposal', 'Risk appetite',
                               'Liquidity policy', 'Treasury limits', 'Compliance report'])[g % 5 + 1] || ' ' END || g,
                           (array['APPROVED', 'APPROVED_WITH_CONDITIONS', 'NOTED', 'DEFERRED', 'REJECTED'])[g % 5 + 1],
                           'Outcome ' || g % 5, g % 3 <> 2,
                           CASE WHEN g % 3 <> 2 THEN gen_random_uuid() END,
                           CASE WHEN g % 3 <> 2 THEN ? || '/' || m.y || '/' || lpad((g / 3 * 2 + g % 3 + 1)::text, 4, '0') END,
                           CASE WHEN g % 3 <> 2 THEN 'IN_FORCE' END,
                           CASE WHEN g % 3 <> 2 THEN m.y::text END,
                           CASE WHEN g % 3 <> 2 THEN g / 3 * 2 + g % 3 + 1 END,
                           CASE WHEN g % 3 <> 2 THEN ? || '-' || m.y || '-' || lpad((g / 3 * 2 + g % 3 + 1)::text, 4, '0') END,
                           CASE WHEN g % 3 <> 2 THEN (g / 3 * 2 + g % 3 + 1)::text END,
                           'FINAL', m.d + time '10:00' + i.n * interval '10 minutes', 1,
                           'It was resolved on item ' || g, '{}', g % 17 = 0, 'VOTE', false,
                           'CP/' || m.y || '/' || lpad(g::text, 5, '0'), 'CP-' || m.y || '-' || lpad(g::text, 5, '0'), 0,
                           'Outcome ' || g % 5 || E'\nIt was resolved on item ' || g, now()
                    from generate_series(0, ? - 1) AS mm(n)
                    cross join lateral (SELECT gen_random_uuid() AS id, mm.n AS n, 2017 + mm.n / 12 AS y,
                                               date '2017-01-15' + mm.n * 30 AS d) m
                    cross join generate_series(0, ? - 1) AS i(n)
                    cross join lateral (SELECT m.n * ? + i.n AS g) gg
                    returning item_id
                    """.trimIndent(),
                ).use { st ->
                    var p = 1
                    st.setString(p++, tenant.toString())
                    st.setString(p++, forum.toString())
                    st.setString(p++, forumType.toString())
                    st.setString(p++, code)
                    st.setString(p++, restrictedTo.toString())
                    st.setInt(p++, needles)
                    st.setString(p++, code)
                    st.setString(p++, code)
                    st.setInt(p++, meetings)
                    st.setInt(p++, ITEMS_PER_MEETING)
                    st.setInt(p, ITEMS_PER_MEETING)
                    st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getObject(1, UUID::class.java)) } }
                }
        }

    /** Analyses the table, so plans are made on its real statistics. */
    fun analyse() {
        DriverManager.getConnection(TestDatabase.jdbcUrl, "memo_migrator", TestDatabase.migratorPassword).use { c ->
            c.createStatement().use { it.execute("analyze search_decision") }
        }
    }

    /** A committed transaction as the schema owner, bound to [tenant] (transaction-local, as the application binds it). */
    fun <T> owner(
        tenant: UUID,
        block: (Connection) -> T,
    ): T =
        DriverManager.getConnection(TestDatabase.jdbcUrl, "memo_migrator", TestDatabase.migratorPassword).use { c ->
            c.autoCommit = false
            c.createStatement().use { it.execute("set local lock_timeout = '30s'") }
            c.createStatement().use { it.execute("set local statement_timeout = '10min'") }
            c.prepareStatement("select set_config('app.tenant_id', ?, true)").use {
                it.setString(1, tenant.toString())
                it.execute()
            }
            val result = block(c)
            c.commit()
            result
        }
}
