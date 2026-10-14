-- Tenant registry and the reference tenant table (ADR-0006, E02 design §1/§7).

CREATE TABLE tenancy_tenant (
    id         uuid        PRIMARY KEY,
    slug       text        NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9](?:[a-z0-9-]{1,61}[a-z0-9])$'),
    name       text        NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
    status     text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    version    bigint      NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL
);

-- A tenant sees only its own registry row; creation happens inside the new tenant's own context.
ALTER TABLE tenancy_tenant ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy_tenant FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_self ON tenancy_tenant FOR ALL
    USING      (id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

-- Reference tenant table: per-tenant key/value configuration.
CREATE TABLE tenancy_setting (
    id         uuid        PRIMARY KEY,
    tenant_id  uuid        NOT NULL REFERENCES tenancy_tenant (id),
    key        text        NOT NULL CHECK (key ~ '^[a-z][a-z0-9.]{1,99}$'),
    value      text        NOT NULL CHECK (length(value) <= 4000),
    version    bigint      NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);

-- tenant_id leads; also serves as the index for the tenant_id foreign key.
CREATE UNIQUE INDEX tenancy_setting_tenant_key_uk ON tenancy_setting (tenant_id, key);

ALTER TABLE tenancy_setting ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy_setting FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenancy_setting FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

-- Views run with the caller's rights so RLS applies (never the owner's).
CREATE VIEW tenancy_setting_view WITH (security_invoker = true) AS
SELECT s.tenant_id, t.slug AS tenant_slug, t.name AS tenant_name, s.key, s.value
FROM tenancy_setting s
JOIN tenancy_tenant t ON t.id = s.tenant_id;

-- Functions are SECURITY INVOKER (the default); SECURITY DEFINER is forbidden by the RLS meta-test.
CREATE FUNCTION tenancy_setting_count() RETURNS bigint
    LANGUAGE sql STABLE SECURITY INVOKER
AS $$ SELECT count(*) FROM tenancy_setting $$;
