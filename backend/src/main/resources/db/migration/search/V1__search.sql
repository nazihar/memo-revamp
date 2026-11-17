-- Phase 3 E05 (ADR-0054, design E05 §3, §10): full-text search. Under forced row-level security PostgreSQL will not use a
-- GIN index for `@@` (the operator is not leakproof), so search has tables of its own: RLS enabled but not forced, owned
-- by the schema owner, with no privileges for memo_app. The application reaches them only through the SECURITY DEFINER
-- functions below, which take the tenant from the same app.tenant_id setting the policies trust and apply the E03 read
-- rule (clearance, active participant or unit scope) themselves. Derived data: rebuilt from memos, notes and extracted
-- text by the search module's listeners, jobs and backfill.
--
-- Notes and chunks carry copies of their memo's access and filter columns (rank, origin unit, status, type, created,
-- amount), kept equal by search_put_memo, so that every part of a search reads one table and can stop at the cap (E05 T1).

CREATE TABLE search_memo (
    tenant_id           uuid        NOT NULL,
    memo_id             uuid        NOT NULL,
    classification_rank int         NOT NULL CHECK (classification_rank >= 0),
    origin_unit_id      uuid,
    status              text        NOT NULL,
    type_id             uuid        NOT NULL,
    initiator_id        uuid        NOT NULL,
    amount              numeric,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    -- The number with each run of separators one `-`, upper case (M-2026-00042), and its last digit group without
    -- leading zeros (42). B-tree lookups: the migration role cannot create pg_trgm (ADR-0054 amendment).
    ref_norm            text,
    ref_tail            text,
    lang                text        NOT NULL DEFAULT 'english' CHECK (lang IN ('english', 'simple')),
    subject             text        NOT NULL,
    fields_text         text        NOT NULL DEFAULT '' CHECK (length(fields_text) <= 65536),
    -- Subject A, number B, type and field values C (ADR-0054). Never a regconfig column: pg_upgrade refuses those.
    tsv                 tsvector    GENERATED ALWAYS AS (
        setweight(to_tsvector(CASE lang WHEN 'english' THEN 'english'::regconfig ELSE 'simple'::regconfig END, subject), 'A') ||
        setweight(to_tsvector('simple'::regconfig, coalesce(ref_norm, '')), 'B') ||
        setweight(to_tsvector(CASE lang WHEN 'english' THEN 'english'::regconfig ELSE 'simple'::regconfig END, fields_text), 'C')
    ) STORED,
    PRIMARY KEY (tenant_id, memo_id),
    FOREIGN KEY (tenant_id, memo_id) REFERENCES memo (tenant_id, id)
);
CREATE INDEX search_memo_tsv_idx ON search_memo USING gin (tsv) WITH (fastupdate = on, gin_pending_list_limit = 2048);
CREATE INDEX search_memo_ref_idx ON search_memo (tenant_id, ref_norm);
CREATE INDEX search_memo_ref_tail_idx ON search_memo (tenant_id, ref_tail);
CREATE INDEX search_memo_unit_idx ON search_memo (tenant_id, origin_unit_id);
CREATE INDEX search_memo_updated_idx ON search_memo (tenant_id, updated_at DESC, memo_id);

CREATE TABLE search_note (
    tenant_id           uuid        NOT NULL,
    note_id             uuid        NOT NULL,
    memo_id             uuid        NOT NULL,
    -- The memo's columns (search_put_memo keeps them equal).
    classification_rank int         NOT NULL CHECK (classification_rank >= 0),
    origin_unit_id      uuid,
    status              text        NOT NULL,
    type_id             uuid        NOT NULL,
    memo_created_at     timestamptz NOT NULL,
    amount              numeric,
    lang                text        NOT NULL DEFAULT 'english' CHECK (lang IN ('english', 'simple')),
    body                text        NOT NULL CHECK (length(body) <= 8000),
    tsv                 tsvector    GENERATED ALWAYS AS (
        to_tsvector(CASE lang WHEN 'english' THEN 'english'::regconfig ELSE 'simple'::regconfig END, body)
    ) STORED,
    PRIMARY KEY (tenant_id, note_id),
    FOREIGN KEY (tenant_id, memo_id) REFERENCES memo (tenant_id, id)
);
CREATE INDEX search_note_tsv_idx ON search_note USING gin (tsv) WITH (fastupdate = on, gin_pending_list_limit = 2048);
CREATE INDEX search_note_memo_idx ON search_note (tenant_id, memo_id);

