-- Tenant terminology overrides (ADR-0032): per term and language, the singular and plural the tenant uses, and the
-- grammatical gender where the language needs it (Arabic). Null forms mean "the catalogue's default"; rows are never
-- deleted, so the version sum used for If-Match only grows.
CREATE TABLE tenancy_term (
    id         uuid        PRIMARY KEY,
    tenant_id  uuid        NOT NULL REFERENCES tenancy_tenant (id),
    term       text        NOT NULL CHECK (term IN ('unit', 'position', 'memo')),
    locale     text        NOT NULL CHECK (locale IN ('en', 'ar')),
    one        text        CHECK (length(one) BETWEEN 1 AND 40),
    other      text        CHECK (length(other) BETWEEN 1 AND 40),
    gender     text        CHECK (gender IN ('masculine', 'feminine')),
    version    bigint      NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CHECK ((one IS NULL) = (other IS NULL))
);

-- tenant_id leads; also serves the tenant_id foreign key.
CREATE UNIQUE INDEX tenancy_term_tenant_term_locale_uk ON tenancy_term (tenant_id, term, locale);

ALTER TABLE tenancy_term ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy_term FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenancy_term FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));
