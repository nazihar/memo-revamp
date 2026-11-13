-- E05 T2 (ADR-0041, design E05 §3, §4.2): when an open decision task is due, from its step's SLA in the tenant's
-- working time. Shared by the step's tasks (moves keep it); a step that runs again gets a new one. Expand only.
ALTER TABLE workflow_task ADD COLUMN due_at timestamptz;
CREATE INDEX workflow_task_due_idx ON workflow_task (tenant_id, due_at) WHERE state = 'OPEN' AND due_at IS NOT NULL;