CREATE TABLE search_chunk (
    tenant_id           uuid        NOT NULL,
    file_version_id     uuid        NOT NULL,
    chunk               int         NOT NULL CHECK (chunk >= 0),
    memo_id             uuid        NOT NULL,
    -- The file version's own classification (an attachment may be above its memo, ADR-0046), and the effective one:
    -- the higher of it and the memo's.
    file_rank           int         NOT NULL CHECK (file_rank >= 0),
    classification_rank int         NOT NULL CHECK (classification_rank >= file_rank),
    -- The memo's columns (search_put_memo keeps them equal).
    origin_unit_id      uuid,
    status              text        NOT NULL,
    type_id             uuid        NOT NULL,
    memo_created_at     timestamptz NOT NULL,
    amount              numeric,
    lang                text        NOT NULL DEFAULT 'english' CHECK (lang IN ('english', 'simple')),
    body                text        NOT NULL,
    -- Computed by search_put_chunks (chunks stay under 8,000 words, ADR-0054).
    tsv                 tsvector    NOT NULL,
    PRIMARY KEY (tenant_id, file_version_id, chunk),
    FOREIGN KEY (tenant_id, memo_id) REFERENCES memo (tenant_id, id)
);
CREATE INDEX search_chunk_tsv_idx ON search_chunk USING gin (tsv) WITH (fastupdate = on, gin_pending_list_limit = 2048);
CREATE INDEX search_chunk_memo_idx ON search_chunk (tenant_id, memo_id);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['search_memo', 'search_note', 'search_chunk']
    LOOP
        -- Enabled, not forced: the owner (these functions) bypasses it; memo_app has no privileges at all.
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY tenant_isolation ON %I FOR ALL
            USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
            WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))$p$, t);
        EXECUTE format('REVOKE ALL ON %I FROM memo_app', t);
    END LOOP;
END $$;

-- The tenant the caller's transaction is bound to; every function refuses another.
CREATE FUNCTION search_tenant(p_tenant uuid) RETURNS uuid
    LANGUAGE plpgsql STABLE SECURITY INVOKER SET search_path = memo, pg_catalog
AS $$
DECLARE bound uuid := nullif(current_setting('app.tenant_id', true), '')::uuid;
BEGIN
    IF bound IS NULL OR p_tenant IS DISTINCT FROM bound THEN
        RAISE EXCEPTION 'search: tenant mismatch' USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN bound;
END $$;

-- Indexes a memo's details and copies its access and filter columns to its notes and chunks.
CREATE FUNCTION search_put_memo(
    p_tenant uuid, p_memo uuid, p_rank int, p_unit uuid, p_status text, p_type uuid, p_initiator uuid, p_amount numeric,
    p_created timestamptz, p_updated timestamptz, p_ref_norm text, p_ref_tail text, p_subject text, p_fields text
) RETURNS void
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = memo, pg_catalog
AS $$
BEGIN
    PERFORM search_tenant(p_tenant);
    INSERT INTO search_memo (tenant_id, memo_id, classification_rank, origin_unit_id, status, type_id, initiator_id, amount,
                             created_at, updated_at, ref_norm, ref_tail, subject, fields_text)
    VALUES (p_tenant, p_memo, p_rank, p_unit, p_status, p_type, p_initiator, p_amount, p_created, p_updated, p_ref_norm,
            p_ref_tail, p_subject, left(coalesce(p_fields, ''), 65536))
    ON CONFLICT (tenant_id, memo_id) DO UPDATE SET
        classification_rank = excluded.classification_rank, origin_unit_id = excluded.origin_unit_id,
        status = excluded.status, type_id = excluded.type_id, amount = excluded.amount, updated_at = excluded.updated_at,
        ref_norm = excluded.ref_norm, ref_tail = excluded.ref_tail, subject = excluded.subject,
        fields_text = excluded.fields_text;
    UPDATE search_note SET classification_rank = p_rank, origin_unit_id = p_unit, status = p_status, type_id = p_type,
                           memo_created_at = p_created, amount = p_amount
    WHERE tenant_id = p_tenant AND memo_id = p_memo
      AND (classification_rank, origin_unit_id, status, type_id, memo_created_at, amount)
          IS DISTINCT FROM (p_rank, p_unit, p_status, p_type, p_created, p_amount);
    UPDATE search_chunk SET classification_rank = greatest(file_rank, p_rank), origin_unit_id = p_unit, status = p_status,
                            type_id = p_type, memo_created_at = p_created, amount = p_amount
    WHERE tenant_id = p_tenant AND memo_id = p_memo
      AND (classification_rank, origin_unit_id, status, type_id, memo_created_at, amount)
          IS DISTINCT FROM (greatest(file_rank, p_rank), p_unit, p_status, p_type, p_created, p_amount);
