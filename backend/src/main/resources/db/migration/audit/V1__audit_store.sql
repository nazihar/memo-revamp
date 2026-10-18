-- Audit store (ADR-0019): append-only, hash-chained per tenant, written in the business transaction.
-- The platform chain uses the zero tenant id and is visible only in platform scope (ADR-0017).

CREATE TABLE audit_event (
    id                uuid        NOT NULL,
    tenant_id         uuid        NOT NULL,
    seq               bigint      NOT NULL CHECK (seq > 0),
    occurred_at       timestamptz NOT NULL,
    actor_type        text        NOT NULL CHECK (actor_type IN ('USER', 'SYSTEM', 'PLATFORM_OPERATOR', 'ANONYMOUS')),
    actor_id          text        CHECK (length(actor_id) <= 200),
    acting_as_id      text        CHECK (length(acting_as_id) <= 200),
    client_ip         text        CHECK (length(client_ip) <= 64),
    client_user_agent text        CHECK (length(client_user_agent) <= 256),
    correlation_id    text        CHECK (length(correlation_id) <= 64),
    action            text        NOT NULL CHECK (action ~ '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$' AND length(action) <= 100),
    object_type       text        NOT NULL CHECK (object_type ~ '^[a-z][a-z0-9_.]*$' AND length(object_type) <= 100),
    object_id         text        CHECK (length(object_id) <= 200),
    canonical         text        NOT NULL,
    prev_hash         bytea       NOT NULL CHECK (length(prev_hash) = 32),
    hash              bytea       NOT NULL CHECK (length(hash) = 32),
    -- Unique keys of a partitioned table must contain the partition key; (tenant_id, seq) uniqueness is guaranteed by
    -- the chain-head lock and checked by the verifier instead.
    PRIMARY KEY (tenant_id, occurred_at, id)
) PARTITION BY RANGE (occurred_at);

CREATE INDEX audit_event_tenant_seq_idx ON audit_event (tenant_id, seq);
CREATE INDEX audit_event_tenant_object_idx ON audit_event (tenant_id, object_type, object_id, occurred_at);
CREATE INDEX audit_event_tenant_actor_idx ON audit_event (tenant_id, actor_id, occurred_at);

-- Every chain's head: the last sequence number and hash. Locked FOR UPDATE by each flush.
CREATE TABLE audit_chain_head (
    tenant_id  uuid        PRIMARY KEY,
    last_seq   bigint      NOT NULL CHECK (last_seq >= 0),
    last_hash  bytea       NOT NULL CHECK (length(last_hash) = 32),
    updated_at timestamptz NOT NULL
);

-- Verification checkpoint per chain.
CREATE TABLE audit_verification (
    tenant_id     uuid        PRIMARY KEY,
    verified_seq  bigint      NOT NULL CHECK (verified_seq >= 0),
    verified_hash bytea       NOT NULL CHECK (length(verified_hash) = 32),
    verified_at   timestamptz NOT NULL,
    status        text        NOT NULL CHECK (status IN ('OK', 'BROKEN')),
    broken_at_seq bigint,
    reason        text        CHECK (length(reason) <= 500)
);

-- What was written to the object-locked bucket (the bucket is the authority).
CREATE TABLE audit_anchor (
    id           uuid        PRIMARY KEY,
    tenant_id    uuid        NOT NULL,
    seq          bigint      NOT NULL CHECK (seq > 0),
    hash         bytea       NOT NULL CHECK (length(hash) = 32),
    object_key   text        NOT NULL,
    retain_until timestamptz NOT NULL,
    anchored_at  timestamptz NOT NULL
);
CREATE UNIQUE INDEX audit_anchor_tenant_seq_uk ON audit_anchor (tenant_id, seq);

