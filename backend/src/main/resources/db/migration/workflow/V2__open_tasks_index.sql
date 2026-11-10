-- Administrators' open-task list for reassignment (design E01 §5, T6): every open task of the tenant, newest first,
-- optionally only the blocked ones. Partial, so it stays as small as the number of open tasks.
CREATE INDEX workflow_task_open_idx ON workflow_task (tenant_id, created_at DESC, id) WHERE state = 'OPEN';
