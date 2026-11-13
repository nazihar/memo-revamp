-- E05 T3 (ADR-0041, design E05 §3, §4.2, §4.3): timers of decision tasks on SLA steps. The row is the source of truth;
-- each has one db-scheduler execution (`workflow.timer`, data: tenant and timer ids only) that fires nothing unless the
-- row is still pending and its task still open. Closing a task cancels its timers; moving it moves them. Expand only.
CREATE TABLE workflow_timer (
    id          uuid        PRIMARY KEY,
    tenant_id   uuid        NOT NULL,
    instance_id uuid        NOT NULL,
    step_id     uuid        NOT NULL,
    task_id     uuid        NOT NULL,
    kind        text        NOT NULL CHECK (kind IN ('REMIND_BEFORE', 'DUE', 'REMIND_AFTER', 'ESCALATE')),
    ordinal     int         NOT NULL DEFAULT 0 CHECK (ordinal BETWEEN 0 AND 9),
    fires_at    timestamptz NOT NULL,
    state       text        NOT NULL CHECK (state IN ('PENDING', 'FIRED', 'CANCELLED')),
    fired_at    timestamptz,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, instance_id) REFERENCES workflow_instance (tenant_id, id)
);
CREATE INDEX workflow_timer_instance_idx ON workflow_timer (tenant_id, instance_id);
CREATE INDEX workflow_timer_task_idx ON workflow_timer (tenant_id, task_id) WHERE state = 'PENDING';
CREATE INDEX workflow_timer_due_idx ON workflow_timer (tenant_id, fires_at) WHERE state = 'PENDING';

ALTER TABLE workflow_timer ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow_timer FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON workflow_timer FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));
