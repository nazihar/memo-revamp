-- Phase 4 E07 T9 (design E07 §8, §9): indexes for the `actions-daily` task, found by ActionsVolumeIT's plans at the
-- design's volume (29,000 actions, 3,400 open, 5,000 people). Expand only: two indexes, no data.

-- The open actions of a tenant, oldest first: the safety net's pass (`ActionSafetyNet`) reads them in this order, and the
-- update requests find the committees with actions under way (`forumsWithOpen`) from the included columns alone. Without
-- it both scanned every action of the tenant (about one in eight is open).
CREATE INDEX actions_item_open_idx ON actions_item (tenant_id, created_at, id) INCLUDE (forum_id, status, reference)
    WHERE status IN ('OPEN', 'IN_PROGRESS', 'DONE_PENDING');

-- The digest keys by day: who had their digest today, and each person's latest digest within the digest's lookback
-- (`ActionDigestStore.had`, `lastDays`). The primary key (tenant, person, day) serves one person; the daily task asks for
-- up to 5,000 at once.
CREATE INDEX actions_digest_day_idx ON actions_digest (tenant_id, day, user_id);
