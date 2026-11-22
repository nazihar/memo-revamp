-- Phase 4 E06 T9 (design E06 §8): found at volume. Every decision recorded or corrected asks which minutes it approved
-- (`MinutesQueries.approvedBy`: a correction that no longer approves them sends minutes still with the chair back to the
-- draft), by `approving_decision_id`, which no index covered: each decision read the tenant's whole `minutes` table
-- (3,600 rows at the design's volume, one per held meeting and growing by a meeting a month per committee). Few minutes
-- have an approving decision at any time beyond the approved ones, and the lookup is by one id: a partial index.
-- Expand only: one index, no data.
CREATE INDEX minutes_approving_decision_idx ON minutes (tenant_id, approving_decision_id) WHERE approving_decision_id IS NOT NULL;