-- RLS: the tenant policy (ADR-0006) plus the platform chain, visible only in platform scope.
DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['audit_event', 'audit_chain_head', 'audit_verification', 'audit_anchor'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY tenant_isolation ON %I FOR ALL
            USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
            WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))$p$, t);
        EXECUTE format($p$CREATE POLICY platform_chain ON %I FOR ALL
            USING      (tenant_id = '00000000-0000-0000-0000-000000000000'::uuid
                        AND (select current_setting('app.scope', true)) = 'platform')
            WITH CHECK (tenant_id = '00000000-0000-0000-0000-000000000000'::uuid
                        AND (select current_setting('app.scope', true)) = 'platform')$p$, t);
    END LOOP;
END $$;

-- Append-only: no UPDATE/DELETE/TRUNCATE for the app role, and a trigger that refuses them for everyone.
REVOKE UPDATE, DELETE, TRUNCATE ON audit_event FROM memo_app;

CREATE FUNCTION audit_event_immutable() RETURNS trigger
    LANGUAGE plpgsql SECURITY INVOKER
AS $$
BEGIN
    RAISE EXCEPTION 'audit_event is append-only (% refused)', TG_OP USING ERRCODE = 'insufficient_privilege';
END $$;
REVOKE EXECUTE ON FUNCTION audit_event_immutable() FROM PUBLIC, memo_app;

CREATE TRIGGER audit_event_no_update_or_delete BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION audit_event_immutable();
CREATE TRIGGER audit_event_no_truncate BEFORE TRUNCATE ON audit_event
    FOR EACH STATEMENT EXECUTE FUNCTION audit_event_immutable();

-- Monthly partitions (UTC), each with the same RLS and grants as the parent. Called by the migrator only, from the
-- Flyway afterMigrate callback (AuditPartitionCallback) on every migration run, so partitions exist months ahead.
CREATE FUNCTION audit_ensure_partitions(months_ahead int) RETURNS int
    LANGUAGE plpgsql SECURITY INVOKER
AS $$
DECLARE
    month_start date;
    part        text;
    created     int := 0;
BEGIN
    FOR i IN 0..months_ahead LOOP
        month_start := (date_trunc('month', now() AT TIME ZONE 'UTC') + make_interval(months => i))::date;
        part := format('audit_event_y%sm%s', to_char(month_start, 'YYYY'), to_char(month_start, 'MM'));
        IF to_regclass(part) IS NULL THEN
            EXECUTE format('CREATE TABLE %I PARTITION OF audit_event FOR VALUES FROM (%L) TO (%L)', part,
                           to_char(month_start, 'YYYY-MM-DD') || ' 00:00:00+00',
                           to_char(month_start + interval '1 month', 'YYYY-MM-DD') || ' 00:00:00+00');
            PERFORM audit_secure_partition(part);
            created := created + 1;
        END IF;
    END LOOP;
    RETURN created;
END $$;

CREATE FUNCTION audit_secure_partition(part text) RETURNS void
    LANGUAGE plpgsql SECURITY INVOKER
AS $$
BEGIN
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', part);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', part);
    EXECUTE format($p$CREATE POLICY tenant_isolation ON %I FOR ALL
        USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
        WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))$p$, part);
    EXECUTE format($p$CREATE POLICY platform_chain ON %I FOR ALL
        USING      (tenant_id = '00000000-0000-0000-0000-000000000000'::uuid
                    AND (select current_setting('app.scope', true)) = 'platform')
        WITH CHECK (tenant_id = '00000000-0000-0000-0000-000000000000'::uuid
                    AND (select current_setting('app.scope', true)) = 'platform')$p$, part);
    EXECUTE format('REVOKE UPDATE, DELETE, TRUNCATE ON %I FROM memo_app', part);
END $$;

REVOKE EXECUTE ON FUNCTION audit_ensure_partitions(int) FROM PUBLIC, memo_app;
REVOKE EXECUTE ON FUNCTION audit_secure_partition(text) FROM PUBLIC, memo_app;

-- Safety net for rows outside every monthly partition (the partition-check job alerts long before this is needed).
CREATE TABLE audit_event_default PARTITION OF audit_event DEFAULT;
SELECT audit_secure_partition('audit_event_default');
SELECT audit_ensure_partitions(12);
