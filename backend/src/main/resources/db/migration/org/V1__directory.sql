-- Org directory (ADR-0026): legal entities, a tree of units, positions in units, effective-dated assignments of people
-- to positions and reporting lines between positions. Nothing is deleted: units, positions and legal entities are closed
-- (valid_to), assignments and reporting lines are ended or cancelled. Date ranges are half-open [valid_from, valid_to)
-- calendar dates in the tenant's time zone; valid_to null means open-ended. No FKs to other modules' tables (modules
-- migrate independently): user ids are checked by the services.

CREATE TABLE org_legal_entity (
    id                  uuid        PRIMARY KEY,
    tenant_id           uuid        NOT NULL,
    name                text        NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
    short_name          text        CHECK (length(short_name) <= 50),
    registration_number text        CHECK (length(registration_number) <= 100),
    valid_from          date        NOT NULL,
    valid_to            date        CHECK (valid_to > valid_from),
    version             bigint      NOT NULL DEFAULT 0,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    UNIQUE (tenant_id, id)
);
CREATE UNIQUE INDEX org_legal_entity_tenant_name_uk ON org_legal_entity (tenant_id, lower(name)) WHERE valid_to IS NULL;

-- `ancestors` is derived from parent_id (root first, without the unit itself) and rewritten for a whole subtree when a
-- unit moves; it lets SQL filter by subtree (`ancestors @> array[:unit]`) without recursion.
CREATE TABLE org_unit (
    id              uuid        PRIMARY KEY,
    tenant_id       uuid        NOT NULL,
    legal_entity_id uuid        NOT NULL,
    parent_id       uuid,
    ancestors       uuid[]      NOT NULL DEFAULT '{}',
    code            text        NOT NULL CHECK (code ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,29}$'),
    name            text        NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
    valid_from      date        NOT NULL,
    valid_to        date        CHECK (valid_to > valid_from),
    version         bigint      NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    UNIQUE (tenant_id, id),
    CHECK (parent_id IS NULL OR parent_id <> id),
    FOREIGN KEY (tenant_id, legal_entity_id) REFERENCES org_legal_entity (tenant_id, id),
    FOREIGN KEY (tenant_id, parent_id) REFERENCES org_unit (tenant_id, id)
);
CREATE INDEX org_unit_tenant_parent_idx ON org_unit (tenant_id, parent_id);
CREATE INDEX org_unit_tenant_entity_idx ON org_unit (tenant_id, legal_entity_id);
CREATE INDEX org_unit_ancestors_idx ON org_unit USING gin (ancestors);
CREATE UNIQUE INDEX org_unit_tenant_code_uk ON org_unit (tenant_id, lower(code)) WHERE valid_to IS NULL;

CREATE TABLE org_position (
    id         uuid        PRIMARY KEY,
    tenant_id  uuid        NOT NULL,
    unit_id    uuid        NOT NULL,
    code       text        CHECK (code ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,29}$'),
    title      text        NOT NULL CHECK (length(title) BETWEEN 1 AND 200),
    -- Head of its unit; at most one open head position per unit.
    head       boolean     NOT NULL DEFAULT false,
    valid_from date        NOT NULL,
    valid_to   date        CHECK (valid_to > valid_from),
    version    bigint      NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, unit_id) REFERENCES org_unit (tenant_id, id)
);
CREATE INDEX org_position_tenant_unit_idx ON org_position (tenant_id, unit_id);
CREATE UNIQUE INDEX org_position_tenant_head_uk ON org_position (tenant_id, unit_id) WHERE head AND valid_to IS NULL;
CREATE UNIQUE INDEX org_position_tenant_code_uk ON org_position (tenant_id, lower(code)) WHERE code IS NOT NULL AND valid_to IS NULL;
-- People picker: prefix search on titles (research R14).
CREATE INDEX org_position_tenant_title_idx ON org_position (tenant_id, lower(title) text_pattern_ops);

-- A person holding a position over a date range. Kind PRIMARY in 1a (ADDITIONAL and ACTING in 1b). At any date a
-- position has at most one active PRIMARY holder and a user at most one active PRIMARY position (checked by the service
-- under row locks).
CREATE TABLE org_assignment (
    id          uuid        PRIMARY KEY,
    tenant_id   uuid        NOT NULL,
    user_id     uuid        NOT NULL,
    position_id uuid        NOT NULL,
    kind        text        NOT NULL DEFAULT 'PRIMARY' CHECK (kind IN ('PRIMARY')),
    valid_from  date        NOT NULL,
    valid_to    date        CHECK (valid_to > valid_from),
    status      text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'CANCELLED')),
    version     bigint      NOT NULL DEFAULT 0,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, position_id) REFERENCES org_position (tenant_id, id)
);
CREATE INDEX org_assignment_tenant_position_idx ON org_assignment (tenant_id, position_id, valid_from);
CREATE INDEX org_assignment_tenant_user_idx ON org_assignment (tenant_id, user_id, valid_from);

-- Position → position, one active SOLID line per position at any date (kind ready for dotted lines).
CREATE TABLE org_reporting_line (
    id                     uuid        PRIMARY KEY,
    tenant_id              uuid        NOT NULL,
    position_id            uuid        NOT NULL,
    reports_to_position_id uuid        NOT NULL,
    kind                   text        NOT NULL DEFAULT 'SOLID' CHECK (kind IN ('SOLID')),
    valid_from             date        NOT NULL,
    valid_to               date        CHECK (valid_to > valid_from),
    status                 text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'CANCELLED')),
    version                bigint      NOT NULL DEFAULT 0,
    created_at             timestamptz NOT NULL,
    updated_at             timestamptz NOT NULL,
    UNIQUE (tenant_id, id),
    CHECK (reports_to_position_id <> position_id),
    FOREIGN KEY (tenant_id, position_id) REFERENCES org_position (tenant_id, id),
    FOREIGN KEY (tenant_id, reports_to_position_id) REFERENCES org_position (tenant_id, id)
);
CREATE INDEX org_reporting_line_tenant_position_idx ON org_reporting_line (tenant_id, position_id, valid_from);
CREATE INDEX org_reporting_line_tenant_reports_to_idx ON org_reporting_line (tenant_id, reports_to_position_id);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['org_legal_entity', 'org_unit', 'org_position', 'org_assignment', 'org_reporting_line'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY tenant_isolation ON %I FOR ALL
            USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
            WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))$p$, t);
    END LOOP;
END $$;
