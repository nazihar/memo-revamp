-- E04 T2 (ADR-0040, design E04 §3): steps record who their assignee resolved to and why, a step offered to several
-- candidates needs a number of their decisions, and a step nobody could be found for is held by a task without an
-- assignee, blocked until an administrator assigns it. Expand only.
ALTER TABLE workflow_step
    ALTER COLUMN assignee_id DROP NOT NULL,
    ADD COLUMN needed     int   CHECK (needed BETWEEN 1 AND 20),
    ADD COLUMN resolution jsonb CHECK (octet_length(resolution::text) <= 8192);

ALTER TABLE workflow_task ALTER COLUMN assignee_id DROP NOT NULL;
ALTER TABLE workflow_task DROP CONSTRAINT workflow_task_blocked_reason_check;
ALTER TABLE workflow_task
    ADD CONSTRAINT workflow_task_blocked_reason_check CHECK (blocked_reason IN ('NO_CLEARANCE', 'INACTIVE', 'UNRESOLVED')),
    ADD CONSTRAINT workflow_task_assignee_check CHECK (assignee_id IS NOT NULL OR blocked_reason = 'UNRESOLVED');
