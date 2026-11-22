-- Phase 4 E08 T1 (ADR-0069, design E08 §1, §6, §7): the decision register. One row per decision (E06's minutes_decision
-- with its current entry), for every committee of the tenant, in a search table of its own as ADR-0054's memo search:
-- RLS enabled but not forced, owned by the schema owner, no privilege for memo_app, reached only through the SECURITY
-- DEFINER functions below. Derived data: written by the search module's listeners in the transaction of each change
-- (decisions, resolutions, their release, the minutes' approval, agenda revisions, meetings) and by a backfill.
--
-- What a row never holds, on purpose (design E08 §1.1): the secretary's notes, the minutes' discussion, papers or their
-- text, the natures of declared interests, the names of dissenters or of anyone in the vote (only whether there was
-- dissent), and quorum override reasons. Recusals are not stored either: the viewer's recused items are computed in the
-- request's transaction and passed in (ADR-0069 C1), so a declaration applies the moment it is committed.
--
-- Expand only: a new table and new functions, which an application one release older never calls.

CREATE TABLE search_decision (
    tenant_id               uuid        NOT NULL,
    decision_id             uuid        NOT NULL,
    forum_id                uuid        NOT NULL,
    forum_type_id           uuid        NOT NULL,
    meeting_id              uuid        NOT NULL,
    -- "BRC 3/2026"; null until the meeting's agenda is first published (a meeting numbered later).
    meeting_label           text        CHECK (char_length(meeting_label) <= 200),
    -- The meeting's local date in its own time zone.
    meeting_date            date        NOT NULL,
    item_id                 uuid        NOT NULL,
    item_parent_id          uuid,
    item_number             text        CHECK (char_length(item_number) <= 50),
    -- The higher of the item's and the meeting's level, as a rank (ADR-0027).
    classification_rank     int         NOT NULL CHECK (classification_rank >= 0),
    -- The item rule's columns (ADR-0061): null when the item is not restricted.
    item_restricted_to      uuid[]      CHECK (cardinality(item_restricted_to) <= 500),
    item_title_hidden       boolean     NOT NULL,
    item_members_only       boolean     NOT NULL,
    title                   text        NOT NULL CHECK (char_length(title) <= 2000),
    outcome_kind            text        NOT NULL CHECK (outcome_kind IN ('APPROVED', 'APPROVED_WITH_CONDITIONS', 'REJECTED',
                                                                         'DEFERRED', 'RETURNED', 'REFERRED', 'NOTED', 'WITHDRAWN')),
    outcome_label           text        NOT NULL CHECK (char_length(outcome_label) <= 200),
    -- The outcome kind is a resolution (ADR-0056): it has, or will have at the meeting's end, a number.
    is_resolution           boolean     NOT NULL,
    -- Its number in force, else the last one voided (a void number stays in the register and is never given again).
    resolution_id           uuid,
    resolution_number       text        CHECK (char_length(resolution_number) <= 100),
    resolution_state        text        CHECK (resolution_state IN ('IN_FORCE', 'VOID')),
    number_period           text,
    number_sequence         bigint,
    -- The number as search keys, as ADR-0054 does for memos: BRC-2026-014 and 14.
    ref_norm                text,
    ref_tail                text,
    -- E06 §1.1, derived: RECORDED, NUMBERED, RELEASED, FINAL.
    state                   text        NOT NULL CHECK (state IN ('RECORDED', 'NUMBERED', 'RELEASED', 'FINAL')),
    decided_at              timestamptz NOT NULL,
    final_at                timestamptz,
    -- The current entry's number: 1 as recorded, more after corrections.
    entry_seq               int         NOT NULL CHECK (entry_seq >= 1),
    corrected               boolean     GENERATED ALWAYS AS (entry_seq > 1) STORED,
    decision_text           text        CHECK (char_length(decision_text) <= 4000),
    conditions              text[]      NOT NULL DEFAULT '{}' CHECK (cardinality(conditions) <= 20),
    has_conditions          boolean     GENERATED ALWAYS AS (cardinality(conditions) > 0) STORED,
    valid_until             date,
    -- Only that someone dissented by name: never who.
    has_dissent             boolean     NOT NULL,
    vote_mode               text        NOT NULL CHECK (vote_mode IN ('CONSENSUS', 'VOTE')),
    ratification_required   boolean     NOT NULL,
    ratified_by_decision_id uuid,
    -- The item's submission and its memo (E02), with the memo's type and number.
    submission_id           uuid,
    memo_id                 uuid,
    memo_type_id            uuid,
    memo_number             text        CHECK (char_length(memo_number) <= 100),
    memo_ref_norm           text,
    -- The first submission of the chain (a resubmission, a referral, a deferral keeps its submission): time to decision.
    chain_submitted_at      timestamptz,
    deferred                boolean     GENERATED ALWAYS AS (outcome_kind = 'DEFERRED') STORED,
    -- DEFERRED decisions earlier in the same chain.
    prior_deferrals         int         NOT NULL DEFAULT 0 CHECK (prior_deferrals >= 0),
    lang                    text        NOT NULL DEFAULT 'english' CHECK (lang IN ('english', 'simple')),
    -- The outcome's label, the decision's text and its conditions, at most 16,000 characters (written by the function).
    body                    text        NOT NULL CHECK (char_length(body) <= 16000),
    -- Title A, the resolution and memo numbers B, the body C. Never a regconfig column: pg_upgrade refuses those.
    tsv                     tsvector    GENERATED ALWAYS AS (
        setweight(to_tsvector(CASE lang WHEN 'english' THEN 'english'::regconfig ELSE 'simple'::regconfig END, title), 'A') ||
        setweight(to_tsvector('simple'::regconfig, coalesce(ref_norm, '') || ' ' || coalesce(memo_ref_norm, '')), 'B') ||
        setweight(to_tsvector(CASE lang WHEN 'english' THEN 'english'::regconfig ELSE 'simple'::regconfig END, body), 'C')
    ) STORED,
    updated_at              timestamptz NOT NULL,
    CHECK ((resolution_id IS NULL) = (resolution_number IS NULL)),
    CHECK ((resolution_id IS NULL) = (resolution_state IS NULL)),
    -- Not a foreign key to minutes_decision: another module's table (ADR-0007). search_decision_unindexed finds the gaps.
    PRIMARY KEY (tenant_id, decision_id)
);
CREATE INDEX search_decision_tsv_idx ON search_decision USING gin (tsv) WITH (fastupdate = on, gin_pending_list_limit = 2048);
-- Newest (a backward scan) and oldest first across committees, and within one committee.
CREATE INDEX search_decision_date_idx ON search_decision (tenant_id, meeting_date, decided_at, decision_id);
CREATE INDEX search_decision_forum_date_idx ON search_decision (tenant_id, forum_id, meeting_date, decided_at, decision_id);
-- Number order within a committee.
CREATE INDEX search_decision_number_idx ON search_decision (tenant_id, forum_id, number_period, number_sequence, decision_id)
    WHERE resolution_number IS NOT NULL;
CREATE INDEX search_decision_ref_idx ON search_decision (tenant_id, ref_norm);
CREATE INDEX search_decision_ref_tail_idx ON search_decision (tenant_id, ref_tail);
CREATE INDEX search_decision_memo_ref_idx ON search_decision (tenant_id, memo_ref_norm);
-- The listeners' set-based updates.
CREATE INDEX search_decision_meeting_idx ON search_decision (tenant_id, meeting_id);
CREATE INDEX search_decision_item_idx ON search_decision (tenant_id, item_id);

-- Enabled, not forced: the owner (these functions) bypasses it; memo_app has no privileges at all (ADR-0054).
ALTER TABLE search_decision ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON search_decision FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));
REVOKE ALL ON search_decision FROM memo_app;