END $$;

-- Indexes a note of an indexed memo; false when the memo is not indexed yet (the backfill indexes it, then its notes).
CREATE FUNCTION search_put_note(p_tenant uuid, p_note uuid, p_memo uuid, p_body text) RETURNS boolean
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = memo, pg_catalog
AS $$
BEGIN
    PERFORM search_tenant(p_tenant);
    INSERT INTO search_note (tenant_id, note_id, memo_id, classification_rank, origin_unit_id, status, type_id,
                             memo_created_at, amount, body)
    SELECT p_tenant, p_note, p_memo, sm.classification_rank, sm.origin_unit_id, sm.status, sm.type_id, sm.created_at,
           sm.amount, left(p_body, 8000)
    FROM search_memo sm WHERE sm.tenant_id = p_tenant AND sm.memo_id = p_memo
    ON CONFLICT (tenant_id, note_id) DO NOTHING;
    RETURN EXISTS (SELECT 1 FROM search_note WHERE tenant_id = p_tenant AND note_id = p_note);
END $$;

-- Replaces a file version's chunks; an empty array removes them (a version that left the memo). False when the memo is
-- not indexed yet.
CREATE FUNCTION search_put_chunks(p_tenant uuid, p_version uuid, p_memo uuid, p_rank int, p_chunks text[]) RETURNS boolean
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = memo, pg_catalog
AS $$
BEGIN
    PERFORM search_tenant(p_tenant);
    DELETE FROM search_chunk WHERE tenant_id = p_tenant AND file_version_id = p_version;
    INSERT INTO search_chunk (tenant_id, file_version_id, chunk, memo_id, file_rank, classification_rank, origin_unit_id,
                              status, type_id, memo_created_at, amount, body, tsv)
    SELECT p_tenant, p_version, c.n - 1, p_memo, p_rank, greatest(p_rank, sm.classification_rank), sm.origin_unit_id,
           sm.status, sm.type_id, sm.created_at, sm.amount, c.body, to_tsvector('english'::regconfig, c.body)
    FROM search_memo sm, unnest(p_chunks) WITH ORDINALITY AS c(body, n)
    WHERE sm.tenant_id = p_tenant AND sm.memo_id = p_memo;
    RETURN EXISTS (SELECT 1 FROM search_memo WHERE tenant_id = p_tenant AND memo_id = p_memo);
END $$;

-- A file version reclassified (ADR-0046): its chunks take the new rank.
CREATE FUNCTION search_set_chunk_rank(p_tenant uuid, p_version uuid, p_rank int) RETURNS void
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = memo, pg_catalog
AS $$
BEGIN
    PERFORM search_tenant(p_tenant);
    UPDATE search_chunk sc SET file_rank = p_rank, classification_rank = greatest(p_rank, sm.classification_rank)
    FROM search_memo sm
    WHERE sc.tenant_id = p_tenant AND sc.file_version_id = p_version
      AND sm.tenant_id = sc.tenant_id AND sm.memo_id = sc.memo_id;
