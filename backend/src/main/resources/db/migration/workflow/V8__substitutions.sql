-- E04 T5 (ADR-0040, design E04 §3, §4.5): dated substitutions. While one is in force, new tasks for the principal go to
-- the substitute, who acts for them. Phase 1b adds self-service and may take them into its delegation model.
CREATE TABLE workflow_substitution (
    id            uuid        PRIMARY KEY,
    tenant_id     uuid        NOT NULL,
    principal_id  uuid        NOT NULL,
    substitute_id uuid        NOT NULL,
    valid_from    date        NOT NULL,
    valid_to      date,
    reason        text        CHECK (length(reason) <= 2000),
    created_by    uuid        NOT NULL,
    created_at    timestamptz NOT NULL,
    CHECK (principal_id <> substitute_id),
    CHECK (valid_to IS NULL OR valid_to > valid_from),
    UNIQUE (tenant_id, id)
);
CREATE INDEX workflow_substitution_principal_idx ON workflow_substitution (tenant_id, principal_id, valid_from);

ALTER TABLE workflow_substitution ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow_substitution FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON workflow_substitution FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));
