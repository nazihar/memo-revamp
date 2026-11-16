-- Phase 3 E06 T4 (ADR-0055, design E06 §3.4, §11): imported memos in search. Each search table gains the memo's origin
-- (every part of a search reads one table, ADR-0054 amendment), search_memo's initiator becomes nullable (an imported
-- memo's legacy initiator may be linked to nobody here), search_put_memo writes the origin and copies it to the memo's
-- notes and chunks with the other access and filter columns, and search_find filters on it (null: both origins).
--
-- Expand only. Existing rows are NATIVE: imported memos were not indexed before this migration (the backfill indexes
-- them now). The new parameters come last and have defaults, so the calls of an application one release older still
-- resolve while a release rolls out. The access rule is unchanged; only the filter and the returned column are new.

ALTER TABLE search_memo
    ADD COLUMN origin text NOT NULL DEFAULT 'NATIVE',
    ALTER COLUMN initiator_id DROP NOT NULL;
ALTER TABLE search_note ADD COLUMN origin text NOT NULL DEFAULT 'NATIVE';
ALTER TABLE search_chunk ADD COLUMN origin text NOT NULL DEFAULT 'NATIVE';
-- NOT VALID: every existing row holds the default, and new rows are checked. Validating would read every chunk of every
-- tenant while the table is locked.
ALTER TABLE search_memo ADD CONSTRAINT search_memo_origin_check CHECK (origin IN ('NATIVE', 'IMPORTED')) NOT VALID;
ALTER TABLE search_note ADD CONSTRAINT search_note_origin_check CHECK (origin IN ('NATIVE', 'IMPORTED')) NOT VALID;
ALTER TABLE search_chunk ADD CONSTRAINT search_chunk_origin_check CHECK (origin IN ('NATIVE', 'IMPORTED')) NOT VALID;

-- Indexes a memo's details and copies its access and filter columns, now with its origin, to its notes and chunks.
DROP FUNCTION search_put_memo(uuid, uuid, int, uuid, text, uuid, uuid, numeric, timestamptz, timestamptz, text, text, text, text);
CREATE FUNCTION search_put_memo(
    p_tenant uuid, p_memo uuid, p_rank int, p_unit uuid, p_status text, p_type uuid, p_initiator uuid, p_amount numeric,
    p_created timestamptz, p_updated timestamptz, p_ref_norm text, p_ref_tail text, p_subject text, p_fields text,
    p_origin text DEFAULT 'NATIVE'
) RETURNS void
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = memo, pg_catalog
AS $$
BEGIN
    PERFORM search_tenant(p_tenant);
    INSERT INTO search_memo (tenant_id, memo_id, classification_rank, origin_unit_id, status, type_id, initiator_id, amount,
                             created_at, updated_at, ref_norm, ref_tail, subject, fields_text, origin)
    VALUES (p_tenant, p_memo, p_rank, p_unit, p_status, p_type, p_initiator, p_amount, p_created, p_updated, p_ref_norm,
            p_ref_tail, p_subject, left(coalesce(p_fields, ''), 65536), p_origin)
    ON CONFLICT (tenant_id, memo_id) DO UPDATE SET
        classification_rank = excluded.classification_rank, origin_unit_id = excluded.origin_unit_id,
        status = excluded.status, type_id = excluded.type_id, amount = excluded.amount, created_at = excluded.created_at,
        updated_at = excluded.updated_at, ref_norm = excluded.ref_norm, ref_tail = excluded.ref_tail,
        subject = excluded.subject, fields_text = excluded.fields_text, origin = excluded.origin;
    UPDATE search_note SET classification_rank = p_rank, origin_unit_id = p_unit, status = p_status, type_id = p_type,
                           memo_created_at = p_created, amount = p_amount, origin = p_origin
    WHERE tenant_id = p_tenant AND memo_id = p_memo
      AND (classification_rank, origin_unit_id, status, type_id, memo_created_at, amount, origin)
          IS DISTINCT FROM (p_rank, p_unit, p_status, p_type, p_created, p_amount, p_origin);
    UPDATE search_chunk SET classification_rank = greatest(file_rank, p_rank), origin_unit_id = p_unit, status = p_status,
                            type_id = p_type, memo_created_at = p_created, amount = p_amount, origin = p_origin
    WHERE tenant_id = p_tenant AND memo_id = p_memo
      AND (classification_rank, origin_unit_id, status, type_id, memo_created_at, amount, origin)
          IS DISTINCT FROM (greatest(file_rank, p_rank), p_unit, p_status, p_type, p_created, p_amount, p_origin);
