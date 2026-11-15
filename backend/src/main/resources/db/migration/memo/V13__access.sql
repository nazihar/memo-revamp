-- Phase 3 E03 T4 (ADR-0050, design E03 §5): who may read a memo. memo_participant stays the one reader table: new kinds
-- COPY (copy-to, E03 T5) and SHARED (an explicit share), who gave access, when it lapses, and when it was revoked.
-- Revoked rows are kept. A share is recorded in memo_share (reason, expiry, revocation); its participant row follows it.
-- Expand only: existing rows keep their kind and stay active.

ALTER TABLE memo_participant DROP CONSTRAINT memo_participant_kind_check;
ALTER TABLE memo_participant
    ADD CONSTRAINT memo_participant_kind_check
        CHECK (kind IN ('INITIATOR', 'APPROVER', 'NOTIFIED', 'COAUTHOR', 'COPY', 'SHARED')),
    ADD COLUMN granted_by uuid,
    -- Only shares lapse; the read rule checks the time, a daily job records the expiry.
    ADD COLUMN expires_at timestamptz,
    ADD COLUMN revoked_at timestamptz,
    ADD CONSTRAINT memo_participant_expiry CHECK (expires_at IS NULL OR kind = 'SHARED');

-- The read rule's EXISTS (ADR-0050): active rows only.
CREATE INDEX memo_participant_active_idx ON memo_participant (tenant_id, user_id, memo_id) WHERE revoked_at IS NULL;
-- Unit readers (memo.read.unit) find memos by the unit they come from.
CREATE INDEX memo_tenant_origin_unit_idx ON memo (tenant_id, origin_unit_id);

CREATE TABLE memo_share (
    id            uuid        PRIMARY KEY,
    tenant_id     uuid        NOT NULL,
    memo_id       uuid        NOT NULL,
    user_id       uuid        NOT NULL,
    granted_by    uuid        NOT NULL,
    reason        text        NOT NULL CHECK (length(reason) BETWEEN 1 AND 500),
    granted_at    timestamptz NOT NULL,
    expires_at    timestamptz CHECK (expires_at IS NULL OR expires_at > granted_at),
    revoked_at    timestamptz,
    revoked_by    uuid,
    -- REVOKED by a person, EXPIRED by the daily job (revoked_by is null then).
    revoke_reason text        CHECK (revoke_reason IN ('REVOKED', 'EXPIRED')),
    CHECK ((revoked_at IS NULL) = (revoke_reason IS NULL)),
    CHECK (revoke_reason IS DISTINCT FROM 'REVOKED' OR revoked_by IS NOT NULL),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, memo_id) REFERENCES memo (tenant_id, id)
);
CREATE INDEX memo_share_tenant_memo_idx ON memo_share (tenant_id, memo_id, granted_at);
CREATE INDEX memo_share_tenant_user_idx ON memo_share (tenant_id, user_id);
-- The expiry sweep.
CREATE INDEX memo_share_tenant_expiry_idx ON memo_share (tenant_id, expires_at) WHERE revoked_at IS NULL AND expires_at IS NOT NULL;

ALTER TABLE memo_share ENABLE ROW LEVEL SECURITY;
ALTER TABLE memo_share FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON memo_share FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

-- A share is never deleted, and only its revocation changes (AGENTS §3.3: the record is sacred).
REVOKE UPDATE, DELETE, TRUNCATE ON memo_share FROM memo_app;
GRANT UPDATE (revoked_at, revoked_by, revoke_reason) ON memo_share TO memo_app;
