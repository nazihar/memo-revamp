-- Phase 3 E01 T8: the two backfills of V3 (Phase 2 E03) ran under FORCE row-level security with no tenant set, so they
-- changed no row in environments that already had data (dev: 16 rounds, 17 steps, 7 open instances). Rounds started
-- before E03 kept an empty route and their steps no route index, so the engine could not find the next entry of a
-- route. This repeats both backfills for those rows only: rounds whose steps all still carry the pre-E03 defaults
-- (node `route`, no route index). Steps started since E03 on a route always have their route index, and other steps
-- name their own node, so no round started since E03 matches.

-- FORCE binds the owner too and no tenant is set here, so lift it for this transaction (MigrationDmlRlsTest).
ALTER TABLE workflow_round NO FORCE ROW LEVEL SECURITY;
ALTER TABLE workflow_step NO FORCE ROW LEVEL SECURITY;

UPDATE workflow_round r
SET route = (SELECT array_agg(s.assignee_id ORDER BY s.ordinal) FROM workflow_step s WHERE s.round_id = r.id)
WHERE r.route = '{}'
  AND EXISTS (SELECT 1 FROM workflow_step s WHERE s.round_id = r.id)
  AND NOT EXISTS (
    SELECT 1 FROM workflow_step s WHERE s.round_id = r.id AND (s.node_id <> 'route' OR s.route_index IS NOT NULL)
  );

-- The subquery sees the steps as they were when the statement started, so a round's steps are judged together.
UPDATE workflow_step s
SET route_index = s.ordinal - 1
WHERE s.route_index IS NULL
  AND NOT EXISTS (
    SELECT 1 FROM workflow_step o WHERE o.round_id = s.round_id AND (o.node_id <> 'route' OR o.route_index IS NOT NULL)
  );

ALTER TABLE workflow_round FORCE ROW LEVEL SECURITY;
ALTER TABLE workflow_step FORCE ROW LEVEL SECURITY;
