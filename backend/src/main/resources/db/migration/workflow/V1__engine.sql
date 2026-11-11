-- Workflow engine (ADR-0034, ADR-0035). An instance routes one subject (for example a memo) through rounds; each round
-- has ordered steps, each step tasks for people, and every action is appended to workflow_action. The subject is known
-- only by type and id: no FKs to other modules' tables, and user ids are checked by the services.

CREATE TABLE workflow_definition (
    id         uuid        PRIMARY KEY,
    tenant_id  uuid        NOT NULL,
    key        text        NOT NULL CHECK (key ~ '^[a-z][a-z0-9-]{0,99}$'),
    name       text        NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
    built_in   boolean     NOT NULL,
    created_at timestamptz NOT NULL,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, key)
);

CREATE TABLE workflow_definition_version (
    id             uuid        PRIMARY KEY,
    tenant_id      uuid        NOT NULL,
    definition_id  uuid        NOT NULL,
    number         int         NOT NULL CHECK (number >= 1),
    body           jsonb       NOT NULL CHECK (octet_length(body::text) <= 1048576),
    schema_version int         NOT NULL CHECK (schema_version >= 1),
    -- E03 adds the maker-checker lifecycle; E01 only publishes built-in versions.
    state          text        NOT NULL CHECK (state IN ('DRAFT', 'IN_REVIEW', 'PUBLISHED', 'RETIRED')),
    created_at     timestamptz NOT NULL,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, definition_id, number),
    FOREIGN KEY (tenant_id, definition_id) REFERENCES workflow_definition (tenant_id, id)
);

CREATE TABLE workflow_instance (
    id                    uuid        PRIMARY KEY,
    tenant_id             uuid        NOT NULL,
    subject_type          text        NOT NULL CHECK (subject_type ~ '^[a-z][a-z0-9.-]{0,99}$'),
    subject_id            uuid        NOT NULL,
    definition_version_id uuid        NOT NULL,
    initiator_id          uuid        NOT NULL,
    status                text        NOT NULL CHECK (status IN ('RUNNING', 'RETURNED', 'APPROVED', 'REJECTED', 'WITHDRAWN')),
    current_round         int         NOT NULL CHECK (current_round >= 1),
    -- Denormalised "who holds it now" for inboxes and the sent box (ADR-0035): kept equal to the open tasks.
    current_step_id       uuid,
    current_assignees     uuid[]      NOT NULL DEFAULT '{}',
    created_at            timestamptz NOT NULL,
    updated_at            timestamptz NOT NULL,
    version               bigint      NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    -- A subject has at most one instance; it continues through every round (design E01 §2, invariant 1).
    UNIQUE (tenant_id, subject_type, subject_id),
    CHECK ((status = 'RUNNING') = (current_step_id IS NOT NULL)),
    FOREIGN KEY (tenant_id, definition_version_id) REFERENCES workflow_definition_version (tenant_id, id)
);
CREATE INDEX workflow_instance_definition_idx ON workflow_instance (tenant_id, definition_version_id);

CREATE TABLE workflow_round (
    id             uuid        PRIMARY KEY,
    tenant_id      uuid        NOT NULL,
    instance_id    uuid        NOT NULL,
    number         int         NOT NULL CHECK (number >= 1),
    outcome        text        NOT NULL CHECK (outcome IN ('OPEN', 'RETURNED', 'APPROVED', 'REJECTED', 'WITHDRAWN')),
    started_by     uuid        NOT NULL,
    started_at     timestamptz NOT NULL,
    ended_at       timestamptz,
    -- What the subject looked like when the round started (for memos: the document version and its SHA-256).
    start_snapshot jsonb       NOT NULL DEFAULT '{}' CHECK (octet_length(start_snapshot::text) <= 4096),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, instance_id, number),
    CHECK ((outcome = 'OPEN') = (ended_at IS NULL)),
    FOREIGN KEY (tenant_id, instance_id) REFERENCES workflow_instance (tenant_id, id)
);
-- At most one open round per instance (invariant 2).
CREATE UNIQUE INDEX workflow_round_one_open_idx ON workflow_round (tenant_id, instance_id) WHERE outcome = 'OPEN';

CREATE TABLE workflow_step (
    id          uuid PRIMARY KEY,
    tenant_id   uuid NOT NULL,
    instance_id uuid NOT NULL,
    round_id    uuid NOT NULL,
    ordinal     int  NOT NULL CHECK (ordinal >= 1),
    type        text NOT NULL CHECK (type IN ('APPROVE')),
    assignee_id uuid NOT NULL,
    state       text NOT NULL CHECK (state IN ('PENDING', 'ACTIVE', 'DONE', 'SKIPPED')),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, round_id, ordinal),
    FOREIGN KEY (tenant_id, round_id) REFERENCES workflow_round (tenant_id, id),
    FOREIGN KEY (tenant_id, instance_id) REFERENCES workflow_instance (tenant_id, id)
);
CREATE INDEX workflow_step_instance_idx ON workflow_step (tenant_id, instance_id);