END $$;

-- Indexes a note of an indexed memo; false when the memo is not indexed yet (the backfill indexes it, then its notes).
CREATE OR REPLACE FUNCTION search_put_note(p_tenant uuid, p_note uuid, p_memo uuid, p_body text) RETURNS boolean
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = memo, pg_catalog
AS $$
BEGIN
    PERFORM search_tenant(p_tenant);
    INSERT INTO search_note (tenant_id, note_id, memo_id, classification_rank, origin_unit_id, status, type_id,
                             memo_created_at, amount, body, origin)
    SELECT p_tenant, p_note, p_memo, sm.classification_rank, sm.origin_unit_id, sm.status, sm.type_id, sm.created_at,
           sm.amount, left(p_body, 8000), sm.origin
    FROM search_memo sm WHERE sm.tenant_id = p_tenant AND sm.memo_id = p_memo
    ON CONFLICT (tenant_id, note_id) DO NOTHING;
    RETURN EXISTS (SELECT 1 FROM search_note WHERE tenant_id = p_tenant AND note_id = p_note);
END $$;

-- Replaces a file's chunks with those of its version p_version; an empty array removes them (a file that left the memo).
-- False when the memo is not indexed yet.
CREATE OR REPLACE FUNCTION search_put_chunks(p_tenant uuid, p_file uuid, p_version uuid, p_memo uuid, p_rank int, p_chunks text[])
    RETURNS boolean
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = memo, pg_catalog
AS $$
BEGIN
    PERFORM search_tenant(p_tenant);
    DELETE FROM search_chunk WHERE tenant_id = p_tenant AND file_id = p_file;
    INSERT INTO search_chunk (tenant_id, file_id, file_version_id, chunk, memo_id, file_rank, classification_rank,
                              origin_unit_id, status, type_id, memo_created_at, amount, body, tsv, origin)
    SELECT p_tenant, p_file, p_version, c.n - 1, p_memo, p_rank, greatest(p_rank, sm.classification_rank), sm.origin_unit_id,
           sm.status, sm.type_id, sm.created_at, sm.amount, c.body, to_tsvector('english'::regconfig, c.body), sm.origin
    FROM search_memo sm, unnest(p_chunks) WITH ORDINALITY AS c(body, n)
    WHERE sm.tenant_id = p_tenant AND sm.memo_id = p_memo;
    RETURN EXISTS (SELECT 1 FROM search_memo WHERE tenant_id = p_tenant AND memo_id = p_memo);
END $$;