END $$;

-- The query: websearch syntax over the English configuration, with the last word as a prefix when the caller asks
-- (3+ letters or digits). Null when nothing searchable is left (empty input, stop words only).
CREATE FUNCTION search_query(p_query text, p_prefix text) RETURNS tsquery
    LANGUAGE plpgsql IMMUTABLE SECURITY INVOKER SET search_path = memo, pg_catalog
AS $$
DECLARE
    q tsquery := CASE WHEN coalesce(p_query, '') = '' THEN NULL ELSE websearch_to_tsquery('english'::regconfig, p_query) END;
    word text := regexp_replace(lower(coalesce(p_prefix, '')), '[^[:alnum:]]', '', 'g');
    pq tsquery;
BEGIN
    IF q IS NOT NULL AND numnode(q) = 0 THEN
        q := NULL;
    END IF;
    IF length(word) >= 3 THEN
        pq := to_tsquery('english'::regconfig, word || ':*');
        IF numnode(pq) > 0 THEN
            q := CASE WHEN q IS NULL THEN pq ELSE q && pq END;
        END IF;
    END IF;
    RETURN q;
END $$;

-- The search filters. Plain SQL with no SET clause, so the planner inlines it.
CREATE FUNCTION search_keep(
    s_status text, s_type uuid, s_unit uuid, s_created timestamptz, s_amount numeric,
    p_status text[], p_types uuid[], p_units uuid[], p_from timestamptz, p_to timestamptz, p_amount_min numeric, p_amount_max numeric
) RETURNS boolean
    LANGUAGE sql IMMUTABLE SECURITY INVOKER
AS $$
    SELECT (p_status IS NULL OR s_status = ANY (p_status))
       AND (p_types IS NULL OR s_type = ANY (p_types))
       AND (p_units IS NULL OR s_unit = ANY (p_units))
       AND (p_from IS NULL OR s_created >= p_from)
       AND (p_to IS NULL OR s_created < p_to)
       AND (p_amount_min IS NULL OR s_amount >= p_amount_min)
       AND (p_amount_max IS NULL OR s_amount <= p_amount_max)
$$;

/*
 * The query of search_find, with numbered parameters (ADR-0054). Kept as text so search_find plans it on every call with
 * the call's values (EXECUTE ... USING): a cached generic plan cannot know whether the reader reads 300 memos or the whole
 * tenant, nor whether a word is in most memos or in fifty (E05 T1). The EXPLAIN gate test prepares this same text.
 *   $1 tenant, $2 reader, $3 clearance rank, $4 unit scope (expanded), $5 all units, $6 any unit scope, $7 now,
 *   $8 tsquery, $9 number normal form, $10 number tail, $11 statuses, $12 types, $13 origin units, $14 from, $15 to,
 *   $16 amount min, $17 amount max, $18 in details, $19 in notes, $20 in documents, $21 cap
 * Each source is searched in two parts, each reading one table and stopping at the cap: through the reader's active
 * participant rows, and through the unit scope (skipped when the reader has none). With p_lookup the participant part looks
 * up each of the reader's memos (OFFSET 0 keeps the planner from turning it into a scan that cannot stop at the cap);
 * without it, for readers with many memos, the planner chooses (for a rare word, the word's index and a hash of the memos).
 */
CREATE FUNCTION search_find_sql(p_lookup boolean) RETURNS text
    LANGUAGE sql IMMUTABLE SECURITY INVOKER
