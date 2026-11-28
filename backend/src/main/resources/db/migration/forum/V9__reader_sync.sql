-- Committee officers' memo access follows the calendar (ADR-0059, the E08 T10 finding): the day each tenant's `FORUM`
-- readers were last brought in line with the chair and secretaries in office, so the `forum-officer-readers` task runs a
-- tenant's day once, at the start of the day in the tenant's zone, and resyncs only the committees whose officers changed
-- since. One row per tenant, locked for the run. Expand only: old app versions never read it.
CREATE TABLE forum_reader_sync (
    tenant_id uuid        NOT NULL,
    synced_on date,
    synced_at timestamptz,
    CHECK ((synced_on IS NULL) = (synced_at IS NULL)),
    PRIMARY KEY (tenant_id)
);

ALTER TABLE forum_reader_sync ENABLE ROW LEVEL SECURITY;
ALTER TABLE forum_reader_sync FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON forum_reader_sync FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

REVOKE DELETE, TRUNCATE ON forum_reader_sync FROM memo_app;
