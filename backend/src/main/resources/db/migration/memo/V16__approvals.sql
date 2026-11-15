-- Phase 3 E03 T6 (ADR-0051, design E03 §6): authenticated approvals. One row per approve, return or reject, written with
-- the action: who decided, how they had authenticated (factors, the age of the second factor, whether a step-up was
-- required, a hash of the session id), the client as the trusted proxy reported it, the hashes of the content decided
-- on, and a record hash chained to the memo's previous approval. Append-only.

CREATE TABLE memo_approval (
    id            uuid        PRIMARY KEY,
    tenant_id     uuid        NOT NULL,
    memo_id       uuid        NOT NULL,
    round         int         NOT NULL CHECK (round >= 1),
    -- The workflow action, by its sequence in the memo's instance.
    action_seq    bigint      NOT NULL,
    decision      text        NOT NULL CHECK (decision IN ('APPROVE', 'RECOMMEND', 'RETURN', 'REJECT')),
    actor_id      uuid        NOT NULL,
    acting_for_id uuid,
    position_id   uuid,
    decided_at    timestamptz NOT NULL,
    auth          jsonb       NOT NULL CHECK (jsonb_typeof(auth) = 'object' AND octet_length(auth::text) <= 4096),
    client        jsonb       NOT NULL CHECK (jsonb_typeof(client) = 'object' AND octet_length(client::text) <= 4096),
    content       jsonb       NOT NULL CHECK (jsonb_typeof(content) = 'object' AND octet_length(content::text) <= 65536),
    -- Lower-case hex SHA-256; prev_hash is null for the memo's first approval.
    prev_hash     text        CHECK (prev_hash ~ '^[0-9a-f]{64}$'),
    record_hash   text        NOT NULL CHECK (record_hash ~ '^[0-9a-f]{64}$'),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, memo_id, action_seq),
    FOREIGN KEY (tenant_id, memo_id) REFERENCES memo (tenant_id, id)
);

ALTER TABLE memo_approval ENABLE ROW LEVEL SECURITY;
ALTER TABLE memo_approval FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON memo_approval FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

REVOKE UPDATE, DELETE, TRUNCATE ON memo_approval FROM memo_app;