-- The search filters, now with the origin (p_origins null: both). Plain SQL with no SET clause, so the planner inlines it.
CREATE FUNCTION search_keep(
    s_status text, s_type uuid, s_unit uuid, s_created timestamptz, s_amount numeric, s_origin text,
    p_status text[], p_types uuid[], p_units uuid[], p_from timestamptz, p_to timestamptz, p_amount_min numeric,
    p_amount_max numeric, p_origins text[]
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
       AND (p_origins IS NULL OR s_origin = ANY (p_origins))
$$;

/*
 * The query of search_find, with numbered parameters (ADR-0054). Kept as text so search_find plans it on every call with
 * the call's values (EXECUTE ... USING): a cached generic plan cannot know whether the reader reads 300 memos or the whole
 * tenant, nor whether a word is in most memos or in fifty (E05 T1). The EXPLAIN gate test prepares this same text.
 *   $1 tenant, $2 reader, $3 clearance rank, $4 unit scope (expanded), $5 all units, $6 any unit scope, $7 now,
 *   $8 tsquery, $9 number normal form, $10 number tail, $11 statuses, $12 types, $13 origin units, $14 from, $15 to,
 *   $16 amount min, $17 amount max, $18 in details, $19 in notes, $20 in documents, $21 cap, $22 origins (E06 T4)
 * Each source is searched in two parts, each reading one table and stopping at the cap: through the reader's active
 * participant rows, and through the unit scope (skipped when the reader has none). With p_lookup the participant part looks
 * up each of the reader's memos (OFFSET 0 keeps the planner from turning it into a scan that cannot stop at the cap);
 * without it, for readers with many memos, the planner chooses (for a rare word, the word's index and a hash of the memos).
 */
CREATE OR REPLACE FUNCTION search_find_sql(p_lookup boolean) RETURNS text
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
              AND search_keep(sm.status, sm.type_id, sm.origin_unit_id, sm.created_at, sm.amount, sm.origin,
                              $11, $12, $13, $14, $15, $16, $17, $22)
            OFFSET 0) x
         WHERE $18 AND $8 IS NOT NULL LIMIT $21)
        UNION ALL
        (SELECT sm.memo_id, ts_rank_cd(sm.tsv, $8) FROM search_memo sm
         WHERE $18 AND $8 IS NOT NULL AND $6 AND sm.tenant_id = $1 AND sm.tsv @@ $8
           AND sm.classification_rank <= $3 AND ($5 OR sm.origin_unit_id = ANY ($4))
           AND search_keep(sm.status, sm.type_id, sm.origin_unit_id, sm.created_at, sm.amount, sm.origin,
                           $11, $12, $13, $14, $15, $16, $17, $22)
         LIMIT $21)
    ),
    refs AS (
        SELECT sm.memo_id, 10.0::real AS s FROM search_memo sm
        WHERE ($9 IS NOT NULL OR $10 IS NOT NULL) AND sm.tenant_id = $1
          AND (sm.ref_norm = $9 OR sm.ref_tail = $10)
          AND sm.classification_rank <= $3
          AND ($5 OR sm.origin_unit_id = ANY ($4) OR sm.memo_id IN (SELECT mine.memo_id FROM mine))
          AND search_keep(sm.status, sm.type_id, sm.origin_unit_id, sm.created_at, sm.amount, sm.origin,
                          $11, $12, $13, $14, $15, $16, $17, $22)
        LIMIT $21
    ),
    notes AS (
        (SELECT x.memo_id, x.s FROM mine CROSS JOIN LATERAL (
            SELECT sn.memo_id, ts_rank_cd(sn.tsv, $8) AS s FROM search_note sn
            WHERE sn.tenant_id = $1 AND sn.memo_id = mine.memo_id AND sn.tsv @@ $8 AND sn.classification_rank <= $3
              AND search_keep(sn.status, sn.type_id, sn.origin_unit_id, sn.memo_created_at, sn.amount, sn.origin,
                              $11, $12, $13, $14, $15, $16, $17, $22)
            OFFSET 0) x
         WHERE $19 AND $8 IS NOT NULL LIMIT $21)
        UNION ALL
        (SELECT sn.memo_id, ts_rank_cd(sn.tsv, $8) FROM search_note sn
         WHERE $19 AND $8 IS NOT NULL AND $6 AND sn.tenant_id = $1 AND sn.tsv @@ $8
           AND sn.classification_rank <= $3 AND ($5 OR sn.origin_unit_id = ANY ($4))
           AND search_keep(sn.status, sn.type_id, sn.origin_unit_id, sn.memo_created_at, sn.amount, sn.origin,
                           $11, $12, $13, $14, $15, $16, $17, $22)
         LIMIT $21)
    ),
    docs AS (
        (SELECT x.memo_id, x.s FROM mine CROSS JOIN LATERAL (
            SELECT sc.memo_id, ts_rank_cd(sc.tsv, $8) AS s FROM search_chunk sc
            WHERE sc.tenant_id = $1 AND sc.memo_id = mine.memo_id AND sc.tsv @@ $8 AND sc.classification_rank <= $3
              AND search_keep(sc.status, sc.type_id, sc.origin_unit_id, sc.memo_created_at, sc.amount, sc.origin,
                              $11, $12, $13, $14, $15, $16, $17, $22)
            OFFSET 0) x
         WHERE $20 AND $8 IS NOT NULL LIMIT $21)
        UNION ALL
        (SELECT sc.memo_id, ts_rank_cd(sc.tsv, $8) FROM search_chunk sc
         WHERE $20 AND $8 IS NOT NULL AND $6 AND sc.tenant_id = $1 AND sc.tsv @@ $8
           AND sc.classification_rank <= $3 AND ($5 OR sc.origin_unit_id = ANY ($4))
           AND search_keep(sc.status, sc.type_id, sc.origin_unit_id, sc.memo_created_at, sc.amount, sc.origin,
                           $11, $12, $13, $14, $15, $16, $17, $22)
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
           sm.status, sm.type_id, sm.origin_unit_id, sm.created_at, sm.updated_at, sm.origin
    FROM top JOIN search_memo sm ON sm.tenant_id = $1 AND sm.memo_id = top.memo_id
    ORDER BY top.score DESC, sm.memo_id
$q$ AS q) s $sql$;

/*
 * The candidates of a search (ADR-0054): at most p_cap memos the reader may read that match, best first, with where they
 * matched and the memo's origin. The E03 read rule (ADR-0050) is applied here, unchanged: classification at most p_rank,
 * and an active participant row of p_reader (not revoked, a share not lapsed at p_now) or the memo's origin unit in the
 * reader's memo.read.unit scope (p_all_units: tenant-wide; p_units: the expanded subtrees). A document chunk counts only
 * within the reader's clearance. p_origins narrows to NATIVE or IMPORTED memos; null or empty keeps both.
 */
DROP FUNCTION search_find(
    uuid, uuid, int, uuid[], boolean, timestamptz, text, text, text, text, text[], uuid[], uuid[], timestamptz, timestamptz,
    numeric, numeric, boolean, boolean, boolean, int
);
CREATE FUNCTION search_find(
    p_tenant uuid, p_reader uuid, p_rank int, p_units uuid[], p_all_units boolean, p_now timestamptz,
    p_query text, p_prefix text, p_ref_norm text, p_ref_tail text,
    p_status text[], p_types uuid[], p_origin_units uuid[], p_from timestamptz, p_to timestamptz,
    p_amount_min numeric, p_amount_max numeric,
    p_in_meta boolean, p_in_notes boolean, p_in_docs boolean, p_cap int,
    p_origins text[] DEFAULT NULL
) RETURNS TABLE (
    memo_id uuid, score real, in_meta boolean, in_notes boolean, in_docs boolean, in_ref boolean,
    status text, type_id uuid, origin_unit_id uuid, created_at timestamptz, updated_at timestamptz, origin text
)
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = memo, pg_catalog
AS $$
DECLARE
    units uuid[] := coalesce(p_units, '{}');
    everywhere boolean := coalesce(p_all_units, false);
    origins text[] := CASE WHEN cardinality(p_origins) > 0 THEN p_origins END;
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
        coalesce(p_in_meta, true), coalesce(p_in_notes, true), coalesce(p_in_docs, true), p_cap, origins;
END $$;

-- The filters without the origin are no longer used.
DROP FUNCTION search_keep(text, uuid, uuid, timestamptz, numeric, text[], uuid[], uuid[], timestamptz, timestamptz, numeric, numeric);

-- As V1: only the entry points are callable by the application (new functions get EXECUTE through default privileges).
REVOKE ALL ON FUNCTION search_keep(text, uuid, uuid, timestamptz, numeric, text, text[], uuid[], uuid[], timestamptz,
    timestamptz, numeric, numeric, text[]) FROM PUBLIC, memo_app;
REVOKE ALL ON FUNCTION search_put_memo, search_find FROM PUBLIC;
GRANT EXECUTE ON FUNCTION search_put_memo, search_find TO memo_app;
