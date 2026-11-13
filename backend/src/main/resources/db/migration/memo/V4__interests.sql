-- E04 T4 (design E04 §3, §4.4): people with a declared interest in a memo (a beneficiary, a related party, an approver
-- who stood aside) are never asked to decide on it (SOD-4). The reason is business content: it stays here, not in the
-- audit log.
CREATE TABLE memo_interest (
    tenant_id   uuid        NOT NULL,
    memo_id     uuid        NOT NULL,
    user_id     uuid        NOT NULL,
    declared_by uuid        NOT NULL,
    reason      text        NOT NULL CHECK (length(reason) BETWEEN 1 AND 4000),
    created_at  timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, memo_id, user_id),
    FOREIGN KEY (tenant_id, memo_id) REFERENCES memo (tenant_id, id)
);

ALTER TABLE memo_interest ENABLE ROW LEVEL SECURITY;
ALTER TABLE memo_interest FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON memo_interest FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));
