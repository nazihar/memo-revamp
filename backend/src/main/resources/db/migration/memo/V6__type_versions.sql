-- Phase 3 E01 T1 (ADR-0043, design E01 §1, §7): memo types get versions that go through maker-checker. memo_type keeps
-- the identity (key, code, active) and, for this release, a copy of its published version's settings, so a node still
-- on the previous release reads the same type (expand). Every existing type becomes published version 1.

ALTER TABLE memo_type
    ADD COLUMN code              text CHECK (code ~ '^[A-Z0-9][A-Z0-9-]{0,9}$'),
    ADD COLUMN published_version int  CHECK (published_version >= 1);
CREATE UNIQUE INDEX memo_type_tenant_code_uk ON memo_type (tenant_id, code) WHERE code IS NOT NULL;

CREATE TABLE memo_type_version (
    id                        uuid        PRIMARY KEY,
    tenant_id                 uuid        NOT NULL,
    memo_type_id              uuid        NOT NULL,
    number                    int         NOT NULL CHECK (number >= 1),
    state                     text        NOT NULL CHECK (state IN ('DRAFT', 'IN_REVIEW', 'PUBLISHED', 'RETIRED')),
    revision                  bigint      NOT NULL DEFAULT 0,
    name                      text        NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
    code                      text        NOT NULL CHECK (code ~ '^[A-Z0-9][A-Z0-9-]{0,9}$'),
    default_classification_id uuid,
    definition_key            text        NOT NULL CHECK (definition_key ~ '^[a-z][a-z0-9-]{0,99}$'),
    route_policy              text        NOT NULL CHECK (route_policy IN ('INITIATOR_DEFINED', 'FIXED', 'BOTH')),
    allowed_approvers         jsonb       NOT NULL DEFAULT '{}' CHECK (
        jsonb_typeof(allowed_approvers) = 'object' AND octet_length(allowed_approvers::text) <= 16384
    ),
    doa_check                 boolean     NOT NULL DEFAULT false,
    step_up_on_approve        boolean     NOT NULL DEFAULT true,
    -- Null: created by the system (the migration below, the built-in type, legacy direct edits before E01 T6).
    created_by                uuid,
    created_at                timestamptz NOT NULL,
    submitted_by              uuid,
    submitted_at              timestamptz,
    reviewed_by               uuid,
    reviewed_at               timestamptz,
    review_remark             text        CHECK (length(review_remark) BETWEEN 1 AND 4000),
    published_at              timestamptz,
    CHECK (reviewed_by IS NULL OR reviewed_by <> submitted_by),
    CHECK (state <> 'PUBLISHED' OR published_at IS NOT NULL),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, memo_type_id, number),
    FOREIGN KEY (tenant_id, memo_type_id) REFERENCES memo_type (tenant_id, id)
);
-- One draft or version in review, and one published version, per type.
CREATE UNIQUE INDEX memo_type_version_one_open_idx
    ON memo_type_version (tenant_id, memo_type_id) WHERE state IN ('DRAFT', 'IN_REVIEW');
CREATE UNIQUE INDEX memo_type_version_one_published_idx
    ON memo_type_version (tenant_id, memo_type_id) WHERE state = 'PUBLISHED';

ALTER TABLE memo_type_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE memo_type_version FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON memo_type_version FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

-- Backfill across tenants. FORCE makes the policy apply to the owner too, and no tenant is set here, so the owner
-- would see no rows: lift FORCE for the backfill and restore it before the transaction commits.
ALTER TABLE memo_type NO FORCE ROW LEVEL SECURITY;
ALTER TABLE memo_type_version NO FORCE ROW LEVEL SECURITY;

-- A short code for every type: the initials of its name (at most four), made unique per tenant with a number.
WITH words AS (
    SELECT t.id, upper(string_agg(left(w.word, 1), '' ORDER BY w.ord)) AS initials
    FROM memo_type t,
         regexp_split_to_table(regexp_replace(t.name, '[^A-Za-z0-9]+', ' ', 'g'), ' ') WITH ORDINALITY AS w(word, ord)
    WHERE w.word <> ''
    GROUP BY t.id
), base AS (
    SELECT t.id, t.tenant_id, t.created_at, coalesce(nullif(left(w.initials, 4), ''), 'T') AS code
    FROM memo_type t LEFT JOIN words w ON w.id = t.id
), numbered AS (
    SELECT id, code, row_number() OVER (PARTITION BY tenant_id, code ORDER BY created_at, id) AS n FROM base
)
UPDATE memo_type m
SET code = CASE WHEN numbered.n = 1 THEN numbered.code ELSE numbered.code || numbered.n END
FROM numbered
WHERE numbered.id = m.id;

INSERT INTO memo_type_version (
    id, tenant_id, memo_type_id, number, state, name, code, default_classification_id, definition_key, route_policy,
    allowed_approvers, doa_check, step_up_on_approve, created_by, created_at, published_at
)
SELECT uuidv7(), tenant_id, id, 1, 'PUBLISHED', name, code, default_classification_id, definition_key, route_policy,
       allowed_approvers, doa_check, step_up_on_approve, NULL, created_at, updated_at
FROM memo_type;

UPDATE memo_type SET published_version = 1;

ALTER TABLE memo_type FORCE ROW LEVEL SECURITY;
ALTER TABLE memo_type_version FORCE ROW LEVEL SECURITY;