-- Writes a decision's row as it is now (the indexer reads every value from the owning modules' published APIs).
CREATE FUNCTION search_put_decision(
    p_tenant uuid, p_decision uuid, p_forum uuid, p_forum_type uuid, p_meeting uuid, p_meeting_label text,
    p_meeting_date date, p_item uuid, p_item_parent uuid, p_item_number text, p_rank int, p_restricted_to uuid[],
    p_title_hidden boolean, p_members_only boolean, p_title text, p_outcome_kind text, p_outcome_label text,
    p_is_resolution boolean, p_resolution uuid, p_resolution_number text, p_resolution_state text, p_number_period text,
    p_number_sequence bigint, p_ref_norm text, p_ref_tail text, p_state text, p_decided_at timestamptz,
    p_final_at timestamptz, p_entry_seq int, p_text text, p_conditions text[], p_valid_until date, p_has_dissent boolean,
    p_vote_mode text, p_ratification_required boolean, p_ratified_by uuid, p_submission uuid, p_memo uuid,
    p_memo_type uuid, p_memo_number text, p_memo_ref_norm text, p_chain_submitted_at timestamptz, p_prior_deferrals int,
    p_updated timestamptz
) RETURNS void
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = memo, pg_catalog
AS $$
DECLARE
    conds text[] := coalesce(p_conditions, '{}');