AS $sql$ SELECT CASE WHEN p_lookup THEN q ELSE replace(q, 'OFFSET 0', '') END FROM (SELECT $q$
    WITH mine AS MATERIALIZED (
        SELECT mp.memo_id FROM memo_participant mp
        WHERE mp.tenant_id = $1 AND mp.user_id = $2 AND mp.revoked_at IS NULL
          AND (mp.expires_at IS NULL OR mp.expires_at > $7)
    ),
    meta AS (
        (SELECT x.memo_id, x.s FROM mine CROSS JOIN LATERAL (
            SELECT sm.memo_id, ts_rank_cd(sm.tsv, $8) AS s FROM search_memo sm
            WHERE sm.tenant_id = $1 AND sm.memo_id = mine.memo_id AND sm.tsv @@ $8 AND sm.classification_rank <= $3
              AND search_keep(sm.status, sm.type_id, sm.origin_unit_id, sm.created_at, sm.amount, $11, $12, $13, $14, $15, $16, $17)
            OFFSET 0) x
         WHERE $18 AND $8 IS NOT NULL LIMIT $21)
        UNION ALL
        (SELECT sm.memo_id, ts_rank_cd(sm.tsv, $8) FROM search_memo sm
         WHERE $18 AND $8 IS NOT NULL AND $6 AND sm.tenant_id = $1 AND sm.tsv @@ $8
           AND sm.classification_rank <= $3 AND ($5 OR sm.origin_unit_id = ANY ($4))
           AND search_keep(sm.status, sm.type_id, sm.origin_unit_id, sm.created_at, sm.amount, $11, $12, $13, $14, $15, $16, $17)
         LIMIT $21)
    ),
    refs AS (
        SELECT sm.memo_id, 10.0::real AS s FROM search_memo sm
        WHERE ($9 IS NOT NULL OR $10 IS NOT NULL) AND sm.tenant_id = $1
          AND (sm.ref_norm = $9 OR sm.ref_tail = $10)
          AND sm.classification_rank <= $3
          AND ($5 OR sm.origin_unit_id = ANY ($4) OR sm.memo_id IN (SELECT mine.memo_id FROM mine))
          AND search_keep(sm.status, sm.type_id, sm.origin_unit_id, sm.created_at, sm.amount, $11, $12, $13, $14, $15, $16, $17)
        LIMIT $21
    ),
    notes AS (
        (SELECT x.memo_id, x.s FROM mine CROSS JOIN LATERAL (
            SELECT sn.memo_id, ts_rank_cd(sn.tsv, $8) AS s FROM search_note sn
            WHERE sn.tenant_id = $1 AND sn.memo_id = mine.memo_id AND sn.tsv @@ $8 AND sn.classification_rank <= $3
              AND search_keep(sn.status, sn.type_id, sn.origin_unit_id, sn.memo_created_at, sn.amount, $11, $12, $13, $14, $15, $16, $17)
            OFFSET 0) x
         WHERE $19 AND $8 IS NOT NULL LIMIT $21)
        UNION ALL
        (SELECT sn.memo_id, ts_rank_cd(sn.tsv, $8) FROM search_note sn
         WHERE $19 AND $8 IS NOT NULL AND $6 AND sn.tenant_id = $1 AND sn.tsv @@ $8
           AND sn.classification_rank <= $3 AND ($5 OR sn.origin_unit_id = ANY ($4))
           AND search_keep(sn.status, sn.type_id, sn.origin_unit_id, sn.memo_created_at, sn.amount, $11, $12, $13, $14, $15, $16, $17)
         LIMIT $21)
    ),
    docs AS (
        (SELECT x.memo_id, x.s FROM mine CROSS JOIN LATERAL (
            SELECT sc.memo_id, ts_rank_cd(sc.tsv, $8) AS s FROM search_chunk sc
            WHERE sc.tenant_id = $1 AND sc.memo_id = mine.memo_id AND sc.tsv @@ $8 AND sc.classification_rank <= $3
              AND search_keep(sc.status, sc.type_id, sc.origin_unit_id, sc.memo_created_at, sc.amount, $11, $12, $13, $14, $15, $16, $17)
            OFFSET 0) x
         WHERE $20 AND $8 IS NOT NULL LIMIT $21)
        UNION ALL
        (SELECT sc.memo_id, ts_rank_cd(sc.tsv, $8) FROM search_chunk sc
         WHERE $20 AND $8 IS NOT NULL AND $6 AND sc.tenant_id = $1 AND sc.tsv @@ $8
           AND sc.classification_rank <= $3 AND ($5 OR sc.origin_unit_id = ANY ($4))
           AND search_keep(sc.status, sc.type_id, sc.origin_unit_id, sc.memo_created_at, sc.amount, $11, $12, $13, $14, $15, $16, $17)
         LIMIT $21)
    ),
    top AS (
        SELECT h.memo_id,
               sum(h.s)::real AS score,
               bool_or(h.k = 'meta') AS in_meta, bool_or(h.k = 'notes') AS in_notes,
               bool_or(h.k = 'docs') AS in_docs, bool_or(h.k = 'ref') AS in_ref
        FROM (
            -- One score per memo and source (the best note or chunk), weighted: details 1, notes 0.4, documents 0.2.
            SELECT m.memo_id, max(m.s) AS s, 'meta' AS k FROM meta m GROUP BY m.memo_id
            UNION ALL SELECT r.memo_id, max(r.s), 'ref' FROM refs r GROUP BY r.memo_id
            UNION ALL SELECT n.memo_id, 0.4 * max(n.s), 'notes' FROM notes n GROUP BY n.memo_id
            UNION ALL SELECT d.memo_id, 0.2 * max(d.s), 'docs' FROM docs d GROUP BY d.memo_id
        ) h
        GROUP BY h.memo_id
        ORDER BY score DESC, h.memo_id
        LIMIT $21
    )
    SELECT sm.memo_id, top.score, top.in_meta, top.in_notes, top.in_docs, top.in_ref,
           sm.status, sm.type_id, sm.origin_unit_id, sm.created_at, sm.updated_at
    FROM top JOIN search_memo sm ON sm.tenant_id = $1 AND sm.memo_id = top.memo_id
    ORDER BY top.score DESC, sm.memo_id
$q$ AS q) s $sql$;

