-- Idempotency-Key for creating requests (AGENTS.md §4 "double submit"): the first request under a key does the work
-- and stores its result; a repeat with the same key and body gets that result again. Per tenant, per operation.
CREATE TABLE system_idempotency_key (
    tenant_id    uuid        NOT NULL,
    operation    text        NOT NULL CHECK (length(operation) <= 100),
    key          text        NOT NULL CHECK (length(key) BETWEEN 1 AND 200),
    request_hash bytea       NOT NULL CHECK (length(request_hash) = 32),
    result       text        CHECK (length(result) <= 2000),
    created_at   timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, operation, key)
);
CREATE INDEX system_idempotency_key_tenant_created_idx ON system_idempotency_key (tenant_id, created_at);

ALTER TABLE system_idempotency_key ENABLE ROW LEVEL SECURITY;
ALTER TABLE system_idempotency_key FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON system_idempotency_key FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));