BEGIN
    PERFORM search_tenant(p_tenant);
    INSERT INTO search_decision (
        tenant_id, decision_id, forum_id, forum_type_id, meeting_id, meeting_label, meeting_date, item_id, item_parent_id,
        item_number, classification_rank, item_restricted_to, item_title_hidden, item_members_only, title, outcome_kind,
        outcome_label, is_resolution, resolution_id, resolution_number, resolution_state, number_period, number_sequence,
        ref_norm, ref_tail, state, decided_at, final_at, entry_seq, decision_text, conditions, valid_until, has_dissent,
        vote_mode, ratification_required, ratified_by_decision_id, submission_id, memo_id, memo_type_id, memo_number,
        memo_ref_norm, chain_submitted_at, prior_deferrals, body, updated_at)
    VALUES (
        p_tenant, p_decision, p_forum, p_forum_type, p_meeting, p_meeting_label, p_meeting_date, p_item, p_item_parent,
        p_item_number, p_rank, p_restricted_to, coalesce(p_title_hidden, false), coalesce(p_members_only, false),
        coalesce(p_title, ''), p_outcome_kind, p_outcome_label, p_is_resolution, p_resolution, p_resolution_number,
        p_resolution_state, p_number_period, p_number_sequence, p_ref_norm, p_ref_tail, p_state, p_decided_at, p_final_at,
        p_entry_seq, p_text, conds, p_valid_until, coalesce(p_has_dissent, false), p_vote_mode,
        coalesce(p_ratification_required, false), p_ratified_by, p_submission, p_memo, p_memo_type, p_memo_number,
        p_memo_ref_norm, p_chain_submitted_at, coalesce(p_prior_deferrals, 0),
        left(concat_ws(E'\n', p_outcome_label, p_text, nullif(array_to_string(conds, E'\n'), '')), 16000), p_updated)
    ON CONFLICT (tenant_id, decision_id) DO UPDATE SET
        forum_id = excluded.forum_id, forum_type_id = excluded.forum_type_id, meeting_id = excluded.meeting_id,
        meeting_label = excluded.meeting_label, meeting_date = excluded.meeting_date, item_id = excluded.item_id,
        item_parent_id = excluded.item_parent_id, item_number = excluded.item_number,
        classification_rank = excluded.classification_rank, item_restricted_to = excluded.item_restricted_to,
        item_title_hidden = excluded.item_title_hidden, item_members_only = excluded.item_members_only,
        title = excluded.title, outcome_kind = excluded.outcome_kind, outcome_label = excluded.outcome_label,
        is_resolution = excluded.is_resolution, resolution_id = excluded.resolution_id,
        resolution_number = excluded.resolution_number, resolution_state = excluded.resolution_state,
        number_period = excluded.number_period, number_sequence = excluded.number_sequence, ref_norm = excluded.ref_norm,
        ref_tail = excluded.ref_tail, state = excluded.state, decided_at = excluded.decided_at,
        final_at = excluded.final_at, entry_seq = excluded.entry_seq, decision_text = excluded.decision_text,
        conditions = excluded.conditions, valid_until = excluded.valid_until, has_dissent = excluded.has_dissent,
        vote_mode = excluded.vote_mode, ratification_required = excluded.ratification_required,
        ratified_by_decision_id = excluded.ratified_by_decision_id, submission_id = excluded.submission_id,
        memo_id = excluded.memo_id, memo_type_id = excluded.memo_type_id, memo_number = excluded.memo_number,
        memo_ref_norm = excluded.memo_ref_norm, chain_submitted_at = excluded.chain_submitted_at,
        prior_deferrals = excluded.prior_deferrals, body = excluded.body, updated_at = excluded.updated_at;
END $$;

-- The minutes of p_meeting were approved (E06 T6): its decisions are final, in one set-based write. Returns the rows.
CREATE FUNCTION search_put_decisions_final(p_tenant uuid, p_meeting uuid, p_final_at timestamptz, p_updated timestamptz)
    RETURNS int
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = memo, pg_catalog
AS $$
DECLARE n int;
BEGIN
    PERFORM search_tenant(p_tenant);
    UPDATE search_decision SET final_at = coalesce(final_at, p_final_at), state = 'FINAL', updated_at = p_updated
    WHERE tenant_id = p_tenant AND meeting_id = p_meeting AND (state <> 'FINAL' OR final_at IS NULL);
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN n;
END $$;

/*
 * A new agenda revision of p_meeting was published, or the meeting changed (its label, date or level): the meeting's
 * columns on every row of the meeting, and each decided item's columns from p_items, a JSON array of
 * {item, parent, number, title, rank, restrictedTo (array or null), titleHidden, membersOnly}; rank is the higher of the
 * item's and the meeting's level. Two set-based updates on (tenant_id, meeting_id) and (tenant_id, item_id). Returns the rows.
 */