CREATE TABLE workflow_task (
    id             uuid        PRIMARY KEY,
    tenant_id      uuid        NOT NULL,
    instance_id    uuid        NOT NULL,
    step_id        uuid        NOT NULL,
    assignee_id    uuid        NOT NULL,
    state          text        NOT NULL CHECK (state IN ('OPEN', 'COMPLETED', 'CANCELLED', 'REASSIGNED')),
    blocked_reason text        CHECK (blocked_reason IN ('NO_CLEARANCE', 'INACTIVE')),
    outcome        text        CHECK (outcome IN ('APPROVE', 'RETURN', 'REJECT')),
    created_at     timestamptz NOT NULL,
    completed_at   timestamptz,
    version        bigint      NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    CHECK ((state = 'OPEN') = (completed_at IS NULL)),
    CHECK ((state = 'COMPLETED') = (outcome IS NOT NULL)),
    FOREIGN KEY (tenant_id, step_id) REFERENCES workflow_step (tenant_id, id),
    FOREIGN KEY (tenant_id, instance_id) REFERENCES workflow_instance (tenant_id, id)
);
-- The inbox: my open tasks, newest first (design E01 §10).
CREATE INDEX workflow_task_inbox_idx ON workflow_task (tenant_id, assignee_id, state, created_at DESC, id);
CREATE INDEX workflow_task_instance_idx ON workflow_task (tenant_id, instance_id);
CREATE INDEX workflow_task_step_idx ON workflow_task (tenant_id, step_id);
-- One open task per step in sequential rounds (invariant 3); E03's parallel steps relax this with a new index.
CREATE UNIQUE INDEX workflow_task_one_open_idx ON workflow_task (tenant_id, step_id) WHERE state = 'OPEN';

-- The history (ADR-0035): append-only, numbered per instance without gaps under the instance row lock.
CREATE TABLE workflow_action (
    id               uuid        PRIMARY KEY,
    tenant_id        uuid        NOT NULL,
    instance_id      uuid        NOT NULL,
    seq              bigint      NOT NULL CHECK (seq >= 1),
    round            int         NOT NULL CHECK (round >= 1),
    task_id          uuid,
    kind             text        NOT NULL CHECK (kind IN ('SUBMIT', 'APPROVE', 'RETURN', 'REJECT', 'WITHDRAW', 'RESUBMIT', 'REASSIGN')),
    actor_id         uuid        NOT NULL,
    acting_for_id    uuid,
    remark           text        CHECK (length(remark) BETWEEN 1 AND 4000),
    subject_snapshot jsonb       NOT NULL DEFAULT '{}' CHECK (octet_length(subject_snapshot::text) <= 4096),
    occurred_at      timestamptz NOT NULL,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, instance_id, seq),
    FOREIGN KEY (tenant_id, instance_id) REFERENCES workflow_instance (tenant_id, id)
);
CREATE INDEX workflow_action_task_idx ON workflow_action (tenant_id, task_id) WHERE task_id IS NOT NULL;

REVOKE UPDATE, DELETE, TRUNCATE ON workflow_action FROM memo_app;
REVOKE DELETE, TRUNCATE ON workflow_definition, workflow_definition_version, workflow_instance, workflow_round,
    workflow_step, workflow_task FROM memo_app;

CREATE FUNCTION workflow_action_guard() RETURNS trigger
    LANGUAGE plpgsql SECURITY INVOKER
AS $$
BEGIN
    RAISE EXCEPTION 'workflow_action is append-only (% refused)', TG_OP USING ERRCODE = 'insufficient_privilege';
END $$;
REVOKE EXECUTE ON FUNCTION workflow_action_guard() FROM PUBLIC, memo_app;

CREATE TRIGGER workflow_action_append_only BEFORE UPDATE OR DELETE ON workflow_action
    FOR EACH ROW EXECUTE FUNCTION workflow_action_guard();
CREATE TRIGGER workflow_action_no_truncate BEFORE TRUNCATE ON workflow_action
    FOR EACH STATEMENT EXECUTE FUNCTION workflow_action_guard();

ALTER TABLE workflow_definition ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow_definition FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON workflow_definition FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

ALTER TABLE workflow_definition_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow_definition_version FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON workflow_definition_version FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

ALTER TABLE workflow_instance ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow_instance FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON workflow_instance FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

ALTER TABLE workflow_round ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow_round FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON workflow_round FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

ALTER TABLE workflow_step ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow_step FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON workflow_step FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

ALTER TABLE workflow_task ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow_task FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON workflow_task FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

ALTER TABLE workflow_action ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow_action FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON workflow_action FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));
