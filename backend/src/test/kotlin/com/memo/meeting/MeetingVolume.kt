package com.memo.meeting

import com.memo.common.tenant.TenantDsl
import com.memo.testsupport.TestDatabase
import java.sql.DriverManager
import java.util.UUID

/** The people of the volume who sign in: real users, seated through the forum services before the rows are written. */
data class VolumePeople(
    val admin: UUID,
    /** The secretary of every committee. */
    val secretary: UUID,
    /** A member of the first three committees. */
    val director: UUID,
    /** The chair of the first committee. */
    val chair: UUID,
)

/**
 * The meeting volume of design E03 §8 (test plan T5): [forums.size] committees with [perForum] meetings each (30 × 120 =
 * 3,600 over ten years: monthly, ten still to come, the last of them a draft, every fortieth past one cancelled), five
 * sections and 50 items per agenda (180,000; sub-items, a break, submissions in "For decision", a consent and an in-camera
 * section) with 166 papers (600,000), one published revision (every third meeting two), about eleven people in each
 * audience (40,000 rows), the last revision each member saw, some first opens, a placed submission per submission item
 * and a tray of 50. The **focal meeting** is the first committee's next one, at the budget's size: 60 items, 196 papers,
 * five revisions and 40 people, its papers backed by file rows so it can be published again.
 *
 * Restrictions, hidden titles and a level above the director's clearance are spread over the items so the paper rule
 * filters for real. Written by SQL in one tenant transaction as the application role (the rows production writes, with
 * the forced policies), deterministic ids from `md5`; then analysed as the schema owner.
 */
