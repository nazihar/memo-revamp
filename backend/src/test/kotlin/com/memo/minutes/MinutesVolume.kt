package com.memo.minutes

import com.memo.meeting.VolumePeople
import com.memo.testsupport.TestDatabase
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * The minutes' rows of design E06 §8 (test plan T9), written over [com.memo.meeting.MeetingVolume]'s meetings in the same
 * tenant: on every held meeting the decisions of its nine submissions and the first eight consent items (about 55,000,
 * with 70,000 entries: three in ten corrected once), their notes, resolution numbers in agenda order per committee and
 * year (about 30,000; a correction to "Noted" voids one in twenty), the minutes of each held meeting with their history,
 * the draft's file, two or three copies (about 7,000), six comments once circulated (about 20,000), requests for comments,
 * reminders, and for approved minutes the chair's approval (one chain per committee), the record's metadata and the
 * meeting moved to MINUTES_APPROVED.
 *
 * The meetings that the tests measure:
 *  - the **held focal meeting** (the first committee's last held one, 50 items, 39 people): every item decided but one
 *    submission and item 5 of "For noting", its minutes circulated (comments until three days from now) with comments;
 *  - the **focal meeting** (the first committee's next one, 60 items), moved to have started 40 minutes ago (the test
 *    starts it): its items decided but the consent section and five of "For decision", no numbers yet;
 *  - the second committee numbers without a reset (`ALL`): about 1,800 numbers in one period for the register check;
 *  - the committees' last held meetings rotate through DRAFT, CHAIR_REVIEW, CIRCULATED (by circulation past the deadline
 *    on four committees, closing today on three) and AWAITING_CHAIR (five days), so each step of the sweep has work.
 *
 * Written as the schema owner on one connection with the tenant set in each transaction (the forced policies apply), in
 * stages: each stage commits and the tables it wrote are analysed before the next joins them or checks foreign keys
 * against them (an ANALYZE of a table counted while its rows were uncommitted misleads every later plan: the #225
 * stall). Ids are deterministic (`md5`).
 */