CREATE FUNCTION search_put_decision_items(
    p_tenant uuid, p_meeting uuid, p_label text, p_date date, p_items jsonb, p_updated timestamptz
) RETURNS int
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = memo, pg_catalog
AS $$
DECLARE n int;
BEGIN
    PERFORM search_tenant(p_tenant);
    IF jsonb_typeof(coalesce(p_items, '[]'::jsonb)) <> 'array' OR jsonb_array_length(coalesce(p_items, '[]'::jsonb)) > 1000 THEN
        RAISE EXCEPTION 'search: items are an array of at most 1000' USING ERRCODE = 'invalid_parameter_value';
    END IF;
    UPDATE search_decision SET meeting_label = p_label, meeting_date = p_date, updated_at = p_updated
    WHERE tenant_id = p_tenant AND meeting_id = p_meeting
      AND (meeting_label, meeting_date) IS DISTINCT FROM (p_label, p_date);
    UPDATE search_decision d SET
        item_parent_id = i.parent, item_number = i.number, title = coalesce(i.title, ''), classification_rank = i.rank,
        item_restricted_to = CASE WHEN jsonb_typeof(i."restrictedTo") = 'array'
                                  THEN ARRAY(SELECT jsonb_array_elements_text(i."restrictedTo")::uuid) END,
        item_title_hidden = coalesce(i."titleHidden", false), item_members_only = coalesce(i."membersOnly", false),
        updated_at = p_updated
    FROM jsonb_to_recordset(coalesce(p_items, '[]'::jsonb))
        AS i(item uuid, parent uuid, number text, title text, rank int, "restrictedTo" jsonb, "titleHidden" boolean,
             "membersOnly" boolean)
    WHERE d.tenant_id = p_tenant AND d.meeting_id = p_meeting AND d.item_id = i.item AND i.rank IS NOT NULL;
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN n;
END $$;

/*
 * The backfill's work list (design E08 §1.2, §9): up to p_limit decisions of the tenant, in id order, whose row is missing
 * or behind their source: another current entry (a correction the row missed) or the minutes approved since. Reads the
 * minutes module's head rows under their forced RLS (the caller's tenant).
 */
CREATE FUNCTION search_decision_unindexed(p_tenant uuid, p_limit int) RETURNS SETOF uuid
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = memo, pg_catalog
AS $$
BEGIN
    PERFORM search_tenant(p_tenant);
    RETURN QUERY
    SELECT md.id FROM minutes_decision md
    LEFT JOIN search_decision sd ON sd.tenant_id = p_tenant AND sd.decision_id = md.id
    WHERE md.tenant_id = p_tenant
      AND (sd.decision_id IS NULL OR sd.entry_seq <> md.current_seq OR (sd.final_at IS NULL) <> (md.final_at IS NULL))
    ORDER BY md.id
    LIMIT least(greatest(p_limit, 1), 1000);
END $$;

/*
 * The one rule (ADR-0069, design E08 §1.3) on one row, for a viewer whose parameters the application computed from live
 * state (DecisionRegisterAccess): NULL (nothing: outside the viewer's committees or above their clearance), 'LINE' (the
 * restricted line: recused, or an item they may not see in full) or 'FULL'.
 *   officers: committees where the viewer is a secretary, the chair, or the vice-chair when there is no chair;
 *   officer_all: forums.manage; members: committees where they hold a seat (chair, vice-chair, member);
 *   reader: forum.read; recused: the items they may not see in full because of a declaration (their sub-items too).
 * Plain SQL without a SET clause, so the planner inlines it. Null parameters are treated as "none" (fail closed).
 */
CREATE FUNCTION search_decision_level(
    r_forum uuid, r_rank int, r_item uuid, r_parent uuid, r_restricted uuid[], r_hidden boolean, r_members_only boolean,
    p_viewer uuid, p_rank int, p_officer_all boolean, p_officers uuid[], p_members uuid[], p_reader boolean, p_recused uuid[]
) RETURNS text
    LANGUAGE sql IMMUTABLE SECURITY INVOKER
AS $$
    SELECT CASE
        WHEN NOT coalesce(p_reader OR p_officer_all OR r_forum = ANY (p_officers) OR r_forum = ANY (p_members), false)
          OR coalesce(r_rank > p_rank, true) THEN NULL
        WHEN coalesce(r_item = ANY (p_recused) OR r_parent = ANY (p_recused), false) THEN 'LINE'
        WHEN coalesce(p_officer_all OR r_forum = ANY (p_officers), false) THEN 'FULL'
        WHEN coalesce(r_forum = ANY (p_members) AND (r_restricted IS NULL OR p_viewer = ANY (r_restricted)), false) THEN 'FULL'
        WHEN coalesce(p_reader AND r_restricted IS NULL AND NOT r_hidden AND NOT r_members_only, false) THEN 'FULL'
        ELSE 'LINE'
    END
$$;