class MeetingVolume(
    private val tenant: UUID,
    /** The committees' ids and codes, the focal one first. */
    private val forums: List<Pair<UUID, String>>,
    private val perForum: Int,
    private val people: VolumePeople,
    private val levels: Levels,
) {
    /** The meeting levels: [low] for most rows, [high] above the director's clearance. */
    data class Levels(
        val low: UUID,
        val high: UUID,
    )

    init {
        require(perForum > FUTURE) { "perForum must be more than $FUTURE" }
    }

    /** Meetings already past per committee: k ≤ [past]; the [FUTURE] after them are still to come. */
    private val past = perForum - FUTURE

    /** The first meeting still to come: the first committee's is the focal one. */
    private val focalK = past + 1

    /** The focal meeting's id. */
    val focal: UUID get() = generated("mvm1-$focalK")

    /** The id the generator wrote for [key]: PostgreSQL's `md5(key)::uuid`. */
    fun generated(key: String): UUID =
        DriverManager.getConnection(TestDatabase.jdbcUrl, "memo_migrator", TestDatabase.migratorPassword).use { c ->
            c.prepareStatement("select md5(?)::uuid").use { st ->
                st.setString(1, key)
                st.executeQuery().use { rs ->
                    rs.next()
                    rs.getObject(1, UUID::class.java)
                }
            }
        }

    /** Writes the volume through [dsl] (inside a tenant transaction: the temp tables drop at its commit). */
    fun generate(dsl: TenantDsl) {
        statements().forEach { sql ->
            val started = System.nanoTime()
            dsl.current().execute(sql)
            val ms = (System.nanoTime() - started) / NANOS_PER_MILLI
            if (ms >= SLOW_MS) println("MeetingVolume $ms ms: ${sql.trim().lineSequence().first()}")
        }
    }

    /** Refreshes the planner's statistics of the tables written, as the schema owner (the application role may not). */
    fun analyse() {
        DriverManager.getConnection(TestDatabase.jdbcUrl, "memo_migrator", TestDatabase.migratorPassword).use { c ->
            c.createStatement().use { it.execute("analyze ${TABLES.joinToString()}") }
        }
    }

    private fun statements(): List<String> =
        listOf(
            forumsAndMeetings(),
            agendas(),
            sections(),
            items(),
            papers(),
            revisions(),
            audience(),
            reads(),
            submissions(),
            files(),
            declarations(),
        ).flatten()

    private fun forumsAndMeetings(): List<String> {
        val forumRows =
            forums.mapIndexed { i, (id, code) -> "(${i + 1}, '$id'::uuid, '$code')" }.joinToString()
        return listOf(
            "create temp table mv_forum (f int, id uuid, code text) on commit drop",
            "insert into mv_forum values $forumRows",
            // Meeting k of forum f: monthly at 10:00 in Dhaka (04:00 UTC); k > past are still to come, the last a draft.
            """
            create temp table mv_meeting on commit drop as
            select f.f, k, md5('mvm' || f.f || '-' || k)::uuid as id, f.id as forum_id, f.code,
                   date_trunc('day', now()) + ((k - $past - 1) * 30 + f.f % 28) * interval '1 day' + interval '4 hours' as starts_at,
                   case when k = $perForum then 'DRAFT' when k > $past then 'SCHEDULED'
                        when k % 40 = 0 then 'CANCELLED' else 'HELD' end as status,
                   case when f.f = 1 and k = $focalK then $FOCAL_PER_SECTION else $PER_SECTION end as per_section,
                   case when k = $perForum then 0 when f.f = 1 and k = $focalK then $FOCAL_REVISIONS
                        when k % 3 = 0 then 2 else 1 end as revisions,
                   case when f.f = 1 and k = $focalK then $FOCAL_MEMBERS else $MEMBERS end as members
            from mv_forum f, generate_series(1, $perForum) k
            """,
            """
            insert into meeting (id, tenant_id, forum_id, forum_type_version, kind, starts_at, ends_at, time_zone, mode, location,
                                 classification_id, number, number_year, label, status, status_reason, created_by, created_at,
                                 updated_at)
            select id, '$tenant'::uuid, forum_id, 1, 'ORDINARY', starts_at, starts_at + interval '3 hours', 'Asia/Dhaka', 'IN_PERSON',
                   'Board room', '${levels.low}',
                   case when revisions > 0 then k end,
                   case when revisions > 0 then extract(year from starts_at)::int end,
                   case when revisions > 0 then code || ' ' || k || '/' || extract(year from starts_at)::int end,
                   status, case when status = 'CANCELLED' then 'Postponed' end, '${people.admin}', now(), now()
            from mv_meeting
            """,
        )
    }

    private fun agendas(): List<String> =
        listOf(
            """
            insert into meeting_agenda (meeting_id, tenant_id, state, started_from, current_revision, dirty, created_by, created_at,
                                        updated_at)
            select id, '$tenant'::uuid, case when revisions > 0 then 'PUBLISHED' else 'DRAFT' end, 'TEMPLATE', revisions, false,
                   '${people.admin}', now(), now()
            from mv_meeting
            """,
        )

    private fun sections(): List<String> =
        listOf(
            """
            create temp table mv_section on commit drop as
            select m.id as meeting_id, m.f, m.k, m.status, m.starts_at, m.per_section, m.revisions, j,
                   md5('mvs' || m.id || '-' || j)::uuid as section_id,
                   (array['Opening', 'For decision', 'Consent items', 'In camera', 'For noting'])[j + 1] as title,
                   case when j = 2 then 'CONSENT' else 'STANDARD' end as kind, j = 3 as members_only
            from mv_meeting m, generate_series(0, 4) j
            """,
            """
            insert into meeting_section (id, tenant_id, meeting_id, position, title, kind, members_only, receives_submissions, created_at)
            select section_id, '$tenant'::uuid, meeting_id, j, title, kind, members_only, j = 1, now() from mv_section
            """,
        )

    /**
     * Items: per section, the last is a sub-item of the one before; "For decision" holds submissions; "For noting" opens
     * with a break. [n] is the agenda order, [tn] the published number (breaks skipped, a sub-item "n.1").
     */
    private fun items(): List<String> {
        val sub = "i = s.per_section - 1"
        val brk = "(s.j = 4 and i = 0)"
        return listOf(
            """
            create temp table mv_item on commit drop as
            select s.meeting_id, s.f, s.k, s.status, s.starts_at, s.revisions, s.section_id, s.j, s.members_only, i,
                   md5('mvi' || s.meeting_id || '-' || s.j || '-' || i)::uuid as item_id,
                   case when $sub then md5('mvi' || s.meeting_id || '-' || s.j || '-' || (i - 1))::uuid end as parent_id,
                   case when $brk then 'BREAK' when s.j = 1 and not $sub then 'SUBMISSION' else 'ITEM' end as kind,
                   row_number() over (partition by s.meeting_id order by s.j, i) as n,
                   count(*) filter (where not $sub and not $brk) over (partition by s.meeting_id order by s.j, i) as tn,
                   $sub as sub, $brk as brk
            from mv_section s, generate_series(0, s.per_section - 1) i
            """,
            "create index on mv_item (meeting_id)",
            "analyze mv_item",
            """
            insert into meeting_item (id, tenant_id, meeting_id, section_id, parent_id, position, kind, submission_id, memo_id, title,
                                      purpose, minutes, restricted_to, title_hidden, classification_id, created_by, created_at,
                                      updated_at)
            select item_id, '$tenant'::uuid, meeting_id, section_id, parent_id, case when sub then 0 else i end, kind,
                   case when kind = 'SUBMISSION' then md5('mvsub' || item_id)::uuid end,
                   case when kind = 'SUBMISSION' then md5('mvmemo' || item_id)::uuid end,
                   'Item ' || n || ' of the meeting', $PURPOSE, case when not sub then 15 end, ${restriction()},
                   n % 50 = 7, case when n % 20 = 0 then '${levels.high}'::uuid else '${levels.low}'::uuid end,
                   '${people.secretary}', now(), now()
            from mv_item
            """,
            """
            insert into meeting_item_attendee (tenant_id, item_id, user_id, added_at)
            select '$tenant'::uuid, item_id, md5('mvatt' || f)::uuid, now() from mv_item where n % 10 = 3 and not brk
            """,
        )
    }

    /** Papers: four on every third item of a section, else three; none on a break. */
    private fun papers(): List<String> =
        listOf(
            """
            create temp table mv_paper on commit drop as
            select it.meeting_id, it.f, it.k, it.item_id, it.kind, it.revisions, it.n, p,
                   md5('mvp' || it.item_id || '-' || p)::uuid as paper_id, md5('mvf' || it.item_id || '-' || p)::uuid as file_id,
                   md5('mvv' || it.item_id || '-' || p)::uuid as version_id,
                   row_number() over (partition by it.meeting_id order by it.n, p) as pn
            from mv_item it, generate_series(0, case when it.i % 3 = 0 then 3 else 2 end) p
            where not it.brk
            """,
            "create index on mv_paper (meeting_id)",
            "analyze mv_paper",
            """
            insert into meeting_paper (id, tenant_id, meeting_id, item_id, position, source, file_id, title, restricted_to,
                                       classification_id, created_by, created_at, updated_at)
            select paper_id, '$tenant'::uuid, meeting_id, item_id, p,
                   case when kind = 'SUBMISSION' and p = 0 then 'MEMO_DOCUMENT' when kind = 'SUBMISSION' then 'MEMO_ATTACHMENT'
                        else 'UPLOAD' end,
                   file_id, 'Paper ' || pn, case when pn % 30 = 0 then array['${people.chair}'::uuid] end, '${levels.low}',
                   '${people.secretary}', now(), now()
            from mv_paper
            """,
        )

    /** Revisions 1..r of each published agenda: the same body, a few items retitled and papers replaced from revision 2. */
    private fun revisions(): List<String> =
        listOf(
            """
            create temp table mv_revision on commit drop as
            select m.id as meeting_id, m.starts_at, r, md5('mvr' || m.id || '-' || r)::uuid as revision_id
            from mv_meeting m, generate_series(1, m.revisions) r
            """,
            """
            insert into meeting_revision (id, tenant_id, meeting_id, number, published_at, published_by, note, notified)
            select revision_id, '$tenant'::uuid, meeting_id, r, starts_at - (12 - r) * interval '1 day', '${people.secretary}',
                   case when r > 1 then 'Updated papers' end, true
            from mv_revision
            """,
            """
            insert into meeting_revision_section (tenant_id, revision_id, section_id, position, title, kind, members_only)
            select '$tenant'::uuid, r.revision_id, s.section_id, s.j, s.title, s.kind, s.members_only
            from mv_revision r join mv_section s on s.meeting_id = r.meeting_id
            """,
            """
            insert into meeting_revision_item (tenant_id, revision_id, item_id, section_id, parent_item_id, position, number, kind,
                                               submission_id, memo_id, title, purpose, minutes, starts_at, presenters, attendees,
                                               restricted_to, title_hidden, members_only, classification_id, late, changes)
            select '$tenant'::uuid, r.revision_id, it.item_id, it.section_id, it.parent_id, it.n - 1,
                   case when it.brk then null when it.sub then it.tn || '.1' else it.tn::text end, it.kind,
                   mi.submission_id, mi.memo_id, mi.title, mi.purpose, mi.minutes,
                   case when not it.sub then it.starts_at + (it.n - 1) * interval '15 minutes' end, '[]'::jsonb,
                   case when it.n % 10 = 3 and not it.brk then array[md5('mvatt' || it.f)::uuid] else array[]::uuid[] end,
                   mi.restricted_to, mi.title_hidden, it.members_only, mi.classification_id, 'NONE',
                   case when r.r > 1 and it.n % 15 = 1 then array['RETITLED'] else array[]::text[] end
            from mv_revision r
            join mv_item it on it.meeting_id = r.meeting_id
            join meeting_item mi on mi.tenant_id = '$tenant'::uuid and mi.id = it.item_id
            """,
            """
            insert into meeting_revision_paper (tenant_id, revision_id, paper_id, item_id, position, source, file_id, file_version_id,
                                                title, restricted_to, classification_id, late, pages, changes)
            select '$tenant'::uuid, r.revision_id, p.paper_id, p.item_id, p.p, mp.source, p.file_id, p.version_id, mp.title,
                   mp.restricted_to, mp.classification_id, 'NONE', 10,
                   case when r.r > 1 and p.pn % 40 = 1 then array['REPLACED'] else array[]::text[] end
            from mv_revision r
            join mv_paper p on p.meeting_id = r.meeting_id
            join meeting_paper mp on mp.tenant_id = '$tenant'::uuid and mp.id = p.paper_id
            """,
        )

    /** The audience: the committee's members, its secretary, the director (three committees), the chair and attendees. */
    private fun audience(): List<String> =
        listOf(
            """
            insert into meeting_audience (tenant_id, meeting_id, user_id, source, members_only)
            select '$tenant'::uuid, m.id, md5('mvu' || m.f || '-' || u)::uuid, 'MEMBER', true
            from mv_meeting m, generate_series(1, m.members) u
            union all select '$tenant'::uuid, m.id, '${people.secretary}'::uuid, 'MEMBER', true from mv_meeting m
            union all select '$tenant'::uuid, m.id, '${people.director}'::uuid, 'MEMBER', true from mv_meeting m where m.f <= $DIRECTOR_FORUMS
            union all select '$tenant'::uuid, m.id, '${people.chair}'::uuid, 'MEMBER', true from mv_meeting m where m.f = 1
            union all select '$tenant'::uuid, m.id, md5('mvatt' || m.f)::uuid, 'ATTENDEE', false from mv_meeting m
            """,
        )

    /**
     * What the audience did: every member saw the latest revision, the director only the first (so "What changed" and the
     * "My meetings" counts have work), and one member opened the first 30 papers of each published agenda.
     */
    private fun reads(): List<String> =
        listOf(
            """
            insert into meeting_reader_state (tenant_id, meeting_id, user_id, last_revision, seen_at)
            select '$tenant'::uuid, m.id, md5('mvu' || m.f || '-' || u)::uuid, m.revisions, now()
            from mv_meeting m, generate_series(1, $MEMBERS) u where m.revisions > 0
            union all
            select '$tenant'::uuid, m.id, '${people.director}'::uuid, 1, now() from mv_meeting m where m.revisions > 0 and m.f <= $DIRECTOR_FORUMS
            """,
            """
            insert into meeting_paper_open (tenant_id, paper_id, user_id, first_opened_at)
            select '$tenant'::uuid, paper_id, md5('mvu' || f || '-1')::uuid, now() from mv_paper where revisions > 0 and pn <= 30
            """,
        )

    /** A submission per submission item (decided for past meetings, accepted and placed for the coming ones) and a tray. */
    private fun submissions(): List<String> =
        listOf(
            """
            insert into forum_submission (id, tenant_id, forum_id, subject_type, subject_id, instance_id, purpose, status, submitted_by,
                                          submitted_at, outcome, decided_by, decided_at, meeting_id, meeting_date)
            select md5('mvsub' || it.item_id)::uuid, '$tenant'::uuid, m.forum_id, 'memo', md5('mvmemo' || it.item_id)::uuid,
                   md5('mvins' || it.item_id)::uuid, 'DECISION',
                   case when it.status in ('HELD', 'CANCELLED') then 'DECIDED' else 'ACCEPTED' end,
                   '${people.admin}', m.starts_at - interval '20 days',
                   case when it.status in ('HELD', 'CANCELLED') then 'APPROVED' end,
                   case when it.status in ('HELD', 'CANCELLED') then '${people.secretary}'::uuid end,
                   case when it.status in ('HELD', 'CANCELLED') then m.starts_at end,
                   m.id, (m.starts_at at time zone 'Asia/Dhaka')::date
            from mv_item it join mv_meeting m on m.id = it.meeting_id
            where it.kind = 'SUBMISSION'
            """,
            """
            insert into forum_submission (id, tenant_id, forum_id, subject_type, subject_id, instance_id, purpose, status, submitted_by,
                                          submitted_at)
            select md5('mvtray' || t)::uuid, '$tenant'::uuid, '${forums.first().first}', 'memo', md5('mvtraymemo' || t)::uuid,
                   md5('mvtrayins' || t)::uuid, 'DECISION', 'ACCEPTED', '${people.admin}', now() - t * interval '1 hour'
            from generate_series(1, $TRAY) t
            """,
        )

    /** File rows for the focal meeting's papers: a PDF of ten pages each, so the draft and publishing read real versions. */
    private fun files(): List<String> {
        val focal = "p.f = 1 and p.k = $focalK"
        val key = "'t/$tenant/' || substr(translate(md5('mvkey' || p.paper_id), '0189', 'wxyz'), 1, 26)"
        return listOf(
            """
            insert into files_file (id, tenant_id, purpose, owner_id, version_count, created_by, created_at)
            select p.file_id, '$tenant'::uuid, 'meeting.paper', p.meeting_id::text, 0, '${people.secretary}', now() from mv_paper p where $focal
            """,
            """
            insert into files_version (id, tenant_id, file_id, number, original_name, media_type, size_bytes, sha256, bucket, object_key,
                                       ciphertext_bytes, keyset_key_id, scan_engine, pdf_pages, lock_state, uploaded_by, uploaded_at)
            select p.version_id, '$tenant'::uuid, p.file_id, 1, 'paper.pdf', 'application/pdf', 100000,
                   decode(md5('a' || p.paper_id) || md5('b' || p.paper_id), 'hex'), 'work', $key, 100100, 1, 'test', 10, 'NONE',
                   '${people.secretary}', now()
            from mv_paper p where $focal
            """,
            """
            update files_file f set current_version_id = p.version_id, version_count = 1
            from mv_paper p where $focal and f.tenant_id = '$tenant'::uuid and f.id = p.file_id
            """,
        )
    }

    /**
     * Declarations of interest (design E04 §8, test plan T4): on 5 % of the items (one in 20; two declarants on every other
     * one), mostly recused, some taking part after a ruling, waiting for one or withdrawn; on the focal meeting 36, the
     * director's among them, so the members' agenda has recusals to apply. Their history (declared, ruled, withdrawn) and
     * the members' reviews of the first and second revisions.
     */
    private fun declarations(): List<String> {
        val focal = "it.f = 1 and it.k = $focalK"
        return listOf(
            """
            create temp table mv_declaration on commit drop as
            select it.meeting_id, m.forum_id, it.item_id, it.revisions, d, md5('mvd' || it.item_id || '-' || d)::uuid as id,
                   case when $focal and d = 0 and it.n % 12 = 0 then '${people.director}'::uuid
                        else md5('mvu' || it.f || '-' || (1 + (it.n + d) % $MEMBERS))::uuid end as user_id,
                   case when (it.n + d) % 10 = 3 then 'TAKING_PART' when (it.n + d) % 10 = 7 then 'WITHDRAWN'
                        when (it.n + d) % 10 = 9 then 'AWAITING_RULING' else 'RECUSED' end as state,
                   it.starts_at - interval '10 days' + it.n * interval '1 minute' + d * interval '1 hour' as declared_at
            from mv_item it
            join mv_meeting m on m.id = it.meeting_id,
                 generate_series(0, case when it.n % 40 = 5 or $focal then 1 else 0 end) d
            where not it.brk and (it.n % 20 = 5 or ($focal and it.n % 4 = 0))
            """,
            """
            insert into meeting_declaration (id, tenant_id, meeting_id, item_id, forum_id, user_id, nature, intent, source, recorded_by,
                                             how, declared_at, at_meeting, revision, state)
            select id, '$tenant'::uuid, meeting_id, item_id, forum_id, user_id, 'Interest in the counterparty ' || d,
                   case when state = 'AWAITING_RULING' then 'ASK_CHAIR' else 'LEAVE' end, 'SELF', user_id, 'IN_APP',
                   declared_at, false, case when revisions > 0 then 1 end, state
            from mv_declaration
            """,
            """
            insert into meeting_declaration_event (id, tenant_id, declaration_id, kind, state_after, actor_id, how, note, at)
            select md5('mvde' || id)::uuid, '$tenant'::uuid, id, 'DECLARED',
                   case when state = 'AWAITING_RULING' then 'AWAITING_RULING' else 'RECUSED' end, user_id, 'IN_APP', null,
                   declared_at
            from mv_declaration
            union all
            select md5('mvdr' || id)::uuid, '$tenant'::uuid, id, 'RULED', 'TAKING_PART', '${people.chair}'::uuid, 'IN_APP', null,
                   declared_at + interval '1 day'
            from mv_declaration where state = 'TAKING_PART'
            union all
            select md5('mvdw' || id)::uuid, '$tenant'::uuid, id, 'WITHDRAWN', 'WITHDRAWN', user_id, 'IN_APP',
                   'Entered in error', declared_at + interval '1 day'
            from mv_declaration where state = 'WITHDRAWN'
            """,
            """
            insert into meeting_declaration_review (tenant_id, meeting_id, user_id, revision, reviewed_at)
            select '$tenant'::uuid, m.id, md5('mvu' || m.f || '-' || u)::uuid, r, m.starts_at - interval '12 days' + r * interval '1 hour'
            from mv_meeting m, generate_series(1, $MEMBERS) u, generate_series(1, 2) r
            where m.revisions >= r and (r = 1 and u % 4 <> 0 or r = 2 and u % 2 = 0)
            """,
        )
    }

    /** Every 25th item is for the chair only (the director sees its line), every 25th + 12 for the director and the chair. */
    private fun restriction() =
        "case when n % 25 = 0 then array['${people.chair}'::uuid] " +
            "when n % 25 = 12 then array['${people.director}'::uuid, '${people.chair}'::uuid] end"

    companion object {
        /** Meetings still to come per committee (the last a draft). */
        const val FUTURE = 10
        const val PER_SECTION = 10
        const val FOCAL_PER_SECTION = 12
        const val FOCAL_REVISIONS = 5
        const val MEMBERS = 10
        const val FOCAL_MEMBERS = 36
        const val DIRECTOR_FORUMS = 3
        const val TRAY = 50

        /** An item's purpose: none on a break, else in turn. */
        private const val PURPOSE =
            "case when kind = 'BREAK' then null else (array['DECISION', 'DISCUSSION', 'INFORMATION'])[1 + n % 3] end"
        private const val SLOW_MS = 1_000L
        private const val NANOS_PER_MILLI = 1_000_000L

        val TABLES =
            listOf(
                "meeting",
                "meeting_agenda",
                "meeting_section",
                "meeting_item",
                "meeting_item_attendee",
                "meeting_paper",
                "meeting_revision",
                "meeting_revision_section",
                "meeting_revision_item",
                "meeting_revision_paper",
                "meeting_audience",
                "meeting_reader_state",
                "meeting_paper_open",
                "forum_submission",
                "files_file",
                "files_version",
                "meeting_declaration",
                "meeting_declaration_event",
                "meeting_declaration_review",
            )
    }
}