/*
 * The candidates of a search (ADR-0054): at most p_cap memos the reader may read that match, best first, with where they
 * matched. The E03 read rule (ADR-0050) is applied here: classification at most p_rank, and an active participant row of
 * p_reader (not revoked, a share not lapsed at p_now) or the memo's origin unit in the reader's memo.read.unit scope
 * (p_all_units: tenant-wide; p_units: the expanded subtrees). A document chunk counts only within the reader's clearance.
 */
CREATE FUNCTION search_find(
    p_tenant uuid, p_reader uuid, p_rank int, p_units uuid[], p_all_units boolean, p_now timestamptz,
    p_query text, p_prefix text, p_ref_norm text, p_ref_tail text,
    p_status text[], p_types uuid[], p_origin_units uuid[], p_from timestamptz, p_to timestamptz,
    p_amount_min numeric, p_amount_max numeric,
    p_in_meta boolean, p_in_notes boolean, p_in_docs boolean, p_cap int
) RETURNS TABLE (
    memo_id uuid, score real, in_meta boolean, in_notes boolean, in_docs boolean, in_ref boolean,
    status text, type_id uuid, origin_unit_id uuid, created_at timestamptz, updated_at timestamptz
)
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = memo, pg_catalog
AS $$
DECLARE
    units uuid[] := coalesce(p_units, '{}');
    everywhere boolean := coalesce(p_all_units, false);
    -- Readers with more memos than this leave the participant part's plan to the planner (search_find_sql).
    lookups constant int := 5000;
    many boolean;
BEGIN
    PERFORM search_tenant(p_tenant);
    IF p_cap IS NULL OR p_cap < 1 OR p_cap > 10000 THEN
        RAISE EXCEPTION 'search: the cap is 1 to 10000' USING ERRCODE = 'invalid_parameter_value';
    END IF;
    SELECT count(*) > lookups INTO many FROM (
        SELECT 1 FROM memo_participant mp
        WHERE mp.tenant_id = p_tenant AND mp.user_id = p_reader AND mp.revoked_at IS NULL
        LIMIT lookups + 1
    ) x;
    RETURN QUERY EXECUTE search_find_sql(NOT many) USING
        p_tenant, p_reader, p_rank, units, everywhere, everywhere OR cardinality(units) > 0, p_now,
        search_query(p_query, p_prefix), p_ref_norm, p_ref_tail,
        p_status, p_types, p_origin_units, p_from, p_to, p_amount_min, p_amount_max,
        coalesce(p_in_meta, true), coalesce(p_in_notes, true), coalesce(p_in_docs, true), p_cap;
