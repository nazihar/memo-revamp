-- Phase 4 E01 T1 (ADR-0056, ADR-0057, design E01 §1, §7): forum types are reviewed policy templates. forum_type holds the
-- identity; forum_type_version holds the policy and moves DRAFT → IN_REVIEW → PUBLISHED → RETIRED (common.review).
-- Expand only: new tables.

CREATE TABLE forum_type (
    id                uuid        PRIMARY KEY,
    tenant_id         uuid        NOT NULL,
    code              text        NOT NULL CHECK (code ~ '^[A-Z0-9][A-Z0-9-]{0,9}$'),
    active            boolean     NOT NULL DEFAULT true,
    published_version int         CHECK (published_version >= 1),
    created_by        uuid,
    created_at        timestamptz NOT NULL,
    version           bigint      NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, code)
);

CREATE TABLE forum_type_version (
    id                        uuid        PRIMARY KEY,
    tenant_id                 uuid        NOT NULL,
    forum_type_id             uuid        NOT NULL,
    number                    int         NOT NULL CHECK (number >= 1),
    state                     text        NOT NULL CHECK (state IN ('DRAFT', 'IN_REVIEW', 'PUBLISHED', 'RETIRED')),
    revision                  bigint      NOT NULL DEFAULT 0,
    name                      text        NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
    description               text        CHECK (length(description) BETWEEN 1 AND 2000),
    default_classification_id uuid,
    retention                 jsonb       CHECK (retention IS NULL OR jsonb_typeof(retention) = 'object'),
    member_attributes         jsonb       NOT NULL DEFAULT '[]' CHECK (
        jsonb_typeof(member_attributes) = 'array' AND octet_length(member_attributes::text) <= 4096
    ),
    quorum                    jsonb       NOT NULL CHECK (jsonb_typeof(quorum) = 'object' AND octet_length(quorum::text) <= 16384),
    outcomes                  jsonb       NOT NULL CHECK (jsonb_typeof(outcomes) = 'array' AND octet_length(outcomes::text) <= 8192),
    term_policy               jsonb       NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(term_policy) = 'object'),
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
    UNIQUE (tenant_id, forum_type_id, number),
    FOREIGN KEY (tenant_id, forum_type_id) REFERENCES forum_type (tenant_id, id)
);
-- One draft or version in review, and one published version, per type.
CREATE UNIQUE INDEX forum_type_version_one_open_idx
    ON forum_type_version (tenant_id, forum_type_id) WHERE state IN ('DRAFT', 'IN_REVIEW');
CREATE UNIQUE INDEX forum_type_version_one_published_idx
    ON forum_type_version (tenant_id, forum_type_id) WHERE state = 'PUBLISHED';

ALTER TABLE forum_type ENABLE ROW LEVEL SECURITY;
ALTER TABLE forum_type FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON forum_type FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

ALTER TABLE forum_type_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE forum_type_version FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON forum_type_version FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

-- The app role reads and writes types and versions but never deletes them (ADR-0056: nothing is deleted).
REVOKE DELETE, TRUNCATE ON forum_type, forum_type_version FROM memo_app;
