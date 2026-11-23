-- Phase 4 E08 T5 (design E08 §8, analytics M2): the actions of some committees closed in a period ("completed on time",
-- ActionStats.closedOnTime) read through the committees' index read every action they ever had (about 24,000 for 30
-- committees at the design's volume) and kept the year's; this one reads the period's closed actions only. Expand only:
-- a new partial index, tenant_id first; old app versions ignore it.
CREATE INDEX actions_item_closed_idx ON actions_item (tenant_id, forum_id, closed_at) WHERE status = 'CLOSED';