/*
 * The query of search_find_decisions (design E08 §1.3, §1.4), as text, so that search_find_decisions plans it on every call
 * with the call's values (EXECUTE ... USING, ADR-0054's amendment): a committee member's scope and a reader's are far
 * apart, and a word in most decisions or in five. The EXPLAIN gate test prepares the same text.
 *   $1 tenant, $2 viewer, $3 clearance rank, $4 officer of all, $5 officer committees, $6 member committees, $7 reader,
 *   $8 recused items, $9 tsquery, $10 number normal form, $11 number tail, $12 committees, $13 committee types,
 *   $14 from, $15 to, $16 outcomes, $17 resolutions only, $18 states, $19 memo types, $20 flags, $21 today,
 *   $22 any content filter, $23-$28 the cursor (meeting date, decided at, score, period, sequence, decision),
 *   $29 page rows, $30 text candidates, $31 rows counted, $32 every committee in scope, $33 the committees in scope
 * p_text: words or a number were given (candidates first, then order); otherwise the order's index is read in order.
 * Text and content filters match FULL rows only; committee, type, dates, state and "resolutions only" match FULL and LINE
 * rows; a number lookup matches both (the number is on the line); a memo number FULL rows only (design E08 §1.3).
 */
CREATE FUNCTION search_find_decisions_sql(p_text boolean, p_sort text) RETURNS text
    LANGUAGE plpgsql IMMUTABLE SECURITY INVOKER SET search_path = memo, pg_catalog
AS $f$
DECLARE
    lvl text := $l$search_decision_level(d.forum_id, d.classification_rank, d.item_id, d.item_parent_id, d.item_restricted_to,
        d.item_title_hidden, d.item_members_only, $2, $3, $4, $5, $6, $7, $8)$l$;
    keep text := $k$d.tenant_id = $1 AND ($32 OR d.forum_id = ANY ($33)) AND d.classification_rank <= $3
        AND ($12 IS NULL OR d.forum_id = ANY ($12)) AND ($13 IS NULL OR d.forum_type_id = ANY ($13))
        AND ($14 IS NULL OR d.meeting_date >= $14) AND ($15 IS NULL OR d.meeting_date <= $15)
        AND (NOT $17 OR d.resolution_number IS NOT NULL)
        AND ($18 IS NULL OR d.state = ANY ($18) OR ('VOID' = ANY ($18) AND d.resolution_state = 'VOID'))$k$;
    content text := $c$($16 IS NULL OR d.outcome_kind = ANY ($16)) AND ($19 IS NULL OR d.memo_type_id = ANY ($19))
        AND ($20 IS NULL OR ((NOT 'WITH_CONDITIONS' = ANY ($20) OR d.has_conditions)
            AND (NOT 'WITH_DISSENT' = ANY ($20) OR d.has_dissent)
            AND (NOT 'TO_BE_RATIFIED' = ANY ($20) OR (d.ratification_required AND d.ratified_by_decision_id IS NULL))
            AND (NOT 'CORRECTED' = ANY ($20) OR d.corrected)
            AND (NOT 'VALIDITY_PASSED' = ANY ($20) OR d.valid_until < $21)))$c$;
    matches text;
    order_by text;
    keyset text;
    cols text := $s$d.decision_id, d.forum_id, d.forum_type_id, d.meeting_id, d.meeting_label, d.meeting_date, d.item_id,
        d.item_number, d.state, d.resolution_id, d.resolution_number, d.resolution_state, d.number_period, d.number_sequence,
        d.decided_at, d.entry_seq, d.item_restricted_to, d.item_parent_id, d.title, d.outcome_kind, d.outcome_label,
        d.is_resolution, d.decision_text, d.conditions, d.valid_until, d.has_dissent, d.vote_mode, d.ratification_required,
        d.ratified_by_decision_id, d.corrected, d.final_at, d.submission_id, d.memo_id, d.memo_type_id, d.memo_number$s$;
    q text;
