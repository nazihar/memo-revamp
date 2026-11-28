-- Position steps follow holder changes that take effect on a date (ADR-0068 amendment, 2026-11-28): the day each tenant's
-- open position-assigned steps were last brought in line with the holders on that day, so the `workflow-position-holders`
-- task runs a tenant's day once, at the start of the day in the tenant's zone, and re-resolves only the steps whose
-- positions changed holder since. One row per tenant, locked while the run plans. Expand only: old app versions never
-- read it.
CREATE TABLE workflow_holder_sync (
    tenant_id uuid        NOT NULL,
    synced_on date,
    synced_at timestamptz,
    CHECK ((synced_on IS NULL) = (synced_at IS NULL)),
    PRIMARY KEY (tenant_id)
);

ALTER TABLE workflow_holder_sync ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow_holder_sync FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON workflow_holder_sync FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

REVOKE DELETE, TRUNCATE ON workflow_holder_sync FROM memo_app;
