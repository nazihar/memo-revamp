-- Phase 4 E08 T4 (ADR-0070, design E08 §2.1–§2.2, §6): the decision statistics behind analytics' time to decision (M3) and
-- items deferred (M4). T1's stub (search/V4) counted FULL decisions per committee; this one returns the facts of each FULL
-- decision on a SUBMISSION item, under the same rule and the same parameters as search_find_decisions, so that analytics
-- computes the medians, the percentiles, working days (the tenant's business calendar) and the deferral counts from rows
-- the viewer sees in full only. A LINE row's outcome is unknown to the viewer, so it is never returned or counted.
--
-- The stub was never called by a released application (T1 and T4 ship together), so it is dropped and created again with
-- the same arguments and a new result: the change is expand only for every version that runs.

DROP FUNCTION search_decision_stats(uuid, uuid, int, boolean, uuid[], uuid[], boolean, uuid[], uuid[], date, date);

/*
 * The query of search_decision_stats as text, so that the function plans it on every call with the call's values
 * (EXECUTE ... USING, as search_find_decisions; ADR-0054's amendment), and the EXPLAIN gate prepares the same text.
 *   $1 tenant, $2 viewer, $3 clearance rank, $4 officer of all, $5 officer committees, $6 member committees, $7 reader,
 *   $8 recused items, $9 committees (null: every one in scope), $10 from, $11 to (meeting dates, inclusive),
 *   $12 every committee in scope, $13 the committees in scope, $14 at most this many rows
 */
CREATE FUNCTION search_decision_stats_sql() RETURNS text
    LANGUAGE sql IMMUTABLE SECURITY INVOKER
AS $f$
    SELECT $q$
    SELECT d.forum_id, d.memo_type_id, d.memo_id, d.submission_id, d.meeting_date, d.decided_at, d.chain_submitted_at,
           d.outcome_kind, d.prior_deferrals
    FROM search_decision d
    WHERE d.tenant_id = $1 AND ($12 OR d.forum_id = ANY ($13)) AND d.classification_rank <= $3
      AND ($9 IS NULL OR d.forum_id = ANY ($9)) AND d.meeting_date >= $10 AND d.meeting_date <= $11
      AND d.submission_id IS NOT NULL
      AND search_decision_level(d.forum_id, d.classification_rank, d.item_id, d.item_parent_id, d.item_restricted_to,
              d.item_title_hidden, d.item_members_only, $2, $3, $4, $5, $6, $7, $8) = 'FULL'
    LIMIT $14
    $q$
$f$;

/*
 * Decision statistics for analytics (ADR-0070, design E08 §2.2): one row per decision on a SUBMISSION item whose meeting
 * date is from p_from to p_to, in the committees p_forums (all in scope when null or empty), that the viewer sees in FULL
 * under the register's one rule (search_decision_level, design E08 §1.3): its committee, memo type, memo and submission,
 * meeting date, when it was decided, when its chain was first submitted, its outcome kind and the deferrals earlier in its
 * chain. Nothing about a LINE or a NONE row is returned, so no count can include it. At most 100,000 rows (ten years of
 * thirty committees is 54,000). The viewer's parameters come from DecisionRegisterAccess, computed from live state.
 */
CREATE FUNCTION search_decision_stats(
    p_tenant uuid, p_viewer uuid, p_rank int, p_officer_all boolean, p_officers uuid[], p_members uuid[], p_reader boolean,
    p_recused uuid[], p_forums uuid[], p_from date, p_to date
) RETURNS TABLE (
    forum_id uuid, memo_type_id uuid, memo_id uuid, submission_id uuid, meeting_date date, decided_at timestamptz,
    chain_submitted_at timestamptz, outcome_kind text, prior_deferrals int
)
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = memo, pg_catalog
AS $$
DECLARE
    officers uuid[] := coalesce(p_officers, '{}');
    members uuid[] := coalesce(p_members, '{}');
    officer_all boolean := coalesce(p_officer_all, false);
    reader boolean := coalesce(p_reader, false);
    forums uuid[] := CASE WHEN cardinality(p_forums) > 0 THEN p_forums END;
BEGIN
    PERFORM search_tenant(p_tenant);
    IF p_viewer IS NULL OR p_rank IS NULL THEN
        RAISE EXCEPTION 'search: a viewer and a clearance are required' USING ERRCODE = 'invalid_parameter_value';
    END IF;
    IF p_from IS NULL OR p_to IS NULL OR p_to < p_from OR p_to - p_from > 3700 THEN
        RAISE EXCEPTION 'search: a period of at most ten years is required' USING ERRCODE = 'invalid_parameter_value';
    END IF;
    RETURN QUERY EXECUTE search_decision_stats_sql() USING
        p_tenant, p_viewer, p_rank, officer_all, officers, members, reader, coalesce(p_recused, '{}'),
        forums, p_from, p_to, reader OR officer_all, officers || members, 100000;
END $$;

-- As V4: only the entry point is callable by the application (new functions get EXECUTE through default privileges).
REVOKE ALL ON FUNCTION search_decision_stats_sql() FROM PUBLIC, memo_app;
REVOKE ALL ON FUNCTION search_decision_stats FROM PUBLIC;
GRANT EXECUTE ON FUNCTION search_decision_stats TO memo_app;