class MinutesVolume(
    private val tenant: UUID,
    private val people: VolumePeople,
    private val focal: UUID,
    private val held: UUID,
    /** The minutes policy as a row stores it: the defaults (AT_NEXT_MEETING), and approved by circulation. */
    private val policy: String,
    private val byCirculation: String,
) {
    /** Writes the rows, stage by stage; answers the time each stage took. */
    fun generate(): Map<String, Long> {
        val times = linkedMapOf<String, Long>()
        DriverManager.getConnection(TestDatabase.jdbcUrl, "memo_migrator", TestDatabase.migratorPassword).use { c ->
            c.autoCommit = false
            c.bind()
            stage(c, times, "plan", plan(), emptyList())
            stage(c, times, "decisions", decisions(), listOf("minutes_decision", "meeting_quorum_snapshot"))
            stage(c, times, "entries and notes", entries(), listOf("minutes_decision_entry", "minutes_item_note"))
            stage(c, times, "counters", counters(), listOf("minutes_resolution_counter"))
            stage(c, times, "resolutions", resolutions(), listOf("minutes_resolution"))
            stage(c, times, "draft files", draftFiles(), listOf("files_file", "files_version"))
            stage(c, times, "minutes", minutes(), listOf("minutes", "meeting"))
            stage(c, times, "approvals", approvals(), listOf("minutes_approval"))
            stage(c, times, "history, records, copies, comments", rest(), TABLES)
        }
        return times
    }

    private fun stage(
        c: Connection,
        times: MutableMap<String, Long>,
        name: String,
        statements: List<String>,
        written: List<String>,
    ) {
        val started = System.nanoTime()
        statements.forEach { sql -> c.createStatement().use { it.execute(sql) } }
        c.commit()
        c.bind()
        if (written.isNotEmpty()) c.createStatement().use { it.execute("analyze ${written.distinct().joinToString()}") }
        c.commit()
        c.bind()
        times[name] = (System.nanoTime() - started) / NANOS_PER_MILLI
    }

    /** The settings of each transaction: they are local, so each stage sets them again. */
    private fun Connection.bind() {
        createStatement().use { it.execute("set local lock_timeout = '60s'") }
        createStatement().use { it.execute("set local statement_timeout = '30min'") }
        prepareStatement("select set_config('app.tenant_id', ?, true)").use {
            it.setString(1, tenant.toString())
            it.execute()
        }
    }

    /**
     * The session's plan (temp tables live as long as the connection, across the stages' commits): the committees, the held
     * meetings and the focal one with the minutes' stage each will be at, their items from the first revision with their
     * agenda order, and the decisions to write with their outcomes. The focal meeting is moved first.
     */
    private fun plan(): List<String> =
        listOf(
            """
            update meeting set starts_at = date_trunc('minute', now()) - interval '40 minutes',
                               ends_at = date_trunc('minute', now()) + interval '140 minutes'
            where id = '$focal'
            """,
            // The people seated through the services (the chair, the director, the secretary) took their seats today; at the
            // first committee's past meetings they were in office already (a quorum needs the chair or a vice-chair).
            """
            update forum_member set starts_on = date '2010-01-01', version = version + 1
            where forum_id = (select forum_id from meeting where id = '$held')
              and user_id in ('${people.chair}', '${people.director}', '${people.secretary}')
            """,
            // The chair, who sent apologies to the held focal meeting in E05's replies, came after all (five minutes in): a
            // decision recorded on it after the meeting needs the chair present to be quorate.
            """
            insert into meeting_attendance_event (id, tenant_id, meeting_id, user_id, kind, mode, at, item_id, source, correction,
                                                  recorded_by, recorded_at)
            select md5('mnchair' || id)::uuid, tenant_id, id, '${people.chair}', 'ARRIVED', 'IN_PERSON', starts_at + interval '5 minutes',
                   null, 'RECORDED', false, '${people.secretary}', starts_at + interval '5 minutes'
            from meeting where id = '$held'
            """,
            """
            update meeting_attendance a set state = 'PRESENT', mode = 'IN_PERSON', first_arrived_at = m.starts_at + interval '5 minutes',
                                            last_left_at = null, version = a.version + 1
            from meeting m where m.id = '$held' and a.tenant_id = m.tenant_id and a.meeting_id = m.id and a.user_id = '${people.chair}'
            """,
            // The focal consent section's items are open to the secretary, who decides it in one action (one restricted to
            // the chair would refuse the section to them: 404).
            """
            update meeting_item set restricted_to = null where meeting_id = '$focal' and section_id = md5('mvs' || '$focal' || '-2')::uuid
            """,
            """
            update meeting_revision_item set restricted_to = null
            where section_id = md5('mvs' || '$focal' || '-2')::uuid
              and revision_id in (select id from meeting_revision where meeting_id = '$focal')
            """,
            "create temp table mn_forum as select substr(code, 2)::int as f, id, code, forum_type_id from forum where code ~ '^C[0-9]+$'",
            """
            create temp table mn_meeting as
            select m.id, m.forum_id, fo.f, fo.code, fo.forum_type_id, m.starts_at, m.ends_at, m.number as k,
                   m.id = '$focal' as focal, m.id = '$held' as held_focal,
                   row_number() over (partition by m.forum_id, m.status order by m.starts_at desc) as rank
            from meeting m join mn_forum fo on fo.id = m.forum_id
            where m.status = 'HELD' or m.id = '$focal'
            """,
            """
            alter table mn_meeting add column mstate text, add column by_circulation boolean
            """,
            """
            update mn_meeting set
                mstate = case when focal then null
                              when rank = 1 then (array['DRAFT', 'CHAIR_REVIEW', 'CIRCULATED', 'AWAITING_CHAIR'])[1 + (f + 1) % 4]
                              when rank = 2 and f % 3 = 0 then 'AWAITING_CHAIR'
                              else 'APPROVED' end,
                by_circulation = rank = 1 and f % 8 = 5
            """,
            "analyze mn_meeting",
        ) + planItems()

    /** The plan's items from the first revision with their agenda order, and the decisions to write with their outcomes. */
    private fun planItems(): List<String> =
        listOf(
            """
            create temp table mn_item as
            select mm.id as meeting_id, mm.forum_id, mm.f, mm.code, mm.forum_type_id, mm.starts_at, mm.ends_at, mm.focal,
                   mm.held_focal, mm.mstate, ri.item_id, ri.kind, ri.submission_id, ri.memo_id, ri.purpose, ri.position + 1 as n,
                   rs.position as j, row_number() over (partition by ri.revision_id, ri.section_id order by ri.position) - 1 as i
            from mn_meeting mm
            join meeting_revision r on r.tenant_id = '$tenant' and r.meeting_id = mm.id and r.number = 1
            join meeting_revision_item ri on ri.tenant_id = '$tenant' and ri.revision_id = r.id
            join meeting_revision_section rs on rs.tenant_id = '$tenant' and rs.revision_id = r.id and rs.section_id = ri.section_id
            where ri.kind <> 'BREAK'
            """,
            "create index on mn_item (meeting_id)",
            "analyze mn_item",
            """
            create temp table mn_recused as
            select item_id, array_agg(user_id order by user_id) as users
            from meeting_declaration where state = 'RECUSED' group by item_id
            """,
            "create index on mn_recused (item_id)",
            "analyze mn_recused",
            // Which items are decided, and how: the submissions approved, with conditions, rejected or deferred; consent
            // items noted (the second committee approves them); other items approved when for decision, else noted.
            """
            create temp table mn_decision as
            select d.*, case when d.n % 10 in (1, 4, 7) then case when d.n % 20 = 11 and d.kind1 = 'APPROVED' then 'NOTED'
                                                               else d.kind1 end end as kind2
            from (
                select it.*, md5('mnd' || it.item_id)::uuid as decision_id,
                       case when it.kind = 'SUBMISSION' then
                                case when it.n % 10 <= 5 then 'APPROVED' when it.n % 10 <= 7 then 'APPROVED_WITH_CONDITIONS'
                                     when it.n % 10 = 8 then 'REJECTED' else 'DEFERRED' end
                            when it.j = 2 and it.f = 2 then 'APPROVED'
                            when it.j <> 2 and it.purpose = 'DECISION' then 'APPROVED'
                            else 'NOTED' end as kind1,
                       case when it.focal then it.starts_at + it.n * interval '30 seconds'
                            else it.starts_at + it.n * interval '2 minutes' end as decided_at,
                       coalesce(s.id, md5('mnqs' || it.item_id)::uuid) as snapshot_id, s.id is null as new_snapshot
                from mn_item it
                left join meeting_quorum_snapshot s on s.tenant_id = '$tenant' and s.id = md5('mvqs' || it.item_id)::uuid
                where case when it.focal then it.j in (0, 1, 3, 4) and not (it.j = 1 and it.i >= 7)
                           when it.held_focal then not ((it.j = 1 and it.i = 8) or (it.j = 4 and it.i = 5))
                           else (it.j = 1 and it.kind = 'SUBMISSION') or (it.j = 2 and it.i < 8) end
            ) d
            """,
            "create index on mn_decision (decision_id)",
            "analyze mn_decision",
        )

    /** The decisions, with a snapshot for each that has none from the meeting volume. */
    private fun decisions(): List<String> =
        listOf(
            """
            insert into meeting_quorum_snapshot (id, tenant_id, meeting_id, item_id, at, type_id, type_version, inputs, result,
                                                 quorate, override_reason, ratification_required, inputs_sha256, taken_by, taken_at)
            select snapshot_id, '$tenant'::uuid, meeting_id, item_id, decided_at, forum_type_id, 1, '{"volume": true}'::jsonb,
                   '{"volume": true}'::jsonb, true, null, false, md5('a' || item_id) || md5('b' || item_id), '${people.secretary}',
                   decided_at
            from mn_decision where new_snapshot
            """,
            """
            insert into minutes_decision (id, tenant_id, meeting_id, item_id, forum_id, submission_id, memo_id, current_seq,
                                          released_at, released_kind, final_at, version)
            select decision_id, '$tenant'::uuid, meeting_id, item_id, forum_id,
                   case when kind = 'SUBMISSION' then submission_id end, case when kind = 'SUBMISSION' then memo_id end,
                   case when kind2 is null then 1 else 2 end,
                   case when kind = 'SUBMISSION' and not focal then ends_at + interval '5 minutes' end,
                   case when kind = 'SUBMISSION' and not focal then coalesce(kind2, kind1) end,
                   case when mstate = 'APPROVED' then $APPROVED_AT end,
                   case when kind2 is null then 0 else 1 end
                       + case when kind = 'SUBMISSION' and not focal then 1 else 0 end
                       + case when mstate = 'APPROVED' then 1 else 0 end
            from mn_decision
            """,
        )

    /** Each decision's first entry and, for three in ten, its correction; the secretary's notes on every other item. */
    private fun entries(): List<String> {
        val entry = { kind: String, seq: Int ->
            """
            select '$tenant'::uuid, decision_id, $seq, $kind, ${label(kind)}, $kind in ('APPROVED', 'APPROVED_WITH_CONDITIONS', 'REJECTED'),
                   case when $kind in ('APPROVED', 'APPROVED_WITH_CONDITIONS', 'REJECTED')
                             then 'It was RESOLVED that the proposal of item ' || n || ' be ' || lower(${label(kind)})
                                  || case when $seq = 2 then ', as corrected.' else '.' end
                        when $kind = 'DEFERRED' then 'Deferred to the next meeting.' else 'Noted.' end,
                   case when $kind = 'APPROVED_WITH_CONDITIONS'
                             then '["Security perfected before disbursement.", "A quarterly review of the covenants."]'::jsonb
                        else '[]'::jsonb end,
                   case when $kind = 'APPROVED_WITH_CONDITIONS' then (starts_at + interval '1 year')::date end,
                   case when $kind = 'DEFERRED' then '{"next": true}'::jsonb end, null::uuid, null::text,
                   case when n % 4 = 2 then 'VOTE' else 'CONSENSUS' end,
                   case when n % 4 = 2 then 7 end, case when n % 4 = 2 then 2 end, case when n % 4 = 2 then 1 end,
                   case when n % 4 = 2 then jsonb_build_array(jsonb_build_object('userId', md5('mvu' || f || '-' || (1 + n % 10))::uuid::text,
                                                                                 'reason', 'Concerned about the concentration risk'))
                        else '[]'::jsonb end,
                   case when n % 4 = 2 then array[md5('mvu' || f || '-' || (1 + (n + 3) % 10))::uuid] else '{}'::uuid[] end,
                   coalesce(r.users, '{}'::uuid[]), snapshot_id, false, null::uuid, '{}'::uuid[], decided_at,
                   case when j = 2 and $seq = 1 then md5('mnc' || meeting_id)::uuid end, $seq > 1,
                   case when $seq > 1 then 'The wording was corrected at the secretary''s request.' end, '${people.secretary}'::uuid,
                   case when $seq = 1 then decided_at + interval '1 minute' else decided_at + interval '2 minutes' end
            from mn_decision d left join mn_recused r on r.item_id = d.item_id
            """
        }
        return listOf(
            """
            insert into minutes_decision_entry (tenant_id, decision_id, seq, outcome_kind, outcome_label, resolution, text, conditions,
                                                valid_until, defer_to, refer_to_forum_id, refer_reason, vote_mode, votes_for,
                                                votes_against, votes_abstain, dissent, abstentions, took_no_part, snapshot_id,
                                                ratification_required, ratifies_decision_id, approves_minutes_ids, decided_at,
                                                consent_batch_id, correction, reason, recorded_by, recorded_at)
            ${entry("kind1", 1)}
            union all
            ${entry("kind2", 2)} where kind2 is not null
            """,
            """
            insert into minutes_item_note (tenant_id, meeting_id, item_id, seq, text, saved_by, saved_at)
            select '$tenant'::uuid, meeting_id, item_id, s, repeat('The committee discussed the paper and the risks it raised. ', 4 + s),
                   '${people.secretary}', decided_at + s * interval '10 minutes'
            from mn_decision, generate_series(1, case when n % 6 = 0 then 2 else 1 end) s
            where n % 2 = 0
            """,
        )
    }

    /** The numbers to give: each resolution decision of a held meeting (in force or voided by its correction). */
    private fun counters(): List<String> =
        listOf(
            """
            create temp table mn_resolution as
            select x.*, row_number() over (partition by x.forum_id, x.period order by x.starts_at, x.n) as seq
            from (
                select d.decision_id, d.forum_id, d.f, d.code, d.meeting_id, d.n, d.starts_at, d.ends_at,
                       case when d.f = 2 then 'ALL' else extract(year from d.starts_at at time zone 'Asia/Dhaka')::int::text end as period,
                       coalesce(d.kind2, d.kind1) not in ('APPROVED', 'APPROVED_WITH_CONDITIONS', 'REJECTED') as voided
                from mn_decision d
                where d.kind1 in ('APPROVED', 'APPROVED_WITH_CONDITIONS', 'REJECTED') and not d.focal
            ) x
            """,
            "analyze mn_resolution",
            """
            insert into minutes_resolution_counter (tenant_id, forum_id, period, last_number, first_number, started_by_admin)
            select '$tenant'::uuid, forum_id, period, max(seq), 1, false from mn_resolution group by forum_id, period
            """,
        )

    private fun resolutions(): List<String> =
        listOf(
            """
            insert into minutes_resolution (id, tenant_id, decision_id, forum_id, meeting_id, period, sequence, number, numbered_at, state,
                                            void_reason, voided_at, voided_by_entry_seq)
            select md5('mnr' || decision_id)::uuid, '$tenant'::uuid, decision_id, forum_id, meeting_id, period, seq,
                   code || '/' || case when period = 'ALL' then '' else period || '/' end
                       || case when seq < 1000 then lpad(seq::text, 3, '0') else seq::text end,
                   ends_at + interval '1 minute', case when voided then 'VOID' else 'IN_FORCE' end,
                   case when voided then 'The decision was corrected to Noted.' end,
                   case when voided then ends_at + interval '1 day' end, case when voided then 2 end
            from mn_resolution
            """,
        )

    /** The draft of each held meeting's minutes: a .docx file with one version (the copies and the record refer to it). */
    private fun draftFiles(): List<String> {
        val key = "'t/$tenant/' || substr(translate(md5('mnkey' || id), '0189', 'wxyz'), 1, 26)"
        return listOf(
            """
            insert into files_file (id, tenant_id, purpose, owner_id, version_count, created_by, created_at)
            select md5('mnf' || id)::uuid, '$tenant'::uuid, 'minutes.draft', id::text, 0, '${people.secretary}', ends_at + interval '1 day'
            from mn_meeting where not focal
            """,
            """
            insert into files_version (id, tenant_id, file_id, number, original_name, media_type, size_bytes, sha256, bucket, object_key,
                                       ciphertext_bytes, keyset_key_id, scan_engine, pdf_pages, lock_state, uploaded_by, uploaded_at)
            select md5('mnv' || id)::uuid, '$tenant'::uuid, md5('mnf' || id)::uuid, 1, 'minutes.docx', '$DOCX', 80000,
                   decode(md5('a' || id) || md5('b' || id), 'hex'), 'work', $key, 80100, 1, 'test', null, 'NONE',
                   '${people.secretary}', ends_at + interval '1 day'
            from mn_meeting where not focal
            """,
            """
            update files_file f set current_version_id = md5('mnv' || m.id)::uuid, version_count = 1
            from mn_meeting m where not m.focal and f.tenant_id = '$tenant'::uuid and f.id = md5('mnf' || m.id)::uuid
            """,
        )
    }

    /**
     * The minutes of each held meeting at its stage, with the times a real path would have given them; approved minutes'
     * meetings move to MINUTES_APPROVED.
     */
    private fun minutes(): List<String> {
        val past = "mstate in ('CIRCULATED', 'AWAITING_CHAIR', 'APPROVED')"
        val reviewed = "mstate <> 'DRAFT'"
        val circulated = "least(now() - interval '2 days', ends_at + interval '3 days')"
        val until =
            "case when held_focal then now() + interval '3 days' when by_circulation then now() - interval '1 hour' " +
                "when rank = 1 and mstate = 'CIRCULATED' then now() + interval '3 hours' else $circulated + interval '7 days' end"
        return listOf(
            """
            insert into minutes (tenant_id, meeting_id, forum_id, policy, state, file_id, work_seq, work_kind, work_requested_by,
                                 work_requested_at, checked_version_id, structure_ok, structure_problems, checked_at, held_at,
                                 created_at, version, reviewer_id, reviewer_role, comments_until, circulated_at, awaiting_since,
                                 confirmed_approval_id, approved_at, copies_seq)
            select '$tenant'::uuid, id, forum_id, case when by_circulation then '$byCirculation' else '$policy' end::jsonb, mstate,
                   md5('mnf' || id)::uuid, 1, 'ALL', '${people.secretary}', ends_at + interval '1 day', md5('mnv' || id)::uuid, true,
                   '[]'::jsonb, ends_at + interval '1 day', ends_at, ends_at,
                   case mstate when 'DRAFT' then 2 when 'CHAIR_REVIEW' then 3 when 'CIRCULATED' then 5 else 7 end,
                   case when $reviewed then ${chair("f")} end, case when $reviewed then 'CHAIR' end,
                   case when $past then $until end, case when $past then $circulated end,
                   case when mstate = 'AWAITING_CHAIR' and rank = 1 then now() - interval '5 days'
                        when mstate in ('AWAITING_CHAIR', 'APPROVED') then $circulated + interval '7 days' end,
                   case when mstate = 'APPROVED' then md5('mna' || id)::uuid end, case when mstate = 'APPROVED' then $APPROVED_AT end,
                   case when $past then 2 else 0 end
            from mn_meeting where not focal
            """,
            """
            update meeting m set status = 'MINUTES_APPROVED'
            from mn_meeting mm where mm.mstate = 'APPROVED' and m.tenant_id = '$tenant'::uuid and m.id = mm.id
            """,
        )
    }

    /** The chair's confirmation of approved minutes, chained per committee in meeting order. */
    private fun approvals(): List<String> =
        listOf(
            """
            insert into minutes_approval (id, tenant_id, meeting_id, forum_id, kind, actor_id, role, authentication, client,
                                          docx_version_id, docx_sha256, previous_hash, record_hash, at)
            select md5('mna' || id)::uuid, '$tenant'::uuid, id, forum_id, 'CONFIRMATION', ${chair("f")}, 'CHAIR',
                   '{"method": "PASSWORD", "secondFactor": "TOTP", "stepUpRequired": true}'::jsonb,
                   '{"ip": "10.20.30.40", "userAgent": "volume"}'::jsonb, md5('mnv' || id)::uuid, md5('a' || id) || md5('b' || id),
                   lag(md5('h' || id) || md5('i' || id)) over (partition by forum_id order by starts_at),
                   md5('h' || id) || md5('i' || id), $APPROVED_AT
            from mn_meeting where mstate = 'APPROVED'
            """,
        )

    /** The minutes' history, then [copiesAndComments]. */
    private fun rest(): List<String> =
        listOf(
            """
            insert into minutes_event (id, tenant_id, meeting_id, kind, item_id, actor, on_behalf_by, how, note, file_version_id, sha256, at)
            select md5('mne' || m.id || e.kind)::uuid, '$tenant'::uuid, m.id, e.kind, null, e.actor, null, null, null,
                   case when e.kind = 'GENERATED' then md5('mnv' || m.id)::uuid end,
                   case when e.kind = 'GENERATED' then md5('a' || m.id) || md5('b' || m.id) end, m.ends_at + e.after
            from mn_meeting m,
                 lateral (values ('GENERATED', '${people.secretary}'::uuid, interval '1 day', 0),
                                 ('SENT_FOR_REVIEW', '${people.secretary}'::uuid, interval '2 days', 1),
                                 ('AGREED', ${chair("m.f")}, interval '2 days 4 hours', 2),
                                 ('CIRCULATED', '${people.secretary}'::uuid, interval '3 days', 2),
                                 ('CIRCULATION_ENDED', '${people.secretary}'::uuid, interval '10 days', 3),
                                 ('CONFIRMED', ${chair("m.f")}, interval '35 days', 4)) e(kind, actor, after, stage)
            where not m.focal
              and e.stage <= case m.mstate when 'DRAFT' then 0 when 'CHAIR_REVIEW' then 1 when 'CIRCULATED' then 2
                                           when 'AWAITING_CHAIR' then 3 else 4 end
            """,
        ) + copiesAndComments()

    /** The records of approved minutes, the copies, the comments, the requests for comments and the reminders. */
    private fun copiesAndComments(): List<String> {
        val circulated = "least(now() - interval '2 days', ends_at + interval '3 days')"
        val past = "mstate in ('CIRCULATED', 'AWAITING_CHAIR', 'APPROVED')"
        val firstItem = "md5('mvi' || id || '-1-0')::uuid"
        val recusedSet = "jsonb_build_array(jsonb_build_object('itemId', $firstItem::text, 'kind', 'RECUSED'))"
        val recusedKey = "encode(sha256(convert_to($firstItem::text || ':RECUSED', 'UTF8')), 'hex')"
        val fullKey = "encode(sha256(''::bytea), 'hex')"
        return listOf(
            """
            insert into minutes_record (id, tenant_id, meeting_id, forum_id, record_version, approval_id, docx_version_id, docx_sha256, state,
                                        file_id, file_version_id, sha256, size_bytes, pages, compliant, created_at, built_at, version)
            select md5('mnrec' || id)::uuid, '$tenant'::uuid, id, forum_id, 1, md5('mna' || id)::uuid, md5('mnv' || id)::uuid,
                   md5('a' || id) || md5('b' || id), 'READY', md5('mnrf' || id)::uuid, md5('mnrv' || id)::uuid,
                   md5('c' || id) || md5('d' || id), 400000, 24, true, $APPROVED_AT, $APPROVED_AT + interval '2 minutes', 1
            from mn_meeting where mstate = 'APPROVED'
            """,
            // A full copy of each draft once circulated, a recused reader's on every other one, and for approved minutes that
            // reader's approved copy of the record.
            """
            insert into minutes_copy (id, tenant_id, meeting_id, stage, redacted, set_key, source_version_id, state, file_id,
                                      file_version_id, sha256, created_at, built_at, version, record_version, compliant)
            select md5('mncp' || id || c.n)::uuid, '$tenant'::uuid, id, c.stage, c.redacted, c.set_key, md5('mnv' || id)::uuid, 'DONE',
                   md5('mncf' || id || c.n)::uuid, md5('mncv' || id || c.n)::uuid, md5('e' || id || c.n) || md5('f' || id || c.n),
                   $circulated, $circulated + interval '1 minute', 1, c.record_version, c.compliant
            from mn_meeting m,
                 lateral (values (1, 'DRAFT', '[]'::jsonb, $fullKey, null::int, null::boolean),
                                 (2, 'DRAFT', $recusedSet, $recusedKey, null, null),
                                 (3, 'APPROVED', $recusedSet, $recusedKey, 1, true)) c(n, stage, redacted, set_key, record_version, compliant)
            where not m.focal and $past and (c.n = 1 or (c.n = 2 and m.k % 2 = 0) or (c.n = 3 and m.mstate = 'APPROVED'))
            """,
            // Four members answer "No comments" and two suggest changes (answered once the minutes are approved).
            """
            insert into minutes_comment (id, tenant_id, meeting_id, user_id, item_id, kind, text, at, response, response_note,
                                         responded_by, responded_at)
            select md5('mncm' || id || u)::uuid, '$tenant'::uuid, id, md5('mvu' || f || '-' || u)::uuid,
                   case when u > 4 then $firstItem end, case when u > 4 then 'SUGGESTION' else 'NO_COMMENTS' end,
                   case when u > 4 then 'Please record the second condition as the committee worded it.' end,
                   $circulated + u * interval '3 hours',
                   case when u > 4 and mstate = 'APPROVED' then 'ADOPTED' end, null,
                   case when u > 4 and mstate = 'APPROVED' then '${people.secretary}'::uuid end,
                   case when u > 4 and mstate = 'APPROVED' then $circulated + interval '6 days' end
            from mn_meeting, generate_series(1, $COMMENTS) u
            where not focal and $past
            """,
            """
            insert into minutes_comment_request (id, tenant_id, meeting_id, user_id, item_ids, asked_by, asked_at)
            select md5('mncr' || id)::uuid, '$tenant'::uuid, id, md5('mvatt' || f)::uuid, array[$firstItem], '${people.secretary}',
                   $circulated + interval '1 hour'
            from mn_meeting where not focal and $past and k % 5 = 0
            """,
            """
            insert into minutes_reminder (tenant_id, meeting_id, kind, user_id, key, sent_at)
            select '$tenant'::uuid, id, 'DRAFT_OVERDUE', '${people.secretary}', 'once', ends_at + interval '14 days'
            from mn_meeting where not focal and mstate = 'APPROVED' and k % 4 = 0
            """,
        )
    }

    /** The committee's chair: the volume's chair for the first committee, a member for the others (they have no chair). */
    private fun chair(f: String) = "case when $f = 1 then '${people.chair}'::uuid else md5('mvu' || $f || '-1')::uuid end"

    private fun label(kind: String) =
        "case $kind when 'APPROVED' then 'Approved' when 'APPROVED_WITH_CONDITIONS' then 'Approved with conditions' " +
            "when 'REJECTED' then 'Rejected' when 'DEFERRED' then 'Deferred' else 'Noted' end"

    companion object {
        /** Comments per circulated minutes: four "No comments", two suggestions. */
        const val COMMENTS = 6
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

        /** When approved minutes were confirmed: five weeks after the meeting (never in the future). */
        private const val APPROVED_AT = "least(now() - interval '1 hour', starts_at + interval '35 days')"

        /** The tables written. */
        val TABLES =
            listOf(
                "minutes_decision",
                "minutes_decision_entry",
                "minutes_item_note",
                "minutes_resolution_counter",
                "minutes_resolution",
                "minutes",
                "minutes_event",
                "minutes_approval",
                "minutes_record",
                "minutes_copy",
                "minutes_comment",
                "minutes_comment_request",
                "minutes_reminder",
            )
    }
}
