-- E05 T1 (ADR-0041, design E05 §3, §4.1): the tenant's business calendar for SLAs in working time. Weekly working
-- periods per ISO weekday, and dated exceptions: other periods for that date, or none (closed). The time zone is the
-- tenant's (default.timezone). No row: Sunday to Thursday, 9:00 to 17:00. Expand only.
CREATE TABLE workflow_calendar (
    tenant_id  uuid        PRIMARY KEY,
    week       jsonb       NOT NULL CHECK (jsonb_typeof(week) = 'object' AND octet_length(week::text) <= 4096),
    version    bigint      NOT NULL DEFAULT 0,
    updated_at timestamptz NOT NULL
);

CREATE TABLE workflow_calendar_day (
    tenant_id uuid NOT NULL,
    day       date NOT NULL,
    periods   jsonb NOT NULL CHECK (jsonb_typeof(periods) = 'array' AND octet_length(periods::text) <= 1024),
    note      text CHECK (length(note) <= 200),
    PRIMARY KEY (tenant_id, day)
);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['workflow_calendar', 'workflow_calendar_day'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY tenant_isolation ON %I FOR ALL
            USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
            WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))$p$, t);
    END LOOP;
END $$;