BEGIN
    IF p_sort = 'NUMBER' THEN
        -- Number order lists numbered decisions of one committee (the caller checks there is one).
        keep := keep || ' AND d.resolution_number IS NOT NULL AND d.forum_id = $12[1]';
    END IF;
    -- No content filter: every row of the scope within the clearance matches (keep already holds the level's scope and
    -- clearance, so the planner sees a constant); with one, only FULL rows whose content passes.
    matches := format('(NOT $22 OR lv = %L AND %s)', 'FULL', content);
    order_by := CASE p_sort
        WHEN 'RELEVANCE' THEN 'score DESC, decision_id'
        WHEN 'OLDEST' THEN 'meeting_date, decided_at, decision_id'
        WHEN 'NUMBER' THEN 'number_period, number_sequence, decision_id'
        ELSE 'meeting_date DESC, decided_at DESC, decision_id DESC' END;
    keyset := CASE p_sort
        WHEN 'RELEVANCE' THEN '($28 IS NULL OR score < $25 OR (score = $25 AND decision_id > $28))'
        WHEN 'OLDEST' THEN '($28 IS NULL OR (meeting_date, decided_at, decision_id) > ($23, $24, $28))'
        WHEN 'NUMBER' THEN '($28 IS NULL OR (number_period, number_sequence, decision_id) > ($26, $27, $28))'
        ELSE '($28 IS NULL OR (meeting_date, decided_at, decision_id) < ($23, $24, $28))' END;
    IF p_text THEN
        q := format($q$
    WITH txt AS (
        SELECT d.decision_id, ts_rank_cd(d.tsv, $9) AS s FROM search_decision d
        WHERE $9 IS NOT NULL AND %1$s AND d.tsv @@ $9 AND %2$s = 'FULL' AND %3$s
        LIMIT $30
    ),
    refs AS (
        SELECT d.decision_id, 10.0::real AS s FROM search_decision d CROSS JOIN LATERAL (SELECT %2$s AS lv) l
        WHERE ($10 IS NOT NULL OR $11 IS NOT NULL) AND %1$s AND (d.ref_norm = $10 OR d.ref_tail = $11) AND %4$s
        LIMIT $30
    ),
    memos AS (
        SELECT d.decision_id, 10.0::real AS s FROM search_decision d
        WHERE $10 IS NOT NULL AND %1$s AND d.memo_ref_norm = $10 AND %2$s = 'FULL' AND %3$s
        LIMIT $30
    ),
    hits AS (
        SELECT h.decision_id, sum(h.s)::real AS score
        FROM (SELECT * FROM txt UNION ALL SELECT * FROM refs UNION ALL SELECT * FROM memos) h
        GROUP BY h.decision_id
    ),
    matched AS (
        SELECT %5$s, l.lv, hits.score FROM hits
        JOIN search_decision d ON d.tenant_id = $1 AND d.decision_id = hits.decision_id
        CROSS JOIN LATERAL (SELECT %2$s AS lv) l
        WHERE l.lv IS NOT NULL
    ),
    page AS (SELECT * FROM matched WHERE %6$s ORDER BY %7$s LIMIT $29),
    counted AS (SELECT m.forum_id, m.meeting_date, m.outcome_kind, m.lv FROM matched m),
    capped AS (SELECT (SELECT count(*) FROM txt) >= $30 AS c)
    $q$, keep, lvl, content, matches, cols, keyset, order_by);
    ELSE
        q := format($q$
    WITH page AS (
        SELECT %5$s, l.lv, NULL::real AS score FROM search_decision d CROSS JOIN LATERAL (SELECT %2$s AS lv) l
        WHERE %1$s AND %4$s AND %6$s
        ORDER BY %7$s LIMIT $29
    ),
    counted AS (
        SELECT d.forum_id, d.meeting_date, d.outcome_kind, l.lv FROM search_decision d CROSS JOIN LATERAL (SELECT %2$s AS lv) l
        WHERE %1$s AND %4$s
        ORDER BY %7$s
        LIMIT $31
    ),
    capped AS (SELECT false AS c)
    $q$, keep, lvl, content, matches, cols, keyset, order_by);
    END IF;
    -- One statement: the page (LINE rows without their content), then one row with the total and the facets, all from the
    -- same rows under the same rule. Outcome facets count FULL rows only (a line's outcome is unknown to the viewer).
    RETURN q || $r$
    SELECT 'ROW'::text, p.decision_id, p.lv, CASE WHEN p.lv = 'LINE' THEN
               CASE WHEN p.item_id = ANY ($8) OR p.item_parent_id = ANY ($8) THEN 'RECUSED' ELSE 'RESTRICTED' END END,
           p.score, p.forum_id, p.forum_type_id, p.meeting_id, p.meeting_label, p.meeting_date, p.item_id, p.item_number,
           p.state, p.resolution_id, p.resolution_number, p.resolution_state, p.number_period, p.number_sequence,
           p.decided_at, p.entry_seq,
           CASE WHEN p.lv = 'FULL' THEN p.title END, CASE WHEN p.lv = 'FULL' THEN p.outcome_kind END,
           CASE WHEN p.lv = 'FULL' THEN p.outcome_label END, CASE WHEN p.lv = 'FULL' THEN p.is_resolution END,
           CASE WHEN p.lv = 'FULL' THEN p.decision_text END, CASE WHEN p.lv = 'FULL' THEN p.conditions END,
           CASE WHEN p.lv = 'FULL' THEN p.valid_until END, CASE WHEN p.lv = 'FULL' THEN p.has_dissent END,
           CASE WHEN p.lv = 'FULL' THEN p.vote_mode END, CASE WHEN p.lv = 'FULL' THEN p.ratification_required END,
           CASE WHEN p.lv = 'FULL' THEN p.ratified_by_decision_id END, CASE WHEN p.lv = 'FULL' THEN p.corrected END,
           CASE WHEN p.lv = 'FULL' THEN p.final_at END, CASE WHEN p.lv = 'FULL' THEN p.submission_id END,
           CASE WHEN p.lv = 'FULL' THEN p.memo_id END, CASE WHEN p.lv = 'FULL' THEN p.memo_type_id END,
           CASE WHEN p.lv = 'FULL' THEN p.memo_number END,
           NULL::int, NULL::boolean, NULL::jsonb
    FROM page p WHERE p.lv IS NOT NULL
    UNION ALL
    SELECT 'META', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
           NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
           (SELECT least(count(*), 10000)::int FROM counted),
           (SELECT count(*) > 10000 FROM counted) OR (SELECT c FROM capped),
           jsonb_build_object(
               'forums', coalesce((SELECT jsonb_object_agg(f.k, f.n) FROM (
                   SELECT forum_id::text AS k, count(*) AS n FROM counted GROUP BY forum_id) f), '{}'::jsonb),
               'years', coalesce((SELECT jsonb_object_agg(y.k, y.n) FROM (
                   SELECT extract(year FROM meeting_date)::int::text AS k, count(*) AS n FROM counted GROUP BY 1) y), '{}'::jsonb),
               'outcomes', coalesce((SELECT jsonb_object_agg(o.k, o.n) FROM (
                   SELECT outcome_kind AS k, count(*) AS n FROM counted WHERE lv = 'FULL' GROUP BY outcome_kind) o), '{}'::jsonb))
    $r$;
END $f$;

/*
 * The register's search (ADR-0069, design E08 §1.3, §1.4): one page of the decisions the viewer may see, each FULL or
 * LINE (a LINE row carries the committee, meeting, date, item number, number and state only), then one META row with the
 * total (up to 10,000), whether it is capped, and the facets. The viewer's parameters come from DecisionRegisterAccess,
 * computed from live state in the request's transaction; nothing here reads another module's table. The caller sets the
 * statement timeout (2 s).
 */
CREATE FUNCTION search_find_decisions(
    p_tenant uuid, p_viewer uuid, p_rank int, p_officer_all boolean, p_officers uuid[], p_members uuid[], p_reader boolean,
    p_recused uuid[],
    p_query text, p_prefix text, p_ref_norm text, p_ref_tail text,
    p_forums uuid[], p_forum_types uuid[], p_from date, p_to date, p_outcomes text[], p_resolutions_only boolean,
    p_states text[], p_memo_types uuid[], p_flags text[], p_today date,
    p_sort text, p_after_date date, p_after_at timestamptz, p_after_score real, p_after_period text, p_after_sequence bigint,
    p_after_id uuid, p_limit int, p_cap int
) RETURNS TABLE (
    kind text, decision_id uuid, level text, reason text, score real, forum_id uuid, forum_type_id uuid, meeting_id uuid,
    meeting_label text, meeting_date date, item_id uuid, item_number text, decision_state text, resolution_id uuid,
    resolution_number text, resolution_state text, number_period text, number_sequence bigint, decided_at timestamptz,
    entry_seq int, title text, outcome_kind text, outcome_label text, is_resolution boolean, decision_text text,
    conditions text[], valid_until date, has_dissent boolean, vote_mode text, ratification_required boolean,
    ratified_by_decision_id uuid, corrected boolean, final_at timestamptz, submission_id uuid, memo_id uuid,
    memo_type_id uuid, memo_number text, total int, capped boolean, facets jsonb
)
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = memo, pg_catalog
AS $$
DECLARE
    officers uuid[] := coalesce(p_officers, '{}');
    members uuid[] := coalesce(p_members, '{}');
    recused uuid[] := coalesce(p_recused, '{}');
    officer_all boolean := coalesce(p_officer_all, false);
    reader boolean := coalesce(p_reader, false);
    forums uuid[] := CASE WHEN cardinality(p_forums) > 0 THEN p_forums END;
    types uuid[] := CASE WHEN cardinality(p_forum_types) > 0 THEN p_forum_types END;
    outcomes text[] := CASE WHEN cardinality(p_outcomes) > 0 THEN p_outcomes END;
    states text[] := CASE WHEN cardinality(p_states) > 0 THEN p_states END;
    memo_types uuid[] := CASE WHEN cardinality(p_memo_types) > 0 THEN p_memo_types END;
    flags text[] := CASE WHEN cardinality(p_flags) > 0 THEN p_flags END;
    tsq tsquery := search_query(p_query, p_prefix);
    sort text := coalesce(p_sort, 'NEWEST');
    words boolean;
BEGIN
    PERFORM search_tenant(p_tenant);
    IF p_viewer IS NULL OR p_rank IS NULL THEN
        RAISE EXCEPTION 'search: a viewer and a clearance are required' USING ERRCODE = 'invalid_parameter_value';
    END IF;
    IF p_limit IS NULL OR p_limit < 1 OR p_limit > 1001 OR p_cap IS NULL OR p_cap < 1 OR p_cap > 10000 THEN
        RAISE EXCEPTION 'search: the page is 1 to 1001 rows, the cap 1 to 10000' USING ERRCODE = 'invalid_parameter_value';
    END IF;
    IF sort NOT IN ('RELEVANCE', 'NEWEST', 'OLDEST', 'NUMBER') THEN
        RAISE EXCEPTION 'search: unknown order' USING ERRCODE = 'invalid_parameter_value';
    END IF;
    IF sort = 'NUMBER' AND coalesce(cardinality(forums), 0) <> 1 THEN
        RAISE EXCEPTION 'search: number order needs one committee' USING ERRCODE = 'invalid_parameter_value';
    END IF;
    words := tsq IS NOT NULL OR p_ref_norm IS NOT NULL OR p_ref_tail IS NOT NULL;
    IF sort = 'RELEVANCE' AND NOT words THEN
        sort := 'NEWEST';
    END IF;
    IF NOT words AND (coalesce(p_query, '') <> '' OR coalesce(p_prefix, '') <> '') THEN
        -- Words that leave nothing to search for (stop words only): nothing matches.
        RETURN QUERY SELECT 'META'::text, NULL::uuid, NULL::text, NULL::text, NULL::real, NULL::uuid, NULL::uuid, NULL::uuid,
            NULL::text, NULL::date, NULL::uuid, NULL::text, NULL::text, NULL::uuid, NULL::text, NULL::text, NULL::text,
            NULL::bigint, NULL::timestamptz, NULL::int, NULL::text, NULL::text, NULL::text, NULL::boolean, NULL::text,
            NULL::text[], NULL::date, NULL::boolean, NULL::text, NULL::boolean, NULL::uuid, NULL::boolean, NULL::timestamptz,
            NULL::uuid, NULL::uuid, NULL::uuid, NULL::text, 0, false,
            jsonb_build_object('forums', '{}'::jsonb, 'years', '{}'::jsonb, 'outcomes', '{}'::jsonb);
        RETURN;
    END IF;
    RETURN QUERY EXECUTE search_find_decisions_sql(words, sort) USING
        p_tenant, p_viewer, p_rank, officer_all, officers, members, reader, recused,
        tsq, p_ref_norm, p_ref_tail,
        forums, types, p_from, p_to, outcomes, coalesce(p_resolutions_only, false), states, memo_types, flags, p_today,
        outcomes IS NOT NULL OR memo_types IS NOT NULL OR flags IS NOT NULL,
        p_after_date, p_after_at, p_after_score, p_after_period, p_after_sequence, p_after_id,
        p_limit, p_cap, 10001, reader OR officer_all, officers || members;
END $$;

/*
 * Decision statistics for analytics (ADR-0070, design E08 §2.2): FULL rows only, under the same rule and parameters as
 * search_find_decisions. Stubbed in T1 with the count of FULL decisions per committee in the period; E08 T4 replaces it
 * with the measures M3 and M4 (a later migration drops and recreates it).
 */
CREATE FUNCTION search_decision_stats(
    p_tenant uuid, p_viewer uuid, p_rank int, p_officer_all boolean, p_officers uuid[], p_members uuid[], p_reader boolean,
    p_recused uuid[], p_forums uuid[], p_from date, p_to date
) RETURNS TABLE (forum_id uuid, decisions bigint)
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = memo, pg_catalog
AS $$
BEGIN
    PERFORM search_tenant(p_tenant);
    IF p_viewer IS NULL OR p_rank IS NULL THEN
        RAISE EXCEPTION 'search: a viewer and a clearance are required' USING ERRCODE = 'invalid_parameter_value';
    END IF;
    RETURN QUERY
    SELECT d.forum_id, count(*) FROM search_decision d
    WHERE d.tenant_id = p_tenant
      AND (p_forums IS NULL OR cardinality(p_forums) = 0 OR d.forum_id = ANY (p_forums))
      AND (p_from IS NULL OR d.meeting_date >= p_from) AND (p_to IS NULL OR d.meeting_date <= p_to)
      AND search_decision_level(d.forum_id, d.classification_rank, d.item_id, d.item_parent_id, d.item_restricted_to,
              d.item_title_hidden, d.item_members_only, p_viewer, p_rank, coalesce(p_officer_all, false),
              coalesce(p_officers, '{}'), coalesce(p_members, '{}'), coalesce(p_reader, false),
              coalesce(p_recused, '{}')) = 'FULL'
    GROUP BY d.forum_id;
END $$;

-- As V1: only the entry points are callable by the application (new functions get EXECUTE through default privileges).
REVOKE ALL ON FUNCTION search_decision_level, search_find_decisions_sql(boolean, text) FROM PUBLIC, memo_app;
REVOKE ALL ON FUNCTION search_put_decision, search_put_decisions_final, search_put_decision_items, search_decision_unindexed,
    search_find_decisions, search_decision_stats FROM PUBLIC;
GRANT EXECUTE ON FUNCTION search_put_decision, search_put_decisions_final, search_put_decision_items,
    search_decision_unindexed, search_find_decisions, search_decision_stats TO memo_app;
