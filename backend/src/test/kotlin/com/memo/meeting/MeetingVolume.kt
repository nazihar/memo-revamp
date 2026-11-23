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
    /** With E05's rows too ([invitations] and after; design E05 §8): forum 1's seats, invitations, replies, attendance. */
    private val e05: Boolean = false,
    /** Each agenda opens with a "Matters arising" standing item, as E07's action taken report needs (`ActionsVolumeIT`). */
    private val mattersArising: Boolean = false,
    /**
     * With [e05], the attendance histories of design E08 §8 (analytics' volume, E08 T5): every committee seated ([SEATED]
     * people: a chair, a vice-chair and members, one term ended two years ago and its successor's begun), members sending
     * apologies to [APOLOGIES_PCT] % of the meetings and missing [ABSENT_PCT] % without a word (so they attend about 90 %),
     * [LATE_PCT] % arriving late, [LEAVES_PCT] % leaving early and [RETURNS_PCT] % stepping out and back, and the alternates
     * of three seats standing in when their member sent apologies. The held focal meeting keeps E05's rows, which the E05
     * and E06 volume tests measure.
     */
    private val histories: Boolean = false,
) {
    /** The meeting levels: [low] for most rows, [high] above the director's clearance. */
    data class Levels(
        val low: UUID,
        val high: UUID,
    )

    init {
        require(perForum > FUTURE) { "perForum must be more than $FUTURE" }
        require(!histories || e05) { "the attendance histories are written over E05's rows" }
    }

    /** Meetings already past per committee: k ≤ [past]; the [FUTURE] after them are still to come. */
    private val past = perForum - FUTURE

    /** The first meeting still to come: the first committee's is the focal one. */
    private val focalK = past + 1

    /** The focal meeting's id. */
    val focal: UUID get() = generated("mvm1-$focalK")

    /** The first committee's last held meeting: with [e05], the focal meeting's audience and a full register. */
    private val heldK = if (past % CANCELLED_EVERY == 0) past - 1 else past

    /** The held focal meeting's id. */
    val held: UUID get() = generated("mvm1-$heldK")

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
            c.createStatement().use { it.execute("analyze ${(TABLES + if (e05) E05_TABLES else emptyList()).joinToString()}") }
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
            if (e05) invitations() + replies() + attendance() + quorum() else emptyList(),
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
                        when k % $CANCELLED_EVERY = 0 then 'CANCELLED' else 'HELD' end as status,
                   case when f.f = 1 and k = $focalK then $FOCAL_PER_SECTION else $PER_SECTION end as per_section,
                   case when k = $perForum then 0 when f.f = 1 and k = $focalK then $FOCAL_REVISIONS
                        when k % 3 = 0 then 2 else 1 end as revisions,
                   case when f.f = 1 and k = $focalK then $FOCAL_MEMBERS ${heldMembers()}else $MEMBERS end as members
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
                   case when $brk then 'BREAK' when s.j = 1 and not $sub then 'SUBMISSION'
                        when $mattersArising and s.j = 0 and i = 0 then 'STANDING' else 'ITEM' end as kind,
                   row_number() over (partition by s.meeting_id order by s.j, i) as n,
                   count(*) filter (where not $sub and not $brk) over (partition by s.meeting_id order by s.j, i) as tn,
                   $sub as sub, $brk as brk
            from mv_section s, generate_series(0, s.per_section - 1) i
            """,
            "create index on mv_item (meeting_id)",
            "analyze mv_item",
            """
            insert into meeting_item (id, tenant_id, meeting_id, section_id, parent_id, position, kind, standing_kind, submission_id,
                                      memo_id, title, purpose, minutes, restricted_to, title_hidden, classification_id, created_by,
                                      created_at, updated_at)
            select item_id, '$tenant'::uuid, meeting_id, section_id, parent_id, case when sub then 0 else i end, kind,
                   case when kind = 'STANDING' then 'MATTERS_ARISING' end,
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
                                               standing_kind, submission_id, memo_id, title, purpose, minutes, starts_at, presenters,
                                               attendees, restricted_to, title_hidden, members_only, classification_id, late, changes)
            select '$tenant'::uuid, r.revision_id, it.item_id, it.section_id, it.parent_id, it.n - 1,
                   case when it.brk then null when it.sub then it.tn || '.1' else it.tn::text end, it.kind, mi.standing_kind,
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

    /** The held focal meeting has the focal meeting's audience with [e05]. */
    private fun heldMembers() = if (e05) "when f.f = 1 and k = $heldK then $FOCAL_MEMBERS " else ""

    /**
     * Invitations (design E05 §8): forum 1's 36 member seats (its audience's members, all independent), then for every
     * meeting past draft the invitation (cancelled ones cancelled) and its 40 invitees (forum 1: its 36 members, the
     * secretary, the director and the chair), each sent an invitation, an update and a resend (430,000 messages); the focal
     * meeting's last messages in the outbox, as the panel reads their delivery.
     */
    private fun invitations(): List<String> {
        val sent = "least(now(), m.starts_at - interval '30 days')"
        val cancelled = "status = 'CANCELLED'"
        val focal = "f = 1 and k = $focalK"
        return listOf(
            """
            insert into forum_member (id, tenant_id, forum_id, kind, user_id, role, voting, attributes, starts_on, term_number,
                                      created_at, version)
            select md5('mvfm' || u)::uuid, '$tenant'::uuid, '${forums.first().first}'::uuid, 'PERSON', md5('mvu1-' || u)::uuid,
                   'MEMBER', true, '["independent"]'::jsonb, date '2010-01-01', 1, now(), 0
            from generate_series(1, $FOCAL_MEMBERS) u
            """,
        ) + seats() +
            listOf(
                """
            create temp table mv_invitee on commit drop as
            select m.id as meeting_id, m.f, m.k, m.status, m.starts_at, u, md5('mvu' || m.f || '-' || u)::uuid as user_id, $sent as sent_at
            from mv_meeting m, generate_series(1, $INVITEES) u
            where m.status <> 'DRAFT' and (m.f <> 1 or u <= $FOCAL_MEMBERS)
            union all
            select m.id, m.f, m.k, m.status, m.starts_at, 100 + p.r, p.user_id, $sent
            from mv_meeting m,
                 (values (1, '${people.secretary}'::uuid), (2, '${people.director}'::uuid), (3, '${people.chair}'::uuid)) p(r, user_id)
            where m.status <> 'DRAFT' and m.f = 1
            """,
                "create index on mv_invitee (meeting_id)",
                "analyze mv_invitee",
                """
            insert into meeting_invitation (tenant_id, meeting_id, ical_uid, sequence, sent_sequence, pending_changes, cancelled_at,
                                            due_at, reconciled_at, created_at, version)
            select '$tenant'::uuid, m.id, md5('mvical' || m.id)::uuid, case when m.$cancelled then 2 else 1 end,
                   case when m.$cancelled then 2 else 1 end, '{}', case when m.$cancelled then m.starts_at - interval '5 days' end,
                   null, $sent + interval '1 day', $sent - interval '1 hour', 2
            from mv_meeting m where m.status <> 'DRAFT'
            """,
                """
            insert into meeting_invitee (tenant_id, meeting_id, user_id, state, last_sequence, held_sequence, last_method, last_kind,
                                         last_outbox_id, first_sent_at, last_sent_at, version)
            select '$tenant'::uuid, meeting_id, user_id, case when $cancelled then 'CANCELLED' else 'INVITED' end,
                   case when $cancelled then 2 else 1 end, case when $cancelled then 2 else 1 end,
                   case when $cancelled then 'CANCEL' else 'REQUEST' end, case when $cancelled then 'CANCEL' else 'UPDATE' end,
                   case when $focal then md5('mvob' || meeting_id || user_id)::uuid end, sent_at, sent_at + interval '1 day', 2
            from mv_invitee
            """,
                """
            insert into meeting_invitation_message (id, tenant_id, meeting_id, user_id, sequence, method, kind, changes, outbox_id,
                                                    notification_id, queued_by, queued_at)
            select md5('mvim' || i.meeting_id || i.user_id || n)::uuid, '$tenant'::uuid, i.meeting_id, i.user_id,
                   case when n = 0 then 0 else 1 end, 'REQUEST', (array['INVITATION', 'UPDATE', 'RESEND'])[n + 1],
                   case when n = 1 then array['TIME'] else '{}'::text[] end, null, null,
                   case when n = 2 then '${people.secretary}'::uuid end, i.sent_at + n * interval '12 hours'
            from mv_invitee i, generate_series(0, 2) n
            """,
                """
            insert into notification_mail_outbox (id, tenant_id, template, to_address, status, attempts, created_at, sent_at, relay,
                                                  last_attempt_at)
            select md5('mvob' || meeting_id || user_id)::uuid, '$tenant'::uuid, 'MEETING_UPDATED', 'member' || u || '@volume.example',
                   'SENT', 1, sent_at + interval '1 day', sent_at + interval '1 day', 'PLATFORM', sent_at + interval '1 day'
            from mv_invitee where $focal
            """,
            )
    }

    /**
     * With [histories], the other committees' seats (design E08 §8): their first [SEATED] invitees, the first the chair and
     * the second a vice-chair, seated since 2010; the twelfth's term ended two years ago and the thirteenth took the seat
     * the next day; seats 3 to 5 have an alternate ([alternate]).
     */
    private fun seats(): List<String> =
        if (!histories) {
            emptyList()
        } else {
            listOf(
                """
                insert into forum_member (id, tenant_id, forum_id, kind, user_id, role, voting, attributes, alternate_user_id, starts_on,
                                          ended_on, end_reason, term_number, created_at, version)
                select md5('mvfm' || f.f || '-' || u)::uuid, '$tenant'::uuid, f.id, 'PERSON', md5('mvu' || f.f || '-' || u)::uuid,
                       case u when 1 then 'CHAIR' when 2 then 'VICE_CHAIR' else 'MEMBER' end, true, '[]'::jsonb,
                       case when u between $ALTERNATE_SEATS then ${alternate("f.f", "u")} end,
                       case when u = $SEATED then current_date - 729 else date '2010-01-01' end,
                       case when u = ${SEATED - 1} then current_date - 730 end,
                       case when u = ${SEATED - 1} then 'TERM_COMPLETED' end, 1, now(), 0
                from mv_forum f, generate_series(1, $SEATED) u
                where f.f > 1
                """,
            )
        }

    /** Seat [u]'s alternate on committee [f]. */
    private fun alternate(
        f: String,
        u: String,
    ) = "md5('mvalt' || $f || '-' || $u)::uuid"

    /** Invitee [u] sends apologies to meeting [meeting]: one in five (E05's volume), or with [histories] as design E08 §8. */
    private fun apologies(
        meeting: String,
        u: String,
        focal: String,
    ) = if (histories) "case when $focal then $u % 5 = 3 else ${chance(meeting, u)} < $APOLOGIES_PCT end" else "$u % 5 = 3"

    /** A number from 0 to 99 fixed for invitee [u] at meeting [meeting]: what happens to them there, with [histories]. */
    private fun chance(
        meeting: String,
        u: String,
    ) = "abs(hashtext($meeting::text || '-' || $u)) % 100"

    /** With [histories], the member of seat 3, 4 or 5 of another committee who sends apologies asks the alternate to attend. */
    private fun standsIn() = if (histories) "answer = 'APOLOGIES' and f > 1 and u between $ALTERNATE_SEATS" else "false"

    /**
     * Replies (design E05 §8): every invitee answered (one in five with apologies, one in seven online), but one in ten of
     * the focal meeting's, which also has one in nine answered for an old time; a third changed their answer once (an
     * earlier event). The meeting reminders of past meetings, as the sweep marks them.
     */
    private fun replies(): List<String> {
        val focal = "f = 1 and k = $focalK"
        return listOf(
            """
            create temp table mv_rsvp on commit drop as
            select i.*, case when ${apologies("i.meeting_id", "i.u", "(i.f = 1 and i.k in ($focalK, $heldK))")} then 'APOLOGIES'
                             when i.u % 7 = 2 then 'REMOTE' else 'IN_PERSON' end as answer,
                   ($focal and i.u % 9 = 4) as stale
            from mv_invitee i
            where not ($focal and i.u % 10 = 0)
            """,
            """
            insert into meeting_rsvp (tenant_id, meeting_id, user_id, answer, alternate_attends, note, for_starts_at, for_ends_at,
                                      recorded_by, how, how_note, answered_at, version)
            select '$tenant'::uuid, meeting_id, user_id, answer, ${standsIn()},
                   case when u % 8 = 1 then 'I will join after the first item' end,
                   starts_at - case when stale then interval '1 day' else interval '0' end,
                   starts_at + interval '3 hours' - case when stale then interval '1 day' else interval '0' end,
                   user_id, 'IN_APP', null, sent_at + interval '2 days', case when u % 3 = 0 then 2 else 1 end
            from mv_rsvp
            """,
            """
            insert into meeting_rsvp_event (id, tenant_id, meeting_id, user_id, answer, alternate_attends, note_sha256, for_starts_at,
                                            for_ends_at, how, recorded_by, at)
            select md5('mvre' || meeting_id || user_id || e)::uuid, '$tenant'::uuid, meeting_id, user_id,
                   case when e = 0 then 'APOLOGIES' else answer end, false, null, starts_at, starts_at + interval '3 hours', 'IN_APP',
                   user_id, sent_at + (1 + e) * interval '1 day'
            from mv_rsvp, generate_series(0, 1) e where e = 1 or u % 3 = 0
            """,
            """
            insert into meeting_reminder (tenant_id, meeting_id, user_id, kind, key, sent_at)
            select '$tenant'::uuid, meeting_id, user_id, 'MEETING', ((starts_at at time zone 'Asia/Dhaka')::date)::text,
                   starts_at - interval '1 day'
            from mv_invitee where status in ('HELD', 'CANCELLED')
            """,
        )
    }

    /**
     * Attendance (design E05 §8) at every held meeting: one in five sent apologies; the rest arrived, half left and came
     * back, a third went online, a quarter left early (about 90 events a meeting, 290,000). The held focal meeting has its
     * 36 members and three officers on its register with 9 events each (about 300): three departures and returns and
     * stepping out of an item; ten items in progress. A quorum reached per held meeting, and a snapshot for each of its
     * first items (60,000; the held focal meeting's are taken through [QuorumSnapshots] by the test). With [histories] the
     * other meetings follow design E08 §8's rates instead, some members are absent without apologies, and the alternates of
     * seats 3 to 5 attend for their members ([alternates]).
     */
    private fun attendance(): List<String> {
        val held = "f = 1 and k = $heldK"
        val minute = "* interval '1 minute'"
        val general = "arrives and not focal"
        val cycles = "mv_attendee, generate_series(1, 3) c where focal and not apologies"
        val chance = chance("meeting_id", "u")
        // What happens to each invitee: E05's pattern by seat number, or with the histories by chance (the focal meeting kept).
        val absent = if (histories) "not ($held) and $chance between $APOLOGIES_PCT and ${APOLOGIES_PCT + ABSENT_PCT - 1}" else "false"
        val returns = if (histories) "$chance between $RETURNS_FROM and ${RETURNS_FROM + RETURNS_PCT - 1}" else "u % 2 = 0"
        val leaves = if (histories) "$chance between $LEAVES_FROM and ${LEAVES_FROM + LEAVES_PCT - 1}" else "u % 4 = 1"
        val late =
            if (histories) {
                "case when $chance between $LATE_FROM and ${LATE_FROM + LATE_PCT - 1} then 15 + $chance % 30 else u % 10 end"
            } else {
                "u % 12"
            }
        return listOf(
            """
            create temp table mv_attendee on commit drop as
            select a.*, not a.apologies and not a.absent as arrives
            from (
                select meeting_id, f, k, u, user_id, starts_at, ($held) as focal, ${apologies("meeting_id", "u", "($held)")} as apologies,
                       $absent as absent, $returns as returns, $leaves as leaves, $late as late
                from mv_invitee where status = 'HELD'
            ) a
            """,
            """
            insert into meeting_attendance (tenant_id, meeting_id, user_id, capacity, state, mode, first_arrived_at, last_left_at,
                                            opened_at, updated_at, version)
            select '$tenant'::uuid, meeting_id, user_id, 'MEMBER',
                   case when apologies then 'APOLOGIES' when absent then 'ABSENT' when not focal and leaves then 'LEFT' else 'PRESENT' end,
                   case when not arrives or (not focal and leaves) then null when not focal and u % 3 = 0 then 'REMOTE'
                        else 'IN_PERSON' end,
                   case when arrives then starts_at + late $minute end,
                   case when not arrives then null when focal or returns then starts_at + interval '90 minutes'
                        when leaves then starts_at + interval '170 minutes' end,
                   starts_at, starts_at + interval '3 hours', 0
            from mv_attendee
            """,
            """
            create temp table mv_attendance_event on commit drop as
            select meeting_id, user_id, 0 as n, 'APOLOGIES' as kind, null::text as mode, starts_at - interval '1 hour' as at,
                   false as stepped, 'RSVP' as source
            from mv_attendee where apologies
            union all select meeting_id, user_id, 0, 'ABSENT', null, starts_at + interval '30 minutes', false, 'RECORDED'
            from mv_attendee where absent
            union all select meeting_id, user_id, 0, 'ARRIVED', 'IN_PERSON', starts_at + late $minute, false, 'RECORDED'
            from mv_attendee where arrives
            union all select meeting_id, user_id, 1, 'LEFT', null, starts_at + interval '90 minutes', false, 'RECORDED'
            from mv_attendee where $general and returns
            union all select meeting_id, user_id, 2, 'ARRIVED', 'IN_PERSON', starts_at + interval '100 minutes', false, 'RECORDED'
            from mv_attendee where $general and returns
            union all select meeting_id, user_id, 3, 'MODE_CHANGED', 'REMOTE', starts_at + interval '120 minutes', false, 'RECORDED'
            from mv_attendee where $general and u % 3 = 0
            union all select meeting_id, user_id, 4, 'LEFT', null, starts_at + interval '170 minutes', false, 'RECORDED'
            from mv_attendee where $general and leaves
            union all select meeting_id, user_id, 2 * c - 1, 'LEFT', null, starts_at + 30 * c $minute, false, 'RECORDED' from $cycles
            union all select meeting_id, user_id, 2 * c, 'ARRIVED', 'IN_PERSON', starts_at + (30 * c + 5) $minute, false, 'RECORDED'
            from $cycles
            union all select meeting_id, user_id, 7, 'STEPPED_OUT', null, starts_at + interval '100 minutes', true, 'RECORDED'
            from mv_attendee where focal and not apologies
            union all select meeting_id, user_id, 8, 'STEPPED_BACK', null, starts_at + interval '110 minutes', true, 'RECORDED'
            from mv_attendee where focal and not apologies
            """,
            """
            insert into meeting_attendance_event (id, tenant_id, meeting_id, user_id, kind, mode, at, item_id, source, correction,
                                                  recorded_by, recorded_at)
            select md5('mvae' || meeting_id || user_id || n)::uuid, '$tenant'::uuid, meeting_id, user_id, kind, mode, at,
                   case when stepped then md5('mvi' || meeting_id || '-1-0')::uuid end, source, false, '${people.secretary}', at
            from mv_attendance_event
            """,
        ) + alternates()
    }

    /**
     * With [histories], the alternate of seat 3, 4 or 5 of another committee attends when its member sent apologies (the
     * reply asked them to): in the audience for that seat, on the register as ALTERNATE, arrived at the start.
     */
    private fun alternates(): List<String> {
        if (!histories) return emptyList()
        val standing = "from mv_attendee where apologies and f > 1 and u between $ALTERNATE_SEATS"
        return listOf(
            """
            insert into meeting_audience (tenant_id, meeting_id, user_id, source, members_only, for_member_id, for_user_id)
            select '$tenant'::uuid, meeting_id, ${alternate("f", "u")}, 'ALTERNATE', true, md5('mvfm' || f || '-' || u)::uuid, user_id
            $standing
            """,
            """
            insert into meeting_attendance (tenant_id, meeting_id, user_id, capacity, for_member_id, for_user_id, state, mode,
                                            first_arrived_at, last_left_at, opened_at, updated_at, version)
            select '$tenant'::uuid, meeting_id, ${alternate("f", "u")}, 'ALTERNATE', md5('mvfm' || f || '-' || u)::uuid, user_id,
                   'PRESENT', 'IN_PERSON', starts_at + interval '2 minutes', null, starts_at, starts_at + interval '3 hours', 0
            $standing
            """,
            """
            insert into meeting_attendance_event (id, tenant_id, meeting_id, user_id, kind, mode, at, item_id, source, correction,
                                                  recorded_by, recorded_at)
            select md5('mvaealt' || meeting_id || user_id)::uuid, '$tenant'::uuid, meeting_id, ${alternate("f", "u")}, 'ARRIVED',
                   'IN_PERSON', starts_at + interval '2 minutes', null, 'RECORDED', false, '${people.secretary}',
                   starts_at + interval '2 minutes'
            $standing
            """,
        )
    }

    /** The held meetings' items in progress (the held focal meeting's), quorum reached and snapshots ([attendance]). */
    private fun quorum(): List<String> {
        val held = "f = 1 and k = $heldK"
        return listOf(
            """
            insert into meeting_item_progress (id, tenant_id, meeting_id, item_id, started_at, recorded_by, recorded_at)
            select md5('mvip' || item_id)::uuid, '$tenant'::uuid, meeting_id, item_id, starts_at + n * interval '15 minutes',
                   '${people.secretary}', starts_at + n * interval '15 minutes'
            from mv_item where $held and n <= 10 and not brk
            """,
            """
            insert into meeting_quorum_change (id, tenant_id, meeting_id, level, item_id, kind, needed, present, unmet, at,
                                               cause_event_id, recorded_at)
            select md5('mvqc' || id)::uuid, '$tenant'::uuid, id, 'MEETING', null, 'REACHED', 21, 32, '[]'::jsonb,
                   starts_at + interval '15 minutes', md5('mvae' || id || md5('mvu' || f || '-1')::uuid || 0)::uuid,
                   starts_at + interval '15 minutes'
            from mv_meeting where status = 'HELD'
            """,
            """
            insert into meeting_quorum_snapshot (id, tenant_id, meeting_id, item_id, at, type_id, type_version, inputs, result,
                                                 quorate, override_reason, ratification_required, inputs_sha256, taken_by, taken_at)
            select md5('mvqs' || it.item_id)::uuid, '$tenant'::uuid, it.meeting_id, it.item_id, it.starts_at + it.n * interval '3 minutes',
                   fo.forum_type_id, 1, '{"volume": true}'::jsonb, '{"volume": true}'::jsonb, true, null, false,
                   md5('a' || it.item_id) || md5('b' || it.item_id), '${people.secretary}', it.starts_at + it.n * interval '3 minutes'
            from mv_item it
            join mv_meeting m on m.id = it.meeting_id
            join forum fo on fo.tenant_id = '$tenant'::uuid and fo.id = m.forum_id
            where it.status = 'HELD' and not it.brk and it.n <= $SNAPSHOTS_UP_TO and not (it.f = 1 and it.k = $heldK)
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

        /** Invitees per meeting past draft (design E05 §8: 40). */
        const val INVITEES = 40

        /** Every fortieth past meeting is cancelled. */
        const val CANCELLED_EVERY = 40

        /** Snapshots on the items numbered up to this in agenda order (about 18 a held meeting). */
        const val SNAPSHOTS_UP_TO = 19

        /** With the histories: the seats of the other committees, the last one a successor's (design E08 §8). */
        const val SEATED = 13

        /** The seats whose alternate attends when their member sends apologies. */
        const val ALTERNATE_SEATS = "3 and 5"

        /** With the histories, the share of invitations (in %) answered with apologies, and missed without a word. */
        const val APOLOGIES_PCT = 7
        const val ABSENT_PCT = 3

        /** With the histories, the shares (in %) that step out and come back, leave early, or arrive late, by chance. */
        const val RETURNS_FROM = 10
        const val RETURNS_PCT = 20
        const val LEAVES_FROM = 30
        const val LEAVES_PCT = 8
        const val LATE_FROM = 40
        const val LATE_PCT = 10

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

        /** The tables [MeetingVolume] writes with e05 as well. */
        val E05_TABLES =
            listOf(
                "forum_member",
                "meeting_invitation",
                "meeting_invitee",
                "meeting_invitation_message",
                "notification_mail_outbox",
                "meeting_rsvp",
                "meeting_rsvp_event",
                "meeting_reminder",
                "meeting_attendance",
                "meeting_attendance_event",
                "meeting_item_progress",
                "meeting_quorum_change",
                "meeting_quorum_snapshot",
            )
    }
}
