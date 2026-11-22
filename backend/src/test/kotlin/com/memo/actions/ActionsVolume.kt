package com.memo.actions

import com.memo.meeting.MeetingVolume
import com.memo.meeting.VolumePeople
import com.memo.testsupport.TestDatabase
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import java.util.UUID

/**
 * The action volume of design E07 §8 (test plan T9), written over [MeetingVolume] (its held meetings, their items and the
 * focal meeting's "Matters arising") in the same tenant:
 * - [PER_MEETING] actions per held meeting (30 committees × 108 held meetings × 9 ≈ 29,000; about 970 per committee over
 *   ten years): seven from items of "For decision", "Consent items" and "In camera" (their columns copied, so the item
 *   rule filters for real), one from the meeting and one direct; owned by [persons] people (about one in ten through a
 *   position), with contributors on about half;
 * - about 3,400 open: those of each committee's last [OPEN_MEETINGS] held meetings (the first committee's last
 *   [FOCAL_OPEN_MEETINGS], so its next agenda's report has about 300 rows) and one in a hundred older ones, long overdue;
 *   one in nine of them done and waiting for verification; the others closed (one in twenty cancelled);
 * - the director holds [DIRECTOR_MINE] open actions (one in four through their position) and contributes to
 *   [DIRECTOR_CONTRIBUTES]; one of theirs has a timeline of [TIMELINE] entries;
 * - about 157,000 timeline entries (drafted, issued, zero to three updates, a date change, done, closed or cancelled),
 *   4,000 date requests (decided, and some pending), 30,000 digest keys of the last 60 days, and in-app action notices for
 *   every person (the day before [runDay]'s digest and older ones), so the daily task has a digest for each;
 * - an action taken report for each held meeting (revision 1, its matters arising item), with a row for each action of
 *   the committee's 8 meetings before it (about 220,000 rows), and two actions held back from the focal meeting.
 *
 * Follow-up memo links (`actions_memo`) are not written: that table is E07 T6's `actions/V3` (#230), not on this branch.
 *
 * Written as the schema owner with the tenant set (the forced policies apply), in stages: each commits and analyses what it
 * wrote before the next joins or checks foreign keys against it (an autovacuum ANALYZE of uncommitted rows misled the
 * search volume's plans, SearchVolume). [workflows] then gives every open action a running instance, cloned from the two
 * actions issued through the services ([Template]): the owner's step for those under way, verification for those done.
 */
