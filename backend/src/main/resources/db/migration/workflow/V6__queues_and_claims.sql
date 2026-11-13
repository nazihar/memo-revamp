-- E04 T3 (ADR-0040, design E04 §3, §4.2): queues of people (directly, or as the holders of positions) that a step can
-- go to, and claims on steps shared by several candidates. Expand only.
CREATE TABLE workflow_queue (
    id         uuid        PRIMARY KEY,
    tenant_id  uuid        NOT NULL,
    key        text        NOT NULL CHECK (key ~ '^[a-z][a-z0-9-]{0,99}$'),
    name       text        NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
    version    bigint      NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, key)
);

CREATE TABLE workflow_queue_member (
    tenant_id   uuid NOT NULL,
    queue_id    uuid NOT NULL,
    user_id     uuid,
    position_id uuid,
    CHECK ((user_id IS NULL) <> (position_id IS NULL)),
    FOREIGN KEY (tenant_id, queue_id) REFERENCES workflow_queue (tenant_id, id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX workflow_queue_member_user_idx ON workflow_queue_member (tenant_id, queue_id, user_id) WHERE user_id IS NOT NULL;
CREATE UNIQUE INDEX workflow_queue_member_position_idx
    ON workflow_queue_member (tenant_id, queue_id, position_id) WHERE position_id IS NOT NULL;

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['workflow_queue', 'workflow_queue_member'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY tenant_isolation ON %I FOR ALL
            USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
            WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))$p$, t);
    END LOOP;
END $$;

-- The candidate who claimed a shared step; only their task may decide it until they release it.
ALTER TABLE workflow_step ADD COLUMN claimed_by uuid;

ALTER TABLE workflow_action DROP CONSTRAINT workflow_action_kind_check;
ALTER TABLE workflow_action
    ADD CONSTRAINT workflow_action_kind_check CHECK (kind IN (
        'SUBMIT', 'APPROVE', 'RETURN', 'REJECT', 'WITHDRAW', 'RESUBMIT', 'REASSIGN',
        'RECOMMEND', 'ROUTE', 'NOTIFY', 'ESCALATE_TO_FORUM', 'SEND_BACK', 'FORWARD', 'DELEGATE', 'ASK', 'ANSWER',
        'REFER', 'OPINION', 'RECALL', 'CANCEL', 'MIGRATE', 'CARRY', 'CLAIM', 'RELEASE'));
