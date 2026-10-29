-- Roles, role grants, classification levels and clearances (ADR-0027). Permissions are keys of the code catalogue
-- (com.memo.common.authz.Permission); built-in roles (key set) take their permissions from code, so only custom roles
-- have rows in authz_role_permission.

CREATE TABLE authz_role (
    id          uuid        PRIMARY KEY,
    tenant_id   uuid        NOT NULL,
    -- Built-in role key (tenant_admin, org_admin, auditor, user); null for custom roles.
    key         text        CHECK (key ~ '^[a-z_]{1,50}$'),
    name        text        NOT NULL CHECK (length(name) BETWEEN 1 AND 100),
    description text        CHECK (length(description) <= 500),
    version     bigint      NOT NULL DEFAULT 0,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, key)
);
CREATE UNIQUE INDEX authz_role_tenant_name_uk ON authz_role (tenant_id, lower(name));

CREATE TABLE authz_role_permission (
    tenant_id  uuid NOT NULL,
    role_id    uuid NOT NULL,
    permission text NOT NULL CHECK (permission ~ '^[a-z_.]{1,100}$'),
    PRIMARY KEY (tenant_id, role_id, permission),
    FOREIGN KEY (tenant_id, role_id) REFERENCES authz_role (tenant_id, id)
);

-- A role given to a user, tenant-wide (scope_unit_id null) or for a unit subtree (ADR-0027). No FKs to identity_user or
-- org_unit: modules migrate independently (in no guaranteed order), so references across modules are checked by the
-- services that write them.
CREATE TABLE authz_grant (
    id            uuid        PRIMARY KEY,
    tenant_id     uuid        NOT NULL,
    user_id       uuid        NOT NULL,
    role_id       uuid        NOT NULL,
    scope_unit_id uuid,
    granted_at    timestamptz NOT NULL,
    UNIQUE NULLS NOT DISTINCT (tenant_id, user_id, role_id, scope_unit_id),
    FOREIGN KEY (tenant_id, role_id) REFERENCES authz_role (tenant_id, id)
);
CREATE INDEX authz_grant_tenant_role_idx ON authz_grant (tenant_id, role_id);
CREATE INDEX authz_grant_tenant_scope_idx ON authz_grant (tenant_id, scope_unit_id);

-- Ordered, tenant-defined levels (rank 0 = lowest). Ranks are dense; reordering rewrites them in one transaction.
CREATE TABLE authz_classification_level (
    id          uuid        PRIMARY KEY,
    tenant_id   uuid        NOT NULL,
    rank        int         NOT NULL CHECK (rank BETWEEN 0 AND 99),
    name        text        NOT NULL CHECK (length(name) BETWEEN 1 AND 100),
    description text        CHECK (length(description) <= 500),
    version     bigint      NOT NULL DEFAULT 0,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, rank)
);
CREATE UNIQUE INDEX authz_classification_level_tenant_name_uk ON authz_classification_level (tenant_id, lower(name));

-- A user's clearance; no row means the lowest level.
CREATE TABLE authz_clearance (
    tenant_id  uuid        NOT NULL,
    user_id    uuid        NOT NULL,
    level_id   uuid        NOT NULL,
    version    bigint      NOT NULL DEFAULT 0,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, user_id),
    FOREIGN KEY (tenant_id, level_id) REFERENCES authz_classification_level (tenant_id, id)
);
CREATE INDEX authz_clearance_tenant_level_idx ON authz_clearance (tenant_id, level_id);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['authz_role', 'authz_role_permission', 'authz_grant', 'authz_classification_level',
                             'authz_clearance'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY tenant_isolation ON %I FOR ALL
            USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
            WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))$p$, t);
    END LOOP;
END $$;