class ActionsVolume(
    private val tenant: UUID,
    /** The committees' ids and codes, the focal one first (MeetingVolume's). */
    private val forums: List<Pair<UUID, String>>,
    private val perForum: Int,
    private val people: VolumePeople,
    private val levels: MeetingVolume.Levels,
    /** The position the director holds ("Head of Credit Risk"). */
    private val position: UUID,
    /** The tenant's zone: due dates and digest days are its days. */
    private val zone: String,
    /** The day the daily task is run for (a working day before today, so the hourly job never touches it). */
    private val runDay: LocalDate,
    /** The focal meeting (the first committee's next one): two actions are held back from it. */
    private val focal: UUID,
    /** The people who own actions: identity users, so the digest has someone to send to. */
    val persons: Int,
) {
    /** An action issued through the services, whose workflow rows each open action of its kind copies. */
    data class Template(
        val actionId: UUID,
        val instanceId: UUID,
        /** Its holder: in the copies, the copied action's holder. */
        val holder: UUID,
        val dueDate: LocalDate,
        val stepName: String?,
        val ownerStep: String?,
        /** Who holds it now; null: the same person as the action's owner (in the copies, the copy's holder). */
        val holderNow: UUID?,
    )

    private val past = perForum - MeetingVolume.FUTURE

    /** Writes the actions and everything around them, stage by stage. */
    fun generate() =
        owner { c ->
            peopleStage(c)
            actionStage(c)
            timelineStage(c)
            aroundStage(c)
            reportStage(c)
        }

    /** The volume's people: active accounts with an address, so the digest and the notices reach someone. */
    private fun peopleStage(c: Connection) {
        c.exec(
            """
            insert into identity_user (id, tenant_id, email, display_name, status, created_at, updated_at)
            select md5('avp' || p)::uuid, '$tenant'::uuid, 'avp' || p || '@volume.example', 'Volume person ' || p, 'ACTIVE', now(), now()
            from generate_series(1, $persons) p
            """,
        )
        c.commitAndAnalyse("identity_user")
    }

    private fun actionStage(c: Connection) {
        val forumRows = forums.mapIndexed { i, (id, code) -> "(${i + 1}, '$id'::uuid, '$code')" }.joinToString()
        c.exec("create temp table av_forum (f int, id uuid, code text)")
        c.exec("insert into av_forum values $forumRows")
        c.exec(
            """
            create temp table av_meeting as
            select f.f, f.id as forum_id, f.code, k, m.id as meeting_id, m.starts_at, m.label, $past - k as age
            from av_forum f
            cross join generate_series(1, $past) k
            join meeting m on m.tenant_id = '$tenant' and m.id = md5('mvm' || f.f || '-' || k)::uuid
            where m.status = 'HELD'
            """,
        )
        c.exec("analyze av_meeting")
        // Open: the committee's latest held meetings, and one in a hundred older ones (long overdue). The director's
        // rows (D: held, C: contributed to) are spread over the open ones at random.
        c.exec(
            """
            create temp table av_base as
            with b as (
                select m.*, a, md5('ava' || m.meeting_id || '-' || a)::uuid as id, abs(hashtext(m.meeting_id::text || '-' || a)) as h,
                       m.age < case when m.f = 1 then $FOCAL_OPEN_MEETINGS else $OPEN_MEETINGS end
                           or abs(hashtext(m.meeting_id::text || '-' || a)) % 100 = 0 as open
                from av_meeting m, generate_series(0, ${PER_MEETING - 1}) a
            )
            select b.*, case when open then row_number() over (partition by open order by md5(id::text)) end as rn from b
            """,
        )
        c.exec(
            """
            create temp table av_action as
            select b.*,
                   case when open and rn <= $DIRECTOR_MINE then 'D'
                        when open and rn <= ${DIRECTOR_MINE + DIRECTOR_CONTRIBUTES} then 'C' end as director_role,
                   h % 4 as u,
                   case when open then case when h % 9 = 4 then 'DONE_PENDING' when h % 4 = 0 then 'OPEN' else 'IN_PROGRESS' end
                        else case when h % 20 = 7 then 'CANCELLED' else 'CLOSED' end end as status,
                   (starts_at at time zone '$zone')::date + 30 + 15 * (a % 6) as due_date,
                   a % 7 = 5 as revised, a % 7 = 5 and k % 2 = 0 as agreed,
                   case when a <= 6 then 1 + a % 3 end as j, case when a <= 6 then a % 8 end as i,
                   starts_at + interval '2 hours' as created_at, starts_at + interval '1 day' as issued_at
            from av_base b
            """,
        )
        // The director's timeline action: one of theirs on the first committee, under way, with every update kept.
        c.exec(
            """
            update av_action set u = ${TIMELINE - 2}
            where id = (select id from av_action where director_role = 'D' and f = 1 and status = 'IN_PROGRESS' and not agreed
                        order by id limit 1)
            """,
        )
        c.exec(
            """
            alter table av_action add column holder uuid, add column closed_at timestamptz, add column done_at timestamptz,
                                  add column span_end timestamptz, add column reference text, add column number_year int,
                                  add column sequence int
            """,
        )
        c.exec(
            """
            update av_action set
                holder = case when director_role = 'D' then '${people.director}'::uuid else md5('avp' || (1 + h % $persons))::uuid end,
                closed_at = case when status in ('CLOSED', 'CANCELLED') then
                    least(now() - interval '1 day', greatest(issued_at + interval '2 days',
                          ((due_date + (h % 25 - 15)) + time '12:00') at time zone '$zone')) end
            """,
        )
        c.exec(
            """
            update av_action set
                done_at = case when status = 'CLOSED' then closed_at - interval '2 days'
                               when status = 'DONE_PENDING' then greatest(issued_at + interval '1 hour', now() - (1 + h % 20) * interval '1 day')
                          end
            """,
        )
        c.exec(
            """
            update av_action set span_end = coalesce(case when status = 'CANCELLED' then closed_at end, done_at, now() - interval '1 hour'),
                                 number_year = extract(year from issued_at at time zone '$zone')::int
            """,
        )
        c.exec(
            """
            update av_action a set sequence = s.n, reference = a.code || ' A' || s.n || '/' || a.number_year
            from (select id, row_number() over (partition by forum_id, number_year order by issued_at, a) as n from av_action) s
            where s.id = a.id
            """,
        )
        c.exec("create index on av_action (forum_id, k)")
        c.exec("create index on av_action (id)")
        c.exec("analyze av_action")
        c.exec(
            """
            insert into actions_item (id, tenant_id, forum_id, number_year, sequence, reference, source, meeting_id, item_id, source_note,
                                      source_label, item_parent_id, item_classification_id, item_restricted_to, item_title_hidden,
                                      item_members_only, title, description, classification_id, owner_kind, owner_user_id,
                                      owner_position_id, holder_user_id, priority, due_date, original_due_date, due_revisions,
                                      amber_from, status, confidence, last_update_at, stale_from, rag_override, rag_override_reason,
                                      rag_override_by, done_at, closed_at, closed_by, cancel_kind, cancel_reason, created_by,
                                      created_at, issued_by, issued_at, version)
            select a.id, '$tenant'::uuid, a.forum_id, a.number_year, a.sequence, a.reference,
                   case when a.a <= 6 then 'ITEM' when a.a = 7 then 'MEETING' else 'DIRECT' end,
                   case when a.a <= 7 then a.meeting_id end, mi.id,
                   case when a.a = 8 then 'Chair''s instruction at ' || a.label end,
                   case when a.a <= 6 then a.label || ', item ' || coalesce(ri.number, '1') when a.a = 7 then a.label else 'Direct' end,
                   mi.parent_id, mi.classification_id, mi.restricted_to, coalesce(mi.title_hidden, false), coalesce(a.j = 3, false),
                   $TITLE || ' (' || a.code || ' ' || a.k || '.' || a.a || ')',
                   'The committee asked for this with the owner''s plan, the dates and the evidence of completion.',
                   coalesce(mi.classification_id, '${levels.low}'::uuid), o.kind,
                   case when o.kind = 'PERSON' then a.holder end, case when o.kind = 'POSITION' then o.position end,
                   a.holder, case when a.h % 6 = 0 then 'HIGH' else 'NORMAL' end,
                   a.due_date + case when a.agreed then 30 else 0 end, a.due_date, case when a.agreed then 1 else 0 end,
                   a.due_date + case when a.agreed then 30 else 0 end - 14, a.status,
                   case when a.u > 0 then (array['ON_TRACK', 'ON_TRACK', 'AT_RISK', 'OFF_TRACK'])[1 + a.h % 4] end,
                   case when a.u > 0 then a.issued_at + (a.span_end - a.issued_at) * (a.u::float8 / (a.u + 1)) end,
                   (coalesce(case when a.u > 0 then a.issued_at + (a.span_end - a.issued_at) * (a.u::float8 / (a.u + 1)) end, a.issued_at)
                       at time zone '$zone')::date + 28,
                   case when a.open and a.h % 97 = 0 then 'GREEN' end,
                   case when a.open and a.h % 97 = 0 then 'The chair agreed the revised plan.' end,
                   case when a.open and a.h % 97 = 0 then '${people.chair}'::uuid end,
                   a.done_at, a.closed_at, case when a.status = 'CLOSED' then '${people.secretary}'::uuid end,
                   case when a.status = 'CANCELLED' then (array['NO_LONGER_NEEDED', 'RISK_ACCEPTED', 'DUPLICATE'])[1 + a.h % 3] end,
                   case when a.status = 'CANCELLED' then 'The committee agreed it is no longer needed.' end,
                   '${people.secretary}', a.created_at, '${people.secretary}', a.issued_at, 1 + a.u
            from av_action a
            cross join lateral (
                select case when a.director_role = 'D' and a.rn % 4 = 0 or a.director_role is distinct from 'D' and a.h % 10 = 3
                            then 'POSITION' else 'PERSON' end as kind,
                       case when a.director_role = 'D' then '$position'::uuid else md5('avpos' || (a.h % 300))::uuid end as position
            ) o
            left join meeting_item mi on a.a <= 6 and mi.tenant_id = '$tenant'
                 and mi.id = md5('mvi' || a.meeting_id || '-' || a.j || '-' || a.i)::uuid
            left join meeting_revision_item ri on a.a <= 6 and ri.tenant_id = '$tenant'
                 and ri.revision_id = md5('mvr' || a.meeting_id || '-1')::uuid and ri.item_id = mi.id
            """,
        )
        c.commitAndAnalyse("actions_item")
    }

    /** The timelines, contributors and date requests. */
    private fun timelineStage(c: Connection) {
        val update = "a.issued_at + (a.span_end - a.issued_at) * (g::float8 / (a.u + 1))"
        val rv = "case when a.agreed then 1 else 0 end"
        c.exec(
            """
            insert into actions_entry (tenant_id, action_id, seq, kind, actor_id, text, confidence, details, at)
            select '$tenant'::uuid, a.id, 1, 'DRAFTED', '${people.secretary}'::uuid, null, null,
                   jsonb_build_object('source', case when a.a <= 6 then 'ITEM' when a.a = 7 then 'MEETING' else 'DIRECT' end), a.created_at
            from av_action a
            union all
            select '$tenant'::uuid, a.id, 2, 'ISSUED', '${people.secretary}'::uuid, null, null,
                   jsonb_build_object('reference', a.reference, 'dueDate', a.due_date::text), a.issued_at
            from av_action a
            union all
            select '$tenant'::uuid, a.id, 2 + g, 'UPDATE', a.holder, 'Progress note ' || g || ': the work is under way as planned.',
                   (array['ON_TRACK', 'ON_TRACK', 'AT_RISK', 'OFF_TRACK'])[1 + a.h % 4], '{}'::jsonb, $update
            from av_action a, generate_series(1, a.u) g
            union all
            select '$tenant'::uuid, a.id, 3 + a.u, 'DUE_CHANGED', '${people.secretary}'::uuid, 'The supplier''s report comes later.', null,
                   jsonb_build_object('from', a.due_date::text, 'to', (a.due_date + 30)::text),
                   a.issued_at + (a.span_end - a.issued_at) * 0.3
            from av_action a where a.agreed
            union all
            select '$tenant'::uuid, a.id, 3 + a.u + $rv, 'DONE', a.holder, 'Done as the committee asked.', null, '{}'::jsonb, a.done_at
            from av_action a where a.done_at is not null
            union all
            select '$tenant'::uuid, a.id, 4 + a.u + $rv, 'CLOSED', '${people.secretary}'::uuid, 'Verified.', null, '{}'::jsonb, a.closed_at
            from av_action a where a.status = 'CLOSED'
            union all
            select '$tenant'::uuid, a.id, 3 + a.u + $rv, 'CANCELLED', '${people.secretary}'::uuid, null, null,
                   jsonb_build_object('kind', 'NO_LONGER_NEEDED'), a.closed_at
            from av_action a where a.status = 'CANCELLED'
            """,
        )
        c.exec(
            """
            insert into actions_contributor (id, tenant_id, action_id, user_id, added_by, added_at)
            select md5('avc' || a.id || '-' || n)::uuid, '$tenant'::uuid, a.id, x.user_id, '${people.secretary}', a.issued_at
            from av_action a
            cross join generate_series(1, case when a.director_role = 'C' then 1 when a.a % 9 = 2 then 2 when a.a % 3 = 1 then 1 else 0 end) n
            cross join lateral (
                select case when a.director_role = 'C' then '${people.director}'::uuid
                            else md5('avp' || (1 + (a.h / 7 + n * 31) % $persons))::uuid end as user_id
            ) x
            where x.user_id <> a.holder
            """,
        )
        c.exec(
            """
            insert into actions_due_request (id, tenant_id, action_id, old_date, new_date, reason, state, requested_by, requested_at,
                                             decided_by, decided_at, decision_note)
            select md5('avdr' || a.id)::uuid, '$tenant'::uuid, a.id, a.due_date, a.due_date + 30, 'The supplier''s report comes later.',
                   case when a.agreed then 'AGREED' else 'DECLINED' end, a.holder, a.issued_at + (a.span_end - a.issued_at) * 0.3,
                   '${people.secretary}'::uuid, a.issued_at + (a.span_end - a.issued_at) * 0.35,
                   case when not a.agreed then 'Keep the date: the committee needs it.' end
            from av_action a where a.revised
            union all
            select md5('avdp' || a.id)::uuid, '$tenant'::uuid, a.id, a.due_date, a.due_date + 21, 'The data arrives late this quarter.',
                   'PENDING', a.holder, a.span_end - interval '1 hour', null, null, null
            from av_action a where a.open and a.status <> 'DONE_PENDING' and not a.revised and (a.k + a.a) % 13 = 0
            """,
        )
        c.commitAndAnalyse("actions_entry", "actions_contributor", "actions_due_request")
    }

    /** The counters, the digest keys of past days, the in-app notices the next digest reads, and held-back actions. */
    private fun aroundStage(c: Connection) {
        c.exec(
            """
            insert into actions_counter (tenant_id, forum_id, year, last_number)
            select '$tenant'::uuid, forum_id, number_year, max(sequence) from av_action group by forum_id, number_year
            """,
        )
        val day = "date '$runDay'"
        c.exec(
            """
            insert into actions_digest (tenant_id, user_id, day, sent_at)
            select '$tenant'::uuid, md5('avp' || p)::uuid, $day - d, (($day - d) + time '09:05') at time zone '$zone'
            from generate_series(1, $persons) p, generate_series(1, $DIGEST_DAYS) d
            where (p + d) % 10 = 0
            union all
            select '$tenant'::uuid, u.id, $day - d, (($day - d) + time '09:01') at time zone '$zone'
            from (values ('$COMPLETE'::uuid), ('$STARTED'::uuid)) u(id), generate_series(1, $DIGEST_DAYS) d
            """,
        )
        // Each person's notices: one or two since their last digest, and three older ones within the digest's lookback.
        val cutoff = "(($day + time '09:00') at time zone '$zone')"
        c.exec(
            """
            insert into notification_item (id, tenant_id, user_id, kind, category, params, link, created_at)
            select md5('avn' || p || '-' || n)::uuid, '$tenant'::uuid, md5('avp' || p)::uuid,
                   (array['ACTION_DUE_SOON', 'ACTION_OVERDUE', 'ACTION_TO_VERIFY', 'ACTION_DUE_CHANGED'])[1 + (p + n) % 4], 'WORKFLOW',
                   jsonb_build_object('committee', 'Committee ' || (1 + p % 30), 'reference', 'C' || (1 + p % 30) || ' A' || (p + n) || '/2026'),
                   '/actions/' || md5('avn' || p || '-' || n)::uuid,
                   case when n <= 2 then $cutoff - ((p % 20) + n) * interval '1 hour'
                        else $cutoff - (24 + (p * 7 + n * 53) % 300) * interval '1 hour' end
            from generate_series(1, $persons) p, generate_series(1, 5) n
            where n <> 2 or p % 2 = 0
            """,
        )
        c.exec(
            """
            insert into actions_report_hold (id, tenant_id, action_id, meeting_id, reason, held_by, held_at)
            select md5('avhold' || id)::uuid, '$tenant'::uuid, id, '$focal', 'Reported separately to the board.', '${people.secretary}', now()
            from (select id from av_action where f = 1 and open and director_role is null order by id limit 2) x
            """,
        )
        c.commitAndAnalyse("actions_counter", "actions_digest", "notification_item", "actions_report_hold")
    }

    /** One action taken report per held meeting, frozen as it was built a week before the meeting. */
    private fun reportStage(c: Connection) {
        c.exec(
            """
            create temp table av_report as
            select m.f, m.forum_id, m.k, m.meeting_id, md5('avr' || m.meeting_id)::uuid as id,
                   lag(m.meeting_id) over w as previous_id, lag(m.starts_at) over w as since, m.starts_at - interval '7 days' as built_at
            from av_meeting m window w as (partition by m.f order by m.k)
            """,
        )
        c.exec(
            """
            create temp table av_report_row as
            select r.id as report_id, a.id as action_id, a.due_date, a.h,
                   case when a.status = 'CANCELLED' and a.closed_at < r.built_at then 'CANCELLED'
                        when a.closed_at < r.built_at then 'CLOSED'
                        when a.done_at < r.built_at then 'TO_CLOSE'
                        when a.due_date < (r.built_at at time zone '$zone')::date then 'OVERDUE' else 'OPEN' end as section
            from av_report r
            join av_action a on a.forum_id = r.forum_id and a.k between r.k - $REPORT_MEETINGS and r.k - 1 and a.issued_at < r.built_at
            """,
        )
        c.exec("analyze av_report_row")
        c.exec(
            """
            insert into actions_report (id, tenant_id, forum_id, meeting_id, revision, item_id, previous_meeting_id, since, built_at,
                                        row_count, more_count, held_count, content_sha256)
            select r.id, '$tenant'::uuid, r.forum_id, r.meeting_id, 1, md5('mvi' || r.meeting_id || '-0-0')::uuid, r.previous_id, r.since,
                   r.built_at, (select count(*) from av_report_row x where x.report_id = r.id), 0, 0,
                   md5(r.id::text) || md5(r.meeting_id::text)
            from av_report r
            """,
        )
        c.commitAndAnalyse("actions_report")
        c.exec(
            """
            insert into actions_report_row (tenant_id, report_id, position, section, action_id, snapshot, item_id, item_parent_id,
                                            item_classification_id, item_restricted_to, item_title_hidden, item_members_only,
                                            classification_id)
            select '$tenant'::uuid, x.report_id,
                   row_number() over (partition by x.report_id
                       order by array_position(array['TO_CLOSE', 'OVERDUE', 'OPEN', 'CLOSED', 'CANCELLED'], x.section), x.due_date, x.action_id),
                   x.section, x.action_id,
                   jsonb_build_object('reference', ai.reference, 'sourceLabel', ai.source_label, 'title', ai.title,
                                      'owner', 'Volume person ' || (1 + x.h % $persons), 'dueDate', ai.due_date,
                                      'originalDueDate', ai.original_due_date, 'dueRevisions', ai.due_revisions,
                                      'rag', case x.section when 'TO_CLOSE' then 'TO_VERIFY' when 'OVERDUE' then 'OVERDUE'
                                                 when 'OPEN' then 'ON_TRACK' else x.section end,
                                      'status', case x.section when 'TO_CLOSE' then 'DONE_PENDING' when 'OVERDUE' then 'IN_PROGRESS'
                                                    when 'OPEN' then 'IN_PROGRESS' else x.section end),
                   ai.item_id, ai.item_parent_id, ai.item_classification_id, ai.item_restricted_to, ai.item_title_hidden,
                   ai.item_members_only, ai.classification_id
            from av_report_row x
            join actions_item ai on ai.tenant_id = '$tenant' and ai.id = x.action_id
            """,
        )
        c.commitAndAnalyse("actions_report_row")
    }

    /**
     * Gives each open action without a workflow a running instance: a copy of [underway]'s rows (the owner's step, its task
     * held by the action's holder, due at the end of its date) or, for one done, of [done]'s (verification by the
     * secretaries). Each row is copied through its JSON text with every id of the template replaced by a new one (derived
     * from the action and the old id), the template's action by the action and its holder by the action's; then the due
     * times and timers move with the action's date.
     */
    fun workflows(
        underway: Template,
        done: Template,
    ) = owner { c ->
        val templates = mapOf(TEMPLATE_UNDERWAY to underway, TEMPLATE_DONE to done)
        c.exec(
            """
            create temp table av_wf as
            select a.id as action_id, case when a.status = 'DONE_PENDING' then '$TEMPLATE_DONE' else '$TEMPLATE_UNDERWAY' end as tpl,
                   case when a.status = 'DONE_PENDING' then '${done.instanceId}' else '${underway.instanceId}' end::uuid as tpl_instance,
                   a.holder_user_id as holder,
                   (a.due_date - case when a.status = 'DONE_PENDING' then date '${done.dueDate}' else date '${underway.dueDate}' end)
                       * interval '1 day' as shift
            from actions_item a
            where a.status in ('OPEN', 'IN_PROGRESS', 'DONE_PENDING') and a.workflow_instance_id is null and a.reference is not null
            """,
        )
        c.exec("analyze av_wf")
        templates.forEach { (key, template) ->
            val ids = c.ids(template.instanceId)
            var copied = "to_jsonb(s)::text"
            ids.forEach { id -> copied = "replace($copied, '$id', md5(w.action_id::text || '$id')::uuid::text)" }
            copied = "replace(replace($copied, '${template.actionId}', w.action_id::text), '${template.holder}', w.holder::text)"
            WORKFLOW_TABLES.forEach { table ->
                val instance = if (table == "workflow_instance") "s.id" else "s.instance_id"
                c.exec(
                    """
                    insert into $table
                    select (jsonb_populate_record(null::$table, ($copied)::jsonb)).*
                    from $table s join av_wf w on w.tpl = '$key' and $instance = w.tpl_instance
                    """,
                )
            }
        }
        val copy = "md5(w.action_id::text || w.tpl_instance::text)::uuid"
        c.exec("update workflow_task t set due_at = t.due_at + w.shift from av_wf w where t.instance_id = $copy and t.due_at is not null")
        c.exec("update workflow_timer t set fires_at = t.fires_at + w.shift from av_wf w where t.instance_id = $copy")
        val doneHolder = done.holderNow?.let { "'$it'::uuid" } ?: "a.holder_user_id"
        c.exec(
            """
            update actions_item a set
                workflow_instance_id = $copy,
                step_name = case w.tpl when '$TEMPLATE_UNDERWAY' then ${text(underway.stepName)} else ${text(done.stepName)} end,
                owner_step = ${text(underway.ownerStep)},
                holder_user_id = case w.tpl when '$TEMPLATE_UNDERWAY' then a.holder_user_id else $doneHolder end,
                version = a.version + 1
            from av_wf w
            where a.tenant_id = '$tenant' and a.id = w.action_id
            """,
        )
        c.commitAndAnalyse("actions_item", *WORKFLOW_TABLES.toTypedArray())
    }

    /**
     * Vacuums and analyses everything the volume wrote, so reads are timed on settled tables (visibility maps set) with no
     * autovacuum of the fresh rows running beside the measurement (as SearchVolume does).
     */
    fun settle() =
        DriverManager.getConnection(TestDatabase.jdbcUrl, "memo_migrator", TestDatabase.migratorPassword).use { c ->
            c.createStatement().use { it.execute("set statement_timeout = '20min'") }
            c.createStatement().use { it.execute("vacuum (analyze) ${(TABLES + WORKFLOW_TABLES).joinToString()}") }
        }

    /** The ids of [instanceId]'s rows in every workflow table. */
    private fun Connection.ids(instanceId: UUID): List<String> =
        WORKFLOW_TABLES.flatMap { table ->
            val column = if (table == "workflow_instance") "id" else "instance_id"
            prepareStatement("select id::text from $table where $column = ?").use { st ->
                st.setObject(1, instanceId)
                st.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
            }
        }

    private fun text(value: String?) = value?.let { "'${it.replace("'", "''")}'" } ?: "null"

    /** The action with the long timeline. */
    fun timelineAction(): UUID =
        owner { c ->
            c.prepareStatement("select action_id from actions_entry group by action_id having count(*) >= $TIMELINE limit 1").use { st ->
                st.executeQuery().use { rs ->
                    check(rs.next()) { "no action with $TIMELINE entries" }
                    rs.getObject(1, UUID::class.java)
                }
            }
        }

    /** The row counts of the tables the volume wrote. */
    fun counts(): Map<String, Long> =
        owner { c ->
            (TABLES + WORKFLOW_TABLES).associateWith { table ->
                c.createStatement().use { st ->
                    st.executeQuery("select count(*) from $table").use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }
            }
        }

    /** [block] on the schema owner's connection, in the tenant, in stages; committed at its end. */
    private fun <T> owner(block: (Connection) -> T): T =
        DriverManager.getConnection(TestDatabase.jdbcUrl, "memo_migrator", TestDatabase.migratorPassword).use { c ->
            c.autoCommit = false
            c.bind()
            val result = block(c)
            c.commit()
            result
        }

    /** The settings of each transaction: they are local, so every stage sets them again. */
    private fun Connection.bind() {
        createStatement().use { it.execute("set local lock_timeout = '30s'") }
        createStatement().use { it.execute("set local statement_timeout = '20min'") }
        prepareStatement("select set_config('app.tenant_id', ?, true)").use {
            it.setString(1, tenant.toString())
            it.execute()
        }
    }

    /** Commits a stage, then analyses the [tables] it wrote in the next stage's transaction. */
    private fun Connection.commitAndAnalyse(vararg tables: String) {
        commit()
        bind()
        createStatement().use { it.execute("analyze ${tables.joinToString()}") }
    }

    private fun Connection.exec(sql: String) {
        val started = System.nanoTime()
        createStatement().use { it.execute(sql) }
        val ms = (System.nanoTime() - started) / NANOS_PER_MILLI
        if (ms >= SLOW_MS) println("ActionsVolume $ms ms: ${sql.trim().lineSequence().first()}")
    }

    companion object {
        const val PER_MEETING = 9
        private const val TEMPLATE_UNDERWAY = "A"
        private const val TEMPLATE_DONE = "B"
        const val OPEN_MEETINGS = 11
        const val FOCAL_OPEN_MEETINGS = 32
        const val DIRECTOR_MINE = 200
        const val DIRECTOR_CONTRIBUTES = 50
        const val TIMELINE = 100
        const val PERSONS = 5_000
        const val DIGEST_DAYS = 60
        const val REPORT_MEETINGS = 8
        private const val SLOW_MS = 1_000L
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val COMPLETE = "00000000-0000-0000-0000-000000000000"
        private const val STARTED = "00000000-0000-0000-0000-000000000001"

        /** An action's title, in turn: words the tracker's text filter finds. */
        private const val TITLE =
            "(array['Review the liquidity contingency plan', 'Report the revised collateral valuation', " +
                "'Update the credit limit framework', 'Close the internal audit findings', 'Present the capital adequacy forecast', " +
                "'Test the cyber incident response', 'Assess the critical vendor exposure', 'Revise the treasury hedging policy', " +
                "'Complete the compliance attestation', 'Strengthen the IT resilience controls', " +
                "'Investigate the card fraud losses', 'Reprice the deposit products'])[1 + a.h % 12]"

        /** The tables the volume writes (identity and notification rows aside). */
        val TABLES =
            listOf(
                "actions_item",
                "actions_entry",
                "actions_contributor",
                "actions_due_request",
                "actions_counter",
                "actions_digest",
                "actions_report",
                "actions_report_row",
                "actions_report_hold",
                "notification_item",
                "identity_user",
            )

        /** The workflow rows each open action's instance copies, in the order their foreign keys need. */
        val WORKFLOW_TABLES =
            listOf("workflow_instance", "workflow_round", "workflow_step", "workflow_task", "workflow_action", "workflow_timer")
    }
}
