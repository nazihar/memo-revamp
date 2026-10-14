-- Platform (non-tenant) table owned by the `system` module. No tenant_id, therefore no RLS (AGENTS.md §3.2 applies to tenant tables).
CREATE TABLE system_info (
    id            smallint    PRIMARY KEY CHECK (id = 1),
    schema_marker text        NOT NULL,
    installed_at  timestamptz NOT NULL DEFAULT now()
);

INSERT INTO system_info (id, schema_marker) VALUES (1, 'memo-revamp');

-- Read-only for the application: the marker is written once by the migration.
REVOKE INSERT, UPDATE, DELETE ON system_info FROM memo_app;
