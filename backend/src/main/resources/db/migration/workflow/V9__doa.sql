-- E04 T6 (ADR-0040, design E04 §3, §4.3): the delegation of authority as tenant data. Versions go through maker-checker
-- like definitions and take effect on a date; an instance pins the version in force when it starts. Each rule scopes
-- itself (subject, memo type, initiator's unit, a condition) and lists approval groups in order. Expand only.
CREATE TABLE workflow_doa_version (
    id             uuid        PRIMARY KEY,
    tenant_id      uuid        NOT NULL,
    number         int         NOT NULL CHECK (number >= 1),
    state          text        NOT NULL CHECK (state IN ('DRAFT', 'IN_REVIEW', 'PUBLISHED', 'RETIRED')),
    effective_from date,
    revision       bigint      NOT NULL DEFAULT 0,
    created_by     uuid        NOT NULL,
    created_at     timestamptz NOT NULL,
    submitted_by   uuid,
    submitted_at   timestamptz,
    reviewed_by    uuid,
    reviewed_at    timestamptz,
    review_remark  text        CHECK (length(review_remark) BETWEEN 1 AND 4000),
    published_at   timestamptz,
    CHECK (reviewed_by IS NULL OR reviewed_by <> submitted_by),
    CHECK (state IN ('DRAFT', 'IN_REVIEW') OR effective_from IS NOT NULL),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, number)
);
-- One draft or version in review at a time; one published version per effective date.
CREATE UNIQUE INDEX workflow_doa_version_one_open_idx ON workflow_doa_version (tenant_id) WHERE state IN ('DRAFT', 'IN_REVIEW');
CREATE UNIQUE INDEX workflow_doa_version_effective_idx
    ON workflow_doa_version (tenant_id, effective_from) WHERE state = 'PUBLISHED';

CREATE TABLE workflow_doa_rule (
    id              uuid    PRIMARY KEY,
    tenant_id       uuid    NOT NULL,
    version_id      uuid    NOT NULL,
    ordinal         int     NOT NULL CHECK (ordinal >= 0),
    name            text    NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
    subject_type    text    NOT NULL CHECK (subject_type ~ '^[a-z][a-z0-9.-]{0,49}$'),
    memo_type_id    uuid,
    unit_id         uuid,
    include_subtree boolean NOT NULL DEFAULT true,
    condition       text    CHECK (length(condition) BETWEEN 1 AND 1000),
    groups          jsonb   NOT NULL CHECK (jsonb_typeof(groups) = 'array' AND octet_length(groups::text) <= 16384),
    UNIQUE (tenant_id, version_id, ordinal),
    FOREIGN KEY (tenant_id, version_id) REFERENCES workflow_doa_version (tenant_id, id) ON DELETE CASCADE
);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['workflow_doa_version', 'workflow_doa_rule'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY tenant_isolation ON %I FOR ALL
            USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
            WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))$p$, t);
    END LOOP;
END $$;

-- The DoA version an instance follows (pinned when it starts, when its definition has a `doa` step).
ALTER TABLE workflow_instance ADD COLUMN doa_version_id uuid;
ALTER TABLE workflow_instance
    ADD CONSTRAINT workflow_instance_doa_version_fk
        FOREIGN KEY (tenant_id, doa_version_id) REFERENCES workflow_doa_version (tenant_id, id);
CREATE INDEX workflow_instance_doa_version_idx ON workflow_instance (tenant_id, doa_version_id) WHERE doa_version_id IS NOT NULL;

-- Which approval group of its DoA rule a step is (0 first).
ALTER TABLE workflow_step ADD COLUMN doa_group int CHECK (doa_group BETWEEN 0 AND 9);
