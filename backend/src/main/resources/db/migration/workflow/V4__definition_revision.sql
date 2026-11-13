-- E03 T7 (design E03 §5): draft bodies are replaced with If-Match, so a version row carries a revision that every
-- change increments; and a definition has at most one version being worked on (a draft or one in review) at a time.
ALTER TABLE workflow_definition_version
    ADD COLUMN revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0);

CREATE UNIQUE INDEX workflow_definition_version_open_idx
    ON workflow_definition_version (tenant_id, definition_id) WHERE state IN ('DRAFT', 'IN_REVIEW');