END $$;

/*
 * Snippets for the page's memos only (ADR-0054): from the best matching chunk the reader is cleared for, else the best
 * matching note, else the subject. Private-use characters U+E000 and U+E001 mark the matches; the caller escapes the text
 * and turns them into <mark>. Memos the reader may not read are left out (the same rule as search_find).
 */
CREATE FUNCTION search_snippets(
    p_tenant uuid, p_reader uuid, p_rank int, p_units uuid[], p_all_units boolean, p_now timestamptz,
    p_memos uuid[], p_query text, p_prefix text
) RETURNS TABLE (memo_id uuid, source text, source_id uuid, snippet text)
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = memo, pg_catalog
AS $$
DECLARE
    q tsquery := search_query(p_query, p_prefix);
    everywhere boolean := coalesce(p_all_units, false);
    units uuid[] := coalesce(p_units, '{}');
    opts text := 'StartSel=' || chr(57344) || ',StopSel=' || chr(57345)
        || ',MaxFragments=2,MaxWords=20,MinWords=8,FragmentDelimiter=" … "';
BEGIN
    PERFORM search_tenant(p_tenant);
    IF coalesce(cardinality(p_memos), 0) > 100 THEN
        RAISE EXCEPTION 'search: at most 100 memos per page' USING ERRCODE = 'invalid_parameter_value';
    END IF;
    RETURN QUERY
    SELECT sm.memo_id,
           CASE WHEN ch.body IS NOT NULL THEN 'document' WHEN nt.body IS NOT NULL THEN 'note' ELSE 'subject' END,
           coalesce(ch.file_version_id, nt.note_id),
           CASE
               WHEN q IS NULL THEN left(sm.subject, 200)
               WHEN ch.body IS NOT NULL THEN ts_headline('english'::regconfig, ch.body, q, opts)
               WHEN nt.body IS NOT NULL THEN ts_headline('english'::regconfig, nt.body, q, opts)
               ELSE ts_headline('english'::regconfig, sm.subject, q, opts)
           END
    FROM search_memo sm
    LEFT JOIN LATERAL (
        SELECT sc.file_version_id, sc.body FROM search_chunk sc
        WHERE q IS NOT NULL AND sc.tenant_id = p_tenant AND sc.memo_id = sm.memo_id
          AND sc.classification_rank <= p_rank AND sc.tsv @@ q
        ORDER BY ts_rank_cd(sc.tsv, q) DESC, sc.file_version_id, sc.chunk LIMIT 1
    ) ch ON true
    LEFT JOIN LATERAL (
        SELECT sn.note_id, sn.body FROM search_note sn
        WHERE q IS NOT NULL AND sn.tenant_id = p_tenant AND sn.memo_id = sm.memo_id AND sn.tsv @@ q
        ORDER BY ts_rank_cd(sn.tsv, q) DESC, sn.note_id LIMIT 1
    ) nt ON true
    WHERE sm.tenant_id = p_tenant AND sm.memo_id = ANY (p_memos)
      AND sm.classification_rank <= p_rank
      AND (everywhere OR sm.origin_unit_id = ANY (units) OR EXISTS (
          SELECT 1 FROM memo_participant mp
          WHERE mp.tenant_id = p_tenant AND mp.memo_id = sm.memo_id AND mp.user_id = p_reader
            AND mp.revoked_at IS NULL AND (mp.expires_at IS NULL OR mp.expires_at > p_now)));
END $$;

-- Only the entry points are callable by the application (new functions get EXECUTE through default privileges).
REVOKE ALL ON FUNCTION search_tenant, search_query, search_keep, search_find_sql(boolean) FROM PUBLIC, memo_app;
REVOKE ALL ON FUNCTION search_put_memo, search_put_note, search_put_chunks, search_set_chunk_rank, search_find, search_snippets
    FROM PUBLIC;
GRANT EXECUTE ON FUNCTION search_put_memo, search_put_note, search_put_chunks, search_set_chunk_rank, search_find, search_snippets
    TO memo_app;
